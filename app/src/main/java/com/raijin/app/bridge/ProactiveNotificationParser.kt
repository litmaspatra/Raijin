package com.raijin.app.bridge

import java.util.Calendar
import java.util.regex.Pattern

data class DetectedEvent(
    val title: String,
    val triggerEpochMs: Long,
    val category: String,
    val rawText: String,
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
            )
        }

        // Check absolute clock time e.g. "at 5:30 pm"
        val timeMatcher = TIME_REGEX.matcher(lower)
        var triggerEpochMs = nowMs + (3600_000L * 2) // default 2 hours from now
        var foundTime = false

        while (timeMatcher.find()) {
            val hourStr = timeMatcher.group(1) ?: continue
            val minStr = timeMatcher.group(2) ?: "0"
            val ampm = timeMatcher.group(3)?.lowercase()

            var hour = hourStr.toIntOrNull() ?: continue
            val minute = minStr.toIntOrNull() ?: 0

            if (ampm == "pm" && hour in 1..11) hour += 12
            if (ampm == "am" && hour == 12) hour = 0

            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // If the time already passed today, assume tomorrow
                if (timeInMillis <= nowMs) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            triggerEpochMs = cal.timeInMillis
            foundTime = true
            break
        }

        // If it mentions "tomorrow", add 1 day
        if (lower.contains("tomorrow") && !foundTime) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 9) // Default 9 AM tomorrow
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            triggerEpochMs = cal.timeInMillis
        }

        return DetectedEvent(
            title = sanitizeTitle(category, title, text),
            triggerEpochMs = triggerEpochMs,
            category = category,
            rawText = combined,
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
