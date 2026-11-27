package io.raijin.brain

/** Briefings describe notification coverage, never pretend it is mailbox unread state. */
object BriefingText {
    fun greeting(localHour: Int): String = when (localHour) {
        in 0..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    fun notifications(accessGranted: Boolean, connected: Boolean, counts: Map<String, Int>): String = when {
        !accessGranted -> "Messages couldn't be checked. Notification access is off; enable it only if you want notification summaries."
        !connected -> "Messages couldn't be checked. The notification listener isn't connected yet."
        counts.values.sum() > 0 -> "${counts.values.sum()} active message notification(s) from supported apps. Notifications aren't a complete unread-message count."
        else -> "No active message notifications found in supported apps. Unread messages inside apps may not have notifications."
    }

    /**
     * How a digest line was produced. EXTRACTION is a deterministic fact
     * re-stated from notification text (never invented); PREVIEW is a
     * sanitized, truncated quote of the notification text and must never be
     * described as an AI summary; SUPPRESSED means count-only.
     */
    enum class SummaryKind { EXTRACTION, PREVIEW, SUPPRESSED }

    /**
     * One app's active-notification digest. [snippet] is a sanitized one-liner
     * from the newest notification, or null when the app's bodies are
     * suppressed (messaging apps, secret content, OTP/code material).
     */
    data class AppDigest(
        val label: String,
        val count: Int,
        val snippet: String?,
        val kind: SummaryKind = if (snippet == null) SummaryKind.SUPPRESSED else SummaryKind.PREVIEW,
        /** Notifications the on-device rules marked important / promotional (0 = not triaged). */
        val importantCount: Int = 0,
        val promoCount: Int = 0,
        /** Sanitized one-liner of the newest important notification, if its body may be quoted. */
        val importantSnippet: String? = null,
        /** Plain-words description of the app ("email", "chat messages"), null when unknown. */
        val appKind: String? = null,
    )

    private const val MAX_DIGEST_APPS = 3

    /**
     * Brief content summaries of active notifications. Honest about coverage:
     * says when apps were not summarized, and never pretends notifications
     * are a complete unread count.
     */
    fun notificationDigest(accessGranted: Boolean, connected: Boolean, digests: List<AppDigest>): String = when {
        !accessGranted -> "Notifications couldn't be checked. Notification access is off; enable it only if you want notification summaries."
        !connected -> "Notifications couldn't be checked. The notification listener isn't connected yet."
        digests.isEmpty() -> "No active notifications found. Messages without notifications aren't covered."
        else -> {
            val shown = digests.sortedByDescending { it.importantCount > 0 }.take(MAX_DIGEST_APPS)
            val parts = shown.flatMap { d -> digestParts(d) }
            val rest = digests.size - shown.size
            val coverage = if (rest > 0) "And $rest more app(s) not summarized." else ""
            (parts + coverage).filter { it.isNotBlank() }.joinToString(" ")
        }
    }

    private fun digestParts(d: AppDigest): List<String> {
        val name = if (d.appKind != null) "${d.label} (${d.appKind})" else d.label
        if (d.importantCount == 0 && d.promoCount == 0) {
            return listOf(
                when {
                    d.snippet != null && d.count > 1 -> "$name: ${d.snippet}, plus ${d.count - 1} more."
                    d.snippet != null -> "$name: ${d.snippet}."
                    d.count == 1 -> "$name: 1 notification."
                    else -> "$name: ${d.count} notifications."
                },
            )
        }
        val out = mutableListOf<String>()
        if (d.importantCount > 0) {
            out += if (d.importantSnippet != null) "Important, $name: ${d.importantSnippet}."
            else "Important, $name: ${d.importantCount} notification${if (d.importantCount > 1) "s" else ""}."
        }
        val other = d.count - d.importantCount - d.promoCount
        if (other > 0) out += "$name: $other other."
        if (d.promoCount > 0) out += "$name: ${d.promoCount} promotional."
        return out
    }

    /**
     * NT-5: answer an on-demand notification question ("kya notifications
     * aaye?", "WhatsApp pe kisne bheja?") from the local buffer. Same
     * honesty rules as the brief: names when access is off or the listener
     * is disconnected, never pretends notifications are a complete unread
     * count, and keeps suppressed bodies count-only.
     */
    fun notificationQueryAnswer(
        accessGranted: Boolean,
        connected: Boolean,
        digests: List<AppDigest>,
        appFilter: String?,
    ): String {
        if (!accessGranted) {
            return "Notifications couldn't be checked. Notification access is off; enable it only if you want notification summaries."
        }
        if (!connected) {
            return "Notifications couldn't be checked. The notification listener isn't connected yet."
        }
        if (appFilter == null) {
            return notificationDigest(accessGranted, connected, digests)
        }
        val f = appFilter.trim().lowercase()
        val matched = digests.filter { it.label.lowercase().contains(f) }
        if (matched.isEmpty()) {
            return "No active ${appFilter.trim()} notifications found. Messages without notifications aren't covered."
        }
        return notificationDigest(accessGranted, connected, matched)
    }
}
