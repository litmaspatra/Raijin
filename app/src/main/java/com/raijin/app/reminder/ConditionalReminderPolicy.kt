package com.raijin.app.reminder

/**
 * TM-9: PRD's 30-minute check window. A conditional reminder about to fire
 * stays quiet when the watched contact replied within the window; the event
 * path (NotificationListenerService) usually cancels earlier, this is the
 * final check at fire time. No polling loop: evaluated only when the alarm
 * actually fires.
 */
object ConditionalReminderPolicy {

    const val CHECK_WINDOW_MS = 30 * 60_000L

    /** True when the fire should be suppressed because a reply just arrived. */
    fun shouldSuppressFire(
        lastReplyMs: Long?,
        nowMs: Long,
        windowMs: Long = CHECK_WINDOW_MS,
    ): Boolean = lastReplyMs != null && nowMs - lastReplyMs in 0..windowMs
}
