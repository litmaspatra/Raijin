package com.raijin.app.media

import io.raijin.contract.MusicEvent
import io.raijin.contract.MusicEventType
import io.raijin.contract.MusicSource

/**
 * MU-6 event capture: turns player callbacks into honest taste events.
 *
 * Pure Kotlin so every rule is JVM-testable. Only what the PRD names is
 * recorded: played, completed %, skipped under 30 s, replayed. A mid-track
 * skip (30 s..50%) is an ambiguous signal and records NOTHING - the model
 * stays interpretable because every stored event has one clear meaning.
 */
class PlayerEventTracker(
    private val replayWindowMillis: Long = REPLAY_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /** A track restarted within 10 minutes of completing counts as a replay. */
        const val REPLAY_WINDOW_MILLIS = 10L * 60 * 1000

        /** Under 30 s of playback followed by leaving the track = early skip. */
        const val EARLY_SKIP_MILLIS = 30_000L

        /** At least half the track heard counts as a completion signal. */
        const val COMPLETION_RATIO = 0.5f
    }

    /** Identity (content uri) of the track currently open, if any. */
    var openTrackId: String? = null
        private set

    private var lastCompletedId: String? = null
    private var lastCompletedAt: Long = Long.MIN_VALUE

    /**
     * A track started playing. Always yields one event: REPLAYED when the
     * same track completed within the replay window, otherwise PLAYED.
     */
    fun onTrackStart(id: String, title: String, artist: String): MusicEvent {
        val now = clock()
        val replayed = lastCompletedId == id && now - lastCompletedAt <= replayWindowMillis
        if (replayed) lastCompletedId = null
        openTrackId = id
        return MusicEvent(
            type = if (replayed) MusicEventType.REPLAYED else MusicEventType.PLAYED,
            track = title,
            artist = artist,
            source = MusicSource.LOCAL_LIBRARY,
            atEpochMillis = now,
        )
    }

    /**
     * The open track ended (finished, skipped away, or replaced). Returns the
     * event to record, or null when the ending carries no honest signal
     * (unknown duration, or a mid-track skip between 30 s and 50%).
     */
    fun onTrackEnd(id: String, title: String, artist: String, positionMs: Long, durationMs: Long): MusicEvent? {
        if (openTrackId == id) openTrackId = null
        if (durationMs <= 0) return null
        val pos = positionMs.coerceIn(0L, durationMs)
        val now = clock()
        return when {
            pos < EARLY_SKIP_MILLIS -> MusicEvent(
                type = MusicEventType.SKIPPED_EARLY,
                track = title,
                artist = artist,
                source = MusicSource.LOCAL_LIBRARY,
                atEpochMillis = now,
            )
            pos.toFloat() / durationMs >= COMPLETION_RATIO -> {
                lastCompletedId = id
                lastCompletedAt = now
                MusicEvent(
                    type = MusicEventType.COMPLETED,
                    track = title,
                    artist = artist,
                    source = MusicSource.LOCAL_LIBRARY,
                    atEpochMillis = now,
                    completionRatio = pos.toFloat() / durationMs,
                )
            }
            else -> null
        }
    }

    /** The open track is abandoned without position information (service destroyed). */
    fun onOpenAbandoned() {
        openTrackId = null
    }
}
