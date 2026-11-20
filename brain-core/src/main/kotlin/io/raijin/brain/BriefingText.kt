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
            val shown = digests.take(MAX_DIGEST_APPS)
            val parts = shown.map { d ->
                when {
                    d.snippet != null && d.count > 1 -> "${d.label}: ${d.snippet}, plus ${d.count - 1} more."
                    d.snippet != null -> "${d.label}: ${d.snippet}."
                    d.count == 1 -> "${d.label}: 1 notification."
                    else -> "${d.label}: ${d.count} notifications."
                }
            }
            val rest = digests.size - shown.size
            val coverage = if (rest > 0) "And $rest more app(s) not summarized." else ""
            (parts + coverage).filter { it.isNotBlank() }.joinToString(" ")
        }
    }
}
