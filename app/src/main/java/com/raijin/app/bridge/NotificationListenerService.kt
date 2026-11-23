package com.raijin.app.bridge

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.raijin.app.RaijinService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Background perception sentinel.
 *
 * Continuously monitors notifications from critical communications channels
 * (WhatsApp, Gmail, Outlook, Messages, SMS):
 * 1. Maintains live unread counts for morning briefings.
 * 2. Scans text for dates, flights, meetings, and bills to auto-schedule reminders.
 * 3. Proactively triggers exact alarms and notifies the floating HUD overlay.
 */
class NotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "RaijinNotifListener"

        private val WATCHED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.google.android.apps.messaging",
        )

        /**
         * One cached entry per active notification key. Bodies are stored only
         * as sanitized one-line snippets (or not at all): raw notification text
         * is never kept and never leaves the device.
         */
        data class CachedNotification(
            val pkg: String,
            val appLabel: String,
            val snippet: String?,
            val kind: io.raijin.brain.BriefingText.SummaryKind,
            val postTimeMs: Long,
        )

        /** NT-1: ring buffer (60) + 7-day retention, JVM-tested in NotificationCacheTest. */
        private val activeCache = NotificationCache<CachedNotification>(postedAtMs = { it.postTimeMs })

        @Volatile var listenerConnected: Boolean = false
            private set
        val cachedUnread: Map<String, Int>
            get() = activeCache.values().groupingBy { it.pkg }.eachCount()

        /** Packages never summarized: Raijin itself and the system UI. */
        private val EXCLUDED_PACKAGES = setOf("com.raijin.app", "com.android.systemui", "android")

        /**
         * Per-app digest for briefings: label, active count, and the newest
         * sanitized snippet (null when every body is suppressed).
         */
        val cachedAppDigests: List<io.raijin.brain.BriefingText.AppDigest>
            get() = activeCache.values()
                .groupBy { it.pkg }
                .map { (_, list) ->
                    val newest = list.maxByOrNull { it.postTimeMs }
                    io.raijin.brain.BriefingText.AppDigest(
                        label = newest?.appLabel ?: list.first().pkg,
                        count = list.size,
                        snippet = newest?.snippet,
                        kind = newest?.kind ?: io.raijin.brain.BriefingText.SummaryKind.SUPPRESSED,
                    )
                }
                .sortedByDescending { it.count }

        /** Cache of recently scheduled event titles to prevent duplicate reminders */
        private val recentEventTitles = mutableSetOf<String>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title  = extras.getString("android.title") ?: ""
        val text   = extras.getCharSequence("android.text")?.toString() ?: ""

        cacheNotification(sbn)

        // Proactive detection stays limited to watched communication channels.
        if (sbn.packageName in WATCHED_PACKAGES && title.isNotBlank()) {
            serviceScope.launch {
                handleProactivePerception(title, text, sbn.packageName)
            }
        }
    }

    /**
     * Cache one active notification (all apps except self/system). Updates to
     * the same key replace the previous entry, so a self-updating notification
     * is never double-counted. Called from both onNotificationPosted and
     * onListenerConnected; proactive perception is NOT re-run from here.
     */
    private fun cacheNotification(sbn: StatusBarNotification) {
        if (sbn.packageName in EXCLUDED_PACKAGES) return
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val suppressed = NotificationSanitizer.isBodySuppressed(
            sbn.packageName,
            sbn.notification.category,
            sbn.notification.visibility,
            android.app.Notification.VISIBILITY_SECRET,
        )
        val raw = text.ifBlank { title }
        val sanitized = if (suppressed) null else NotificationSanitizer.sanitize(raw)
        // Deterministic extraction first (grounded fact re-statement); fall
        // back to the sanitized preview quote, never an invented summary.
        val extraction = if (sanitized != null) NotificationSanitizer.extractAlert(raw) else null
        val (snippet, kind) = when {
            sanitized == null -> null to io.raijin.brain.BriefingText.SummaryKind.SUPPRESSED
            extraction != null -> extraction to io.raijin.brain.BriefingText.SummaryKind.EXTRACTION
            else -> sanitized to io.raijin.brain.BriefingText.SummaryKind.PREVIEW
        }
        activeCache.put(
            sbn.key,
            CachedNotification(
                pkg = sbn.packageName,
                appLabel = resolveAppLabel(sbn.packageName),
                snippet = snippet,
                kind = kind,
                postTimeMs = sbn.postTime,
            ),
        )
    }

    private fun resolveAppLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0),
        ).toString()
    } catch (_: Exception) {
        pkg
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeCache.remove(sbn.key)
    }

    private suspend fun handleProactivePerception(title: String, text: String, pkg: String) {
        val detected = ProactiveNotificationParser.parse(title, text) ?: return

        // Deduplication: Avoid scheduling the exact same reminder twice
        val dedupKey = "${detected.title}_${detected.triggerEpochMs / 60000}"
        synchronized(recentEventTitles) {
            if (dedupKey in recentEventTitles) return
            recentEventTitles.add(dedupKey)
            if (recentEventTitles.size > 100) {
                recentEventTitles.clear()
            }
        }

        // NT-4 split (owner decision 2026-11-23): only high-importance AND
        // high-confidence events auto-create. Anything lower waits as an
        // island suggestion chip until the owner accepts it.
        val autoCreate = detected.importance == EventImportance.HIGH &&
            detected.confidence == EventConfidence.HIGH

        if (!autoCreate) {
            val id = PendingSuggestions.store.add(detected)
            RaijinService.notifyProactiveSuggestion(id, detected)
            return
        }

        try {
            ProactiveReminderScheduler.schedule(applicationContext, detected, pkg)

            // Relay to HUD / TTS if RaijinService is running
            RaijinService.notifyProactiveEvent(detected.title, detected.category)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist proactive reminder: ${e.message}", e)
        }
    }

    override fun onListenerConnected() {
        activeCache.clear()
        try {
            activeNotifications?.forEach { sbn -> cacheNotification(sbn) }
            listenerConnected = true
        } catch (e: Exception) {
            listenerConnected = false
            Log.w(TAG, "Couldn't read active notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        activeCache.clear()
        Log.w(TAG, "Notification listener disconnected")
    }
}
