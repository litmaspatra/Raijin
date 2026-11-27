package io.raijin.brain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * TM-1: deterministic Hinglish/English date-time parser.
 *
 * Understands relative ("5 minute baad", "in 2 hours", "aadha ghanta baad"),
 * absolute ("kal subah 6 baje", "next Friday 3pm", "aaj shaam ko", "parso"),
 * and recurring ("har somvar 8 baje", "roz raat 9 baje", "every monday 8am")
 * forms. Same input + same `now` always gives the same answer.
 *
 * Defaults (PRD TM-1): subah 07:00, dopahar 13:00, shaam 18:00, raat 21:00.
 */
object HinglishTimeParser {

    enum class Recurrence { NONE, DAILY, WEEKLY }

    data class Parsed(
        val triggerAt: ZonedDateTime,
        val recurrence: Recurrence,
        /** The fragment of the input that carried the date-time meaning. */
        val matchedFragment: String,
    )

    private val MERIDIEM_SPOKEN = Regex("""(\d)\s*([ap])(?:\.\s?|\s)m\.?(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val NUMBER_WORD_MERIDIEM = Regex(
        """\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\s*(am|pm)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val HOUR_WORDS = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
    )

    /**
     * Voice transcripts write "9:00 a.m." / "6 p.m." / "9 a m". Rewrite those to
     * "9:00 am" / "6 pm" (and "nine am" to "9 am") so the clock time is read
     * instead of falling back to a time-of-day word's default hour.
     */
    fun normalizeMeridiem(text: String): String {
        var out = MERIDIEM_SPOKEN.replace(text) { m -> "${m.groupValues[1]} ${m.groupValues[2].lowercase()}m" }
        out = NUMBER_WORD_MERIDIEM.replace(out) { m ->
            "${HOUR_WORDS.indexOf(m.groupValues[1].lowercase()) + 1} ${m.groupValues[2].lowercase()}"
        }
        return out
    }

    fun parse(text: String, now: ZonedDateTime): Parsed? {
        val t = " " + normalizeMeridiem(text).lowercase().replace(Regex("[^a-z0-9:. ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

        parseRelative(t, now)?.let { return it }
        return parseAbsolute(t, now)
    }

    // ------------------------------------------------------------------
    // Relative: "in 2 hours", "5 minute baad", "aadha ghanta baad"
    // ------------------------------------------------------------------

    private val WORD_NUMBERS = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "paanch" to 5, "pach" to 5,
        "chhe" to 6, "chhah" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30,
    )

    private val SPECIAL_DURATIONS = mapOf(
        "aadha ghanta" to 30L, "aadhe ghante" to 30L,
        "dedh ghanta" to 90L, "dedh ghante" to 90L,
        "dhai ghanta" to 150L, "dhai ghante" to 150L,
        "aadha ghantaa" to 30L,
    )

    private val RELATIVE_EN = Regex(
        """\bin\s+(\d+|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty)\s*(second|seconds|sec|secs|minute|minutes|min|mins|hour|hours|hr|hrs|day|days)\b""")
    private val RELATIVE_HI = Regex(
        """\b(\d+|ek|do|teen|char|paanch|pach|chhe|chhah|saat|aath|nau|das)\s*(second|seconds|sec|secs|minute|minutes|min|mins|hour|hours|hr|hrs|ghanta|ghante|din|day|days)\s*(baad|baad mein|mein)\b""")

    private fun numberOf(token: String): Long? =
        token.toLongOrNull() ?: WORD_NUMBERS[token]?.toLong()

    private fun unitMillis(unit: String): Long? = when (unit) {
        "second", "seconds", "sec", "secs" -> 1_000L
        "minute", "minutes", "min", "mins" -> 60_000L
        "hour", "hours", "hr", "hrs", "ghanta", "ghante" -> 3_600_000L
        "day", "days", "din" -> 86_400_000L
        else -> null
    }

    private fun parseRelative(t: String, now: ZonedDateTime): Parsed? {
        for ((phrase, minutes) in SPECIAL_DURATIONS) {
            if (t.contains(" $phrase ") && (t.contains(" baad") || t.contains(" mein"))) {
                return Parsed(now.plusMinutes(minutes), Recurrence.NONE, "$phrase baad")
            }
        }
        RELATIVE_EN.find(t)?.let { m ->
            val n = numberOf(m.groupValues[1]) ?: return@let
            val unitMs = unitMillis(m.groupValues[2]) ?: return@let
            return Parsed(now.plusNanos(unitMs * n * 1_000_000L), Recurrence.NONE, m.value.trim())
        }
        RELATIVE_HI.find(t)?.let { m ->
            val n = numberOf(m.groupValues[1]) ?: return@let
            val unitMs = unitMillis(m.groupValues[2]) ?: return@let
            return Parsed(now.plusNanos(unitMs * n * 1_000_000L), Recurrence.NONE, m.value.trim())
        }
        return null
    }

    // ------------------------------------------------------------------
    // Absolute: day + daypart + clock time, optional recurrence
    // ------------------------------------------------------------------

    private enum class DayPart(val defaultTime: LocalTime, val isPm: Boolean?) {
        SUBAH(LocalTime.of(7, 0), false),
        DOPAHAR(LocalTime.of(13, 0), true),
        SHAAM(LocalTime.of(18, 0), true),
        RAAT(LocalTime.of(21, 0), true),
    }

    private val DAYPARTS = listOf(
        listOf("subah", "morning") to DayPart.SUBAH,
        listOf("dopahar", "afternoon") to DayPart.DOPAHAR,
        listOf("shaam", "sham", "evening") to DayPart.SHAAM,
        listOf("raat", "night") to DayPart.RAAT,
    )

    private val WEEKDAYS = listOf(
        listOf("somvar", "somvaar", "monday") to DayOfWeek.MONDAY,
        listOf("mangalvar", "mangalvaar", "tuesday") to DayOfWeek.TUESDAY,
        listOf("budhvar", "budhvaar", "wednesday") to DayOfWeek.WEDNESDAY,
        listOf("guruvar", "guruvar", "veervar", "virvar", "thursday") to DayOfWeek.THURSDAY,
        listOf("shukravar", "shukravaar", "friday") to DayOfWeek.FRIDAY,
        listOf("shanivar", "shanivaar", "saturday") to DayOfWeek.SATURDAY,
        listOf("ravivar", "ravivaar", "itvar", "itvaar", "sunday") to DayOfWeek.SUNDAY,
    )

    private val TIME_12H = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)\b""")
    private val TIME_BAJE = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*baje\b""")
    private val TIME_SAWA = Regex("""\bsawa\s+(\d{1,2})\s*baje\b""")   // quarter past
    private val TIME_SADHE = Regex("""\bsadhe\s+(\d{1,2})\s*baje\b""") // half past
    private val TIME_PAUNE = Regex("""\bpaune\s+(\d{1,2})\s*baje\b""") // quarter to

    private data class ClockTime(val hour24: Int, val minute: Int, val fragment: String, val explicitAmPm: Boolean = false)

    private fun parseClockTime(t: String, dayPart: DayPart?): ClockTime? {
        TIME_12H.find(t)?.let { m ->
            var h = m.groupValues[1].toInt()
            if (h !in 1..12) return@let
            val min = m.groupValues[2].ifEmpty { "00" }.toInt()
            val pm = m.groupValues[3].startsWith("p")
            h %= 12
            return ClockTime(if (pm) h + 12 else h, min, m.value.trim(), explicitAmPm = true)
        }
        TIME_SAWA.find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h !in 1..12) return@let
            return ClockTime(applyDayPart(h, dayPart), 15, m.value.trim())
        }
        TIME_SADHE.find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h !in 1..12) return@let
            return ClockTime(applyDayPart(h, dayPart), 30, m.value.trim())
        }
        TIME_PAUNE.find(t)?.let { m ->
            // paune N = (N-1):45
            val h0 = m.groupValues[1].toInt()
            if (h0 !in 2..13) return@let
            return ClockTime(applyDayPart(h0 - 1, dayPart), 45, m.value.trim())
        }
        TIME_BAJE.find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h !in 1..12) return@let
            val min = m.groupValues[2].ifEmpty { "00" }.toInt()
            return ClockTime(applyDayPart(h, dayPart), min, m.value.trim())
        }
        return null
    }

    /** Map a 1-12 clock hour into 24h using the daypart's am/pm sense. */
    private fun applyDayPart(hour12: Int, dayPart: DayPart?): Int {
        val h = hour12 % 12
        return when {
            dayPart == null -> hour12 // resolved later against `now`
            dayPart.isPm == true -> h + 12
            else -> h
        }
    }

    private fun findDayPart(t: String): Pair<DayPart, String>? {
        for ((words, part) in DAYPARTS) {
            for (w in words) {
                if (t.contains(" $w ")) return part to w
            }
        }
        return null
    }

    private fun findWeekday(t: String): Pair<DayOfWeek, String>? {
        for ((words, day) in WEEKDAYS) {
            for (w in words) {
                if (t.contains(" $w ")) return day to w
            }
        }
        return null
    }

    private fun parseAbsolute(t: String, now: ZonedDateTime): Parsed? {
        val dayPartHit = findDayPart(t)
        val weekdayHit = findWeekday(t)
        val clock = parseClockTime(t, dayPartHit?.first)

        val recurringDaily = t.contains(" har roz ") || t.contains(" roz ") || t.contains(" daily ") || t.contains(" every day ")
        val recurringWeekly = (t.contains(" har ") && weekdayHit != null) ||
            (t.contains(" every ") && weekdayHit != null)

        // Day offset
        var dayOffset: Long? = null
        var dayFragment = ""
        when {
            t.contains(" parso ") -> { dayOffset = 2; dayFragment = "parso" }
            t.contains(" narso ") -> { dayOffset = 3; dayFragment = "narso" }
            t.contains(" day after tomorrow ") -> { dayOffset = 2; dayFragment = "day after tomorrow" }
            t.contains(" kal ") -> { dayOffset = 1; dayFragment = "kal" }
            t.contains(" aaj ") -> { dayOffset = 0; dayFragment = "aaj" }
            t.contains(" tomorrow ") -> { dayOffset = 1; dayFragment = "tomorrow" }
            t.contains(" today ") -> { dayOffset = 0; dayFragment = "today" }
        }

        if (clock == null && dayPartHit == null && weekdayHit == null && dayOffset == null &&
            !recurringDaily && !recurringWeekly
        ) {
            return null
        }

        val fragments = mutableListOf<String>()
        dayFragment.takeIf { it.isNotEmpty() }?.let(fragments::add)
        dayPartHit?.second?.let(fragments::add)
        weekdayHit?.second?.let(fragments::add)
        clock?.fragment?.let(fragments::add)
        // Recurrence markers carry date-time meaning too; include them so
        // callers can strip them from reminder titles.
        when {
            t.contains(" har roz ") -> fragments += listOf("har", "roz")
            recurringDaily && t.contains(" roz ") -> fragments += "roz"
            recurringDaily && t.contains(" daily ") -> fragments += "daily"
            recurringDaily && t.contains(" every day ") -> fragments += listOf("every", "day")
            recurringDaily -> fragments += "roz"
            recurringWeekly && t.contains(" har ") -> fragments += "har"
            recurringWeekly && t.contains(" every ") -> fragments += "every"
        }

        // Resolve the clock time. Ambiguous bare hours (no am/pm, no daypart)
        // are refined below once the date is known.
        val (hour24, minute) = when {
            clock != null -> clock.hour24 to clock.minute
            else -> {
                val dp = dayPartHit?.first ?: DayPart.SUBAH
                dp.defaultTime.hour to dp.defaultTime.minute
            }
        }

        // Resolve the date
        val today = now.toLocalDate()
        var date: LocalDate = when {
            weekdayHit != null -> {
                val target = weekdayHit.first
                var d = today
                if (dayOffset == null) {
                    // Bare weekday: next occurrence (today if it is that day and time is ahead)
                    while (d.dayOfWeek != target) d = d.plusDays(1)
                } else {
                    d = today.plusDays(dayOffset)
                }
                d
            }
            dayOffset != null -> today.plusDays(dayOffset)
            else -> today
        }

        var time = LocalTime.of(hour24 % 24, minute)

        // Ambiguous-hour refinement for cases without daypart/am-pm
        if (clock != null && dayPartHit == null && !clock.explicitAmPm) {
            val h12 = clock.hour24 % 12
            val candidates = listOf(
                date.atTime(h12, minute),
                date.atTime(h12 + 12, minute),
            )
            val future = candidates.filter { it.isAfter(now.toLocalDateTime()) }
            time = when {
                future.isNotEmpty() -> future.first().toLocalTime()
                date == today && weekdayHit == null && dayOffset == null -> {
                    // both AM/PM already passed today -> tomorrow AM
                    date = today.plusDays(1)
                    LocalTime.of(h12, minute)
                }
                else -> LocalTime.of(h12, minute)
            }
        }

        // If the resolved moment is already past on a one-shot with an explicit day word, keep it
        // (owner said "aaj shaam" at 7pm knowingly) EXCEPT bare-day weekday handled above.
        var trigger = date.atTime(time).atZone(now.zone)
        when {
            recurringWeekly && !trigger.isAfter(now) -> trigger = trigger.plusWeeks(1)
            recurringDaily && !trigger.isAfter(now) -> trigger = trigger.plusDays(1)
            weekdayHit != null && dayOffset == null && !trigger.isAfter(now) ->
                trigger = trigger.plusWeeks(1) // bare weekday already passed this week
            dayOffset == null && weekdayHit == null && !trigger.isAfter(now) ->
                trigger = trigger.plusDays(1) // no explicit day: next day it still makes sense
            else -> { /* explicit day word: keep exactly what the owner said */ }
        }

        val recurrence = when {
            recurringDaily -> Recurrence.DAILY
            recurringWeekly -> Recurrence.WEEKLY
            else -> Recurrence.NONE
        }

        return Parsed(trigger, recurrence, fragments.joinToString(" "))
    }
}
