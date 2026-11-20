package io.raijin.brain

import io.raijin.contract.DayPart
import io.raijin.contract.MusicEvent
import io.raijin.contract.MusicEventType
import io.raijin.contract.TasteSignal
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.pow

/**
 * MU-6: local, interpretable music preference model.
 *
 * Pure aggregation over recorded [MusicEvent]s with exponential time decay.
 * Every signal can explain itself from raw counts - no learned weights the
 * owner cannot inspect. Runs entirely on-device; the events never leave it.
 */
object MusicTasteModel {

    /** Score halves every 14 days: recent listening counts more. */
    const val HALF_LIFE_MILLIS: Long = 14L * 24 * 60 * 60 * 1000

    private const val W_PLAYED = 1.0
    private const val W_REPLAYED = 1.5
    private const val W_LIKED = 3.0
    private const val W_DISLIKED = -4.0
    private const val W_SKIPPED_EARLY = -2.0

    /** COMPLETED is scaled by how much of the track actually played. */
    private const val W_COMPLETED = 2.0

    fun dayPart(atEpochMillis: Long, zone: TimeZone): DayPart {
        val hour = Calendar.getInstance(zone).apply { timeInMillis = atEpochMillis }
            .get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> DayPart.MORNING
            in 12..16 -> DayPart.AFTERNOON
            in 17..21 -> DayPart.EVENING
            else -> DayPart.NIGHT
        }
    }

    fun isWeekend(atEpochMillis: Long, zone: TimeZone): Boolean {
        val day = Calendar.getInstance(zone).apply { timeInMillis = atEpochMillis }
            .get(Calendar.DAY_OF_WEEK)
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }

    internal fun weight(event: MusicEvent): Double = when (event.type) {
        MusicEventType.PLAYED -> W_PLAYED
        MusicEventType.COMPLETED -> W_COMPLETED * (event.completionRatio ?: 0.0f)
        MusicEventType.SKIPPED_EARLY -> W_SKIPPED_EARLY
        MusicEventType.REPLAYED -> W_REPLAYED
        MusicEventType.LIKED -> W_LIKED
        MusicEventType.DISLIKED -> W_DISLIKED
    }

    internal fun decay(eventAgeMillis: Long): Double {
        if (eventAgeMillis <= 0) return 1.0
        return 2.0.pow(-(eventAgeMillis.toDouble() / HALF_LIFE_MILLIS))
    }

    /**
     * Aggregate events by [keyOf] (artist, language, or genre) into ranked,
     * self-explaining signals. Events with a blank key are skipped. Results
     * sort by score descending, ties broken by recency-weighted play count.
     */
    fun aggregate(
        events: List<MusicEvent>,
        nowEpochMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
        keyOf: (MusicEvent) -> String?,
        limit: Int = 10,
    ): List<TasteSignal> {
        data class Bucket(
            var score: Double = 0.0,
            var plays: Int = 0,
            var likes: Int = 0,
            var dislikes: Int = 0,
            var earlySkips: Int = 0,
            val dayPartCounts: MutableMap<DayPart, Int> = mutableMapOf(),
            var weekend: Int = 0,
            var total: Int = 0,
        )

        val buckets = LinkedHashMap<String, Bucket>()
        for (event in events) {
            val key = keyOf(event)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val b = buckets.getOrPut(key) { Bucket() }
            val age = nowEpochMillis - event.atEpochMillis
            b.score += weight(event) * decay(age)
            when (event.type) {
                MusicEventType.PLAYED, MusicEventType.COMPLETED, MusicEventType.REPLAYED -> b.plays++
                MusicEventType.LIKED -> b.likes++
                MusicEventType.DISLIKED -> b.dislikes++
                MusicEventType.SKIPPED_EARLY -> b.earlySkips++
            }
            val part = dayPart(event.atEpochMillis, zone)
            b.dayPartCounts.merge(part, 1, Int::plus)
            if (isWeekend(event.atEpochMillis, zone)) b.weekend++
            b.total++
        }

        return buckets.entries
            .sortedWith(compareByDescending<Map.Entry<String, Bucket>> { it.value.score }.thenByDescending { it.value.plays })
            .take(limit)
            .map { (name, b) ->
                TasteSignal(
                    name = name,
                    score = b.score,
                    plays = b.plays,
                    likes = b.likes,
                    dislikes = b.dislikes,
                    earlySkips = b.earlySkips,
                    dominantDayPart = b.dayPartCounts.maxByOrNull { it.value }?.key,
                    weekendShare = if (b.total == 0) 0f else b.weekend.toFloat() / b.total,
                )
            }
    }

    /** Plain-language evidence for a signal ("why" on the taste screen). */
    fun explain(signal: TasteSignal): String {
        val parts = mutableListOf<String>()
        if (signal.plays > 0) parts += "played ${signal.plays} time${if (signal.plays == 1) "" else "s"}"
        if (signal.likes > 0) parts += "liked ${signal.likes} time${if (signal.likes == 1) "" else "s"}"
        if (signal.dislikes > 0) parts += "disliked ${signal.dislikes} time${if (signal.dislikes == 1) "" else "s"}"
        if (signal.earlySkips > 0) parts += "skipped early ${signal.earlySkips} time${if (signal.earlySkips == 1) "" else "s"}"
        signal.dominantDayPart?.let { parts += "mostly ${it.name.lowercase()}" }
        return if (parts.isEmpty()) "no listening yet" else parts.joinToString(", ")
    }
}
