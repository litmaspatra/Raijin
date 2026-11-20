package io.raijin.brain

import io.raijin.contract.MusicEvent
import io.raijin.contract.MusicEventType
import io.raijin.contract.MusicSource
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicTasteSamplerTest {

    private val zone = TimeZone.getTimeZone("Asia/Calcutta")
    private val now = 1_800_000_000_000L

    private fun candidates(n: Int) = (1..n).map {
        MusicTasteSampler.Candidate("uri/$it", "Track$it", "Artist$it")
    }

    private fun event(
        type: MusicEventType,
        track: String,
        artist: String,
        at: Long = now,
        completion: Float? = null,
    ) = MusicEvent(
        type = type,
        track = track,
        artist = artist,
        source = MusicSource.LOCAL_LIBRARY,
        atEpochMillis = at,
        completionRatio = completion,
    )

    @Test
    fun `empty library yields no pick`() {
        assertNull(MusicTasteSampler.pick(emptyList(), emptyList(), now, zone))
    }

    @Test
    fun `no history still picks something`() {
        val pick = MusicTasteSampler.pick(candidates(5), emptyList(), now, zone, random = Random(7))
        assertNotNull(pick)
    }

    @Test
    fun `the last 20 recent tracks are avoided`() {
        val candidates = candidates(21)
        val events = (1..20).map { event(MusicEventType.PLAYED, "Track$it", "Artist$it", at = now - it * 1000L) }
        val pick = MusicTasteSampler.pick(candidates, events, now, zone, random = Random(1))
        assertEquals("Track21", pick!!.title)
    }

    @Test
    fun `avoiding everything falls back to the full library instead of silence`() {
        val candidates = candidates(3)
        val events = (1..3).map { event(MusicEventType.PLAYED, "Track$it", "Artist$it", at = now - it * 1000L) }
        val pick = MusicTasteSampler.pick(candidates, events, now, zone, random = Random(1))
        assertNotNull(pick)
    }

    @Test
    fun `a liked artist dominates at low temperature`() {
        val candidates = listOf(
            MusicTasteSampler.Candidate("uri/a", "Song A", "Loved"),
            MusicTasteSampler.Candidate("uri/b", "Song B", "Meh"),
        )
        val events = listOf(
            event(MusicEventType.LIKED, "Song A", "Loved"),
            event(MusicEventType.LIKED, "Song A", "Loved"),
            event(MusicEventType.SKIPPED_EARLY, "Song B", "Meh"),
        )
        val pick = MusicTasteSampler.pick(
            candidates, events, now, zone, temperature = 0.1, avoidRecent = 0, random = Random(3),
        )
        assertEquals("Loved", pick!!.artist)
    }

    @Test
    fun `artist dominant in the current day part wins at low temperature`() {
        val candidates = listOf(
            MusicTasteSampler.Candidate("uri/m", "Morning Song", "Morning Artist"),
            MusicTasteSampler.Candidate("uri/e", "Evening Song", "Evening Artist"),
        )
        // Equal play counts, comparable ages (two days ago, 08:00 vs 20:00
        // IST): the artist dominant in the current evening bucket must win.
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = now - 2L * 24 * 3600 * 1000
        cal.set(Calendar.HOUR_OF_DAY, 8)
        val morning = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 20)
        val evening = cal.timeInMillis
        val events = listOf(
            event(MusicEventType.PLAYED, "Morning Song", "Morning Artist", at = morning),
            event(MusicEventType.PLAYED, "Evening Song", "Evening Artist", at = evening),
        )
        // Evaluate "now" inside the evening bucket.
        val nowEvening = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 20)
        }.timeInMillis
        val pick = MusicTasteSampler.pick(
            candidates, events, nowEvening, zone, temperature = 0.1, avoidRecent = 0, random = Random(5),
        )
        assertEquals("Evening Artist", pick!!.artist)
    }

    @Test
    fun `sampling is deterministic for a fixed seed`() {
        val events = listOf(event(MusicEventType.LIKED, "Track1", "Artist1"))
        val a = MusicTasteSampler.pick(candidates(10), events, now, zone, avoidRecent = 0, random = Random(42))
        val b = MusicTasteSampler.pick(candidates(10), events, now, zone, avoidRecent = 0, random = Random(42))
        assertEquals(a, b)
    }

    @Test
    fun `temperature must be positive`() {
        try {
            MusicTasteSampler.pick(candidates(2), emptyList(), now, zone, temperature = 0.0)
            assertTrue(false, "expected require() to throw")
        } catch (_: IllegalArgumentException) {
        }
    }
}
