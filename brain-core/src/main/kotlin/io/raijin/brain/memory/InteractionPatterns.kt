package io.raijin.brain.memory

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * ME-3: daily pattern mining. Pure function over recorded interactions so the
 * job itself stays a thin scheduling shell around this.
 *
 * A pattern is "5+ interactions of the same action signature inside the same
 * time band (day part) over the window" (PRD 7.10 ME-3). Grouping is by the
 * interaction's normalized signature (lower-cased, digits stripped), never by
 * fuzzy text similarity - a pattern claim must be explainable to the owner.
 */
object InteractionPatterns {

    /** One recorded interaction: when it happened and what it was. */
    data class Interaction(
        val atEpochMillis: Long,
        /** Structured signature, e.g. "set_alarm" or "call_contact:m paa". */
        val signature: String,
    ) {
        init {
            require(atEpochMillis >= 0) { "interaction time must be non-negative" }
            require(signature.isNotBlank()) { "interaction signature must not be blank" }
        }
    }

    enum class DayPart { MORNING, AFTERNOON, EVENING, NIGHT }

    /** A mined pattern candidate, ready to persist as a stored pattern. */
    data class PatternCandidate(
        /** Normalized signature the pattern is about. */
        val signature: String,
        val dayPart: DayPart,
        val count: Int,
        val firstAtEpochMillis: Long,
        val lastAtEpochMillis: Long,
    ) {
        /** Owner-facing sentence for the "What I know about you" screen (ME-4). */
        fun describe(): String {
            val band = when (dayPart) {
                DayPart.MORNING -> "in the morning"
                DayPart.AFTERNOON -> "in the afternoon"
                DayPart.EVENING -> "in the evening"
                DayPart.NIGHT -> "at night"
            }
            return "You often ${humanize(signature)} $band ($count times)"
        }

        companion object {
            /** "action:set_alarm:#:#" -> "set alarm": strip the prefix and digit placeholders. */
            fun humanize(signature: String): String =
                signature.removePrefix("action:")
                    .replace(Regex(":#"), "")
                    .replace('_', ' ')
                    .trim()
        }
    }

    /** Default mining window: warm tier, 30 days (PRD 7.10 ME-3). */
    const val DEFAULT_WINDOW_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    const val DEFAULT_MIN_COUNT: Int = 5

    fun dayPartOf(atEpochMillis: Long, zone: ZoneId): DayPart {
        val hour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atEpochMillis), zone).hour
        return when (hour) {
            in 5..11 -> DayPart.MORNING
            in 12..16 -> DayPart.AFTERNOON
            in 17..21 -> DayPart.EVENING
            else -> DayPart.NIGHT
        }
    }

    /**
     * Normalizes a signature for grouping: lower-case, digits collapsed so
     * "set_alarm:6:30" and "set_alarm:7:00" count as the same habit.
     */
    fun normalize(signature: String): String =
        signature.lowercase().replace(Regex("\\d+"), "#").trim()

    fun findPatterns(
        interactions: List<Interaction>,
        nowEpochMillis: Long,
        zone: ZoneId,
        minCount: Int = DEFAULT_MIN_COUNT,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    ): List<PatternCandidate> {
        require(minCount >= 2) { "minCount must be at least 2" }
        val cutoff = nowEpochMillis - windowMillis
        return interactions
            .filter { it.atEpochMillis in cutoff..nowEpochMillis }
            .groupBy { normalize(it.signature) to dayPartOf(it.atEpochMillis, zone) }
            .filterValues { it.size >= minCount }
            .map { (key, group) ->
                PatternCandidate(
                    signature = key.first,
                    dayPart = key.second,
                    count = group.size,
                    firstAtEpochMillis = group.minOf { it.atEpochMillis },
                    lastAtEpochMillis = group.maxOf { it.atEpochMillis },
                )
            }
            .sortedByDescending { it.count }
    }
}
