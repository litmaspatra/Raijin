package com.raijin.app.bridge

import java.util.Calendar
import java.util.regex.Pattern

/** Owner-confirmed importance mapping (2026-11-23): flights, bills and
 *  meetings carry concrete external deadlines -> HIGH; the generic
 *  reminder bucket -> LOW. */
enum class EventImportance { HIGH, LOW }

/** HIGH when the trigger came from an explicit time expression (relative
 *  "in 30 mins", meridiem, or HH:MM); LOW for bare-number fallbacks and
 *  defaults. */
enum class EventConfidence { HIGH, LOW }

data class DetectedEvent(
    val title: String,
    val triggerEpochMs: Long,
    val category: String,
    val rawText: String,
    val importance: EventImportance,
    val confidence: EventConfidence,
)

/**
 * Deterministic background perception engine.
 *
 * Scans incoming notifications (from WhatsApp, Gmail, SMS, Outlook) to proactively
 * detect calendar events, flights, bills, and deadlines without requiring continuous
 * LLM cloud roundtrips or battery drain.
 */
object ProactiveNotificationParser {

    private val TIME_REGEX = Pattern.compile(
        """\b(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""",
        Pattern.CASE_INSENSITIVE
    )

    private val IN_TIME_REGEX = Pattern.compile(
        """\bin\s+(\d+)\s*(min|mins|minute|minutes|hour|hours|hr|hrs)\b""",
        Pattern.CASE_INSENSITIVE
    )

    private val FLIGHT_REGEX = Pattern.compile(
        """\b(flight|boarding|gate|terminal|pnr)\b""",
        Pattern.CASE_INSENSITIVE
    )

    private val MEETING_REGEX = Pattern.compile(
        """\b(meeting|zoom|google meet|teams|interview|appointment|call with|sync with)\b""",
        Pattern.CASE_INSENSITIVE
    )

    private val BILL_REGEX = Pattern.compile(
        """\b(bill|invoice|due date|payment due|statement due|pay by|electricity bill)\b""",
        Pattern.CASE_INSENSITIVE
    )

    fun parse(title: String, text: String, nowMs: Long = System.currentTimeMillis()): DetectedEvent? {
        val combined = "$title. $text".trim()
        val lower = combined.lowercase()

        val category = when {
            FLIGHT_REGEX.matcher(lower).find() -> "FLIGHT"
            MEETING_REGEX.matcher(lower).find() -> "MEETING"
            BILL_REGEX.matcher(lower).find() -> "BILL"
            lower.contains("remind") || lower.contains("deadline") || lower.contains("don't forget") -> "REMINDER"
            else -> return null
        }
        val importance = when (category) {
            "FLIGHT", "BILL", "MEETING" -> EventImportance.HIGH
            else -> EventImportance.LOW
        }

        // Check relative time first e.g. "in 30 mins"
        val inMatcher = IN_TIME_REGEX.matcher(lower)
        if (inMatcher.find()) {
            val amount = inMatcher.group(1)?.toLongOrNull() ?: 15L
            val unit = inMatcher.group(2)?.lowercase() ?: "mins"
            val deltaMs = if (unit.startsWith("h")) amount * 3600_000L else amount * 60_000L
            val triggerEpochMs = nowMs + deltaMs
            return DetectedEvent(
                title = sanitizeTitle(category, title, text),
                triggerEpochMs = triggerEpochMs,
                category = category,
                rawText = combined,
                importance = importance,
                confidence = EventConfidence.HIGH,
            )
        }

        // Check absolute clock time e.g. "at 5:30 pm"
        val hasTomorrow = lower.contains("tomorrow")
        val timeMatcher = TIME_REGEX.matcher(lower)
        var triggerEpochMs = nowMs + (3600_000L * 2) // default 2 hours from now

        // Strong candidates carry a meridiem or explicit :MM minutes; bare
        // numbers ("gate 9", "28/11") are weak fallbacks used only when no
        // strong candidate exists.
        var strongCal: Calendar? = null
        var weakCal: Calendar? = null

        while (timeMatcher.find()) {
            val hourStr = timeMatcher.group(1) ?: continue
            val minStr = timeMatcher.group(2)
            val ampm = timeMatcher.group(3)?.lowercase()

            var hour = hourStr.toIntOrNull() ?: continue
            val minute = minStr?.toIntOrNull() ?: 0

            // Fail safe on non-clock numbers ("due 28/11", "seat 45A"): a bare
            // number is a clock hour only in 0..23, a meridiem hour in 1..12,
            // minutes always 0..59. Otherwise keep looking, never roll the
            // calendar over leniently into a garbage reminder.
            if (minute !in 0..59) continue
            if (ampm != null) {
                if (hour !in 1..12) continue
                if (ampm == "pm" && hour != 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
            } else if (hour !in 0..23) {
                continue
            }

            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (hasTomorrow) {
                    // "meeting tomorrow at 5 pm" means tomorrow even when the
                    // same clock time is still ahead today.
                    add(Calendar.DAY_OF_YEAR, 1)
                } else if (timeInMillis <= nowMs) {
                    // If the time already passed today, assume tomorrow
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            val strong = ampm != null || minStr != null
            if (strong) {
                if (strongCal == null) strongCal = cal
            } else if (weakCal == null) {
                weakCal = cal
            }
        }

        val chosen = strongCal ?: weakCal
        val confidence: EventConfidence
        if (chosen != null) {
            triggerEpochMs = chosen.timeInMillis
            confidence = if (strongCal != null) EventConfidence.HIGH else EventConfidence.LOW
        } else if (hasTomorrow) {
            // "tomorrow" without a clock time defaults to 9 AM
            triggerEpochMs = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            confidence = EventConfidence.LOW
        } else {
            confidence = EventConfidence.LOW
        }

        return DetectedEvent(
            title = sanitizeTitle(category, title, text),
            triggerEpochMs = triggerEpochMs,
            category = category,
            rawText = combined,
            importance = importance,
            confidence = confidence,
        )
    }

    private fun sanitizeTitle(category: String, title: String, text: String): String {
        val snippet = if (text.length > 50) text.take(47) + "…" else text
        return when (category) {
            "FLIGHT" -> "Flight Update: $snippet"
            "MEETING" -> "Meeting: $title - $snippet"
            "BILL" -> "Payment Due: $title"
            else -> "Reminder: $snippet"
        }
    }
}
