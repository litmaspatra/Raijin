package io.raijin.brain

import io.raijin.contract.MusicEvent
import java.util.TimeZone
import kotlin.math.exp
import kotlin.random.Random

/**
 * MU-7: "play something" - sample a track from the local library, weighted
 * by the MU-6 taste model.
 *
 * Rules (PRD 7.6 MU-7):
 * - softmax over model scores with a temperature;
 * - artists dominant in the current time-of-day bucket get a boost;
 * - the last [avoidRecent] distinct tracks are never picked again;
 * - with no listening history every track is equally likely.
 *
 * Pure and deterministic under a seeded [random] for tests.
 */
object MusicTasteSampler {

    /** Minimal track view the sampler needs (app supplies contentUri as id). */
    data class Candidate(
        val id: String,
        val title: String,
        val artist: String,
    )

    /** Multiplier for artists whose dominant day-part is the current one. */
    const val DAY_PART_BOOST = 1.25

    const val DEFAULT_TEMPERATURE = 0.75

    fun pick(
        candidates: List<Candidate>,
        events: List<MusicEvent>,
        nowEpochMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
        temperature: Double = DEFAULT_TEMPERATURE,
        avoidRecent: Int = 20,
        random: Random = Random.Default,
    ): Candidate? {
        require(temperature > 0) { "temperature must be positive" }
        if (candidates.isEmpty()) return null

        // Avoid repeats from the most recent distinct tracks (newest first).
        val recentKeys = events
            .sortedByDescending { it.atEpochMillis }
            .map { it.track + "§" + it.artist }
            .distinct()
            .take(avoidRecent)
            .toSet()
        val eligible = candidates.filter { (it.title + "§" + it.artist) !in recentKeys }
            .ifEmpty { candidates } // avoiding everything would mean silence

        val artistSignals = MusicTasteModel.aggregate(events, nowEpochMillis, zone, { it.artist }, Int.MAX_VALUE)
            .associateBy { it.name }
        val trackSignals = MusicTasteModel.aggregate(
            events, nowEpochMillis, zone, { e -> e.track + "§" + e.artist }, Int.MAX_VALUE,
        ).associateBy { it.name }
        val currentPart = MusicTasteModel.dayPart(nowEpochMillis, zone)

        fun score(c: Candidate): Double {
            val artist = artistSignals[c.artist]
            var s = (artist?.score ?: 0.0) + (trackSignals[c.title + "§" + c.artist]?.score ?: 0.0)
            if (artist != null && artist.dominantDayPart == currentPart) s *= DAY_PART_BOOST
            return s
        }

        val scores = eligible.map { score(it) }
        val max = scores.max()
        val weights = scores.map { exp((it - max) / temperature) }
        val total = weights.sum()
        var draw = random.nextDouble() * total
        for (i in eligible.indices) {
            draw -= weights[i]
            if (draw <= 0) return eligible[i]
        }
        return eligible.last()
    }
}
