package com.raijin.app.media

import io.raijin.contract.MusicEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerEventTrackerTest {

    private var now = 1_000_000L
    private val tracker = PlayerEventTracker(clock = { now })

    @Test
    fun `first start of a track records PLAYED`() {
        val e = tracker.onTrackStart("uri/1", "Kesariya", "Arijit Singh")
        assertEquals(MusicEventType.PLAYED, e.type)
        assertEquals("uri/1", tracker.openTrackId)
    }

    @Test
    fun `restart within the replay window after completion records REPLAYED`() {
        tracker.onTrackStart("uri/1", "Kesariya", "Arijit Singh")
        tracker.onTrackEnd("uri/1", "Kesariya", "Arijit Singh", 200_000, 200_000)
        now += 5 * 60_000 // five minutes later
        val e = tracker.onTrackStart("uri/1", "Kesariya", "Arijit Singh")
        assertEquals(MusicEventType.REPLAYED, e.type)
    }

    @Test
    fun `restart after the replay window records PLAYED again`() {
        tracker.onTrackStart("uri/1", "Kesariya", "Arijit Singh")
        tracker.onTrackEnd("uri/1", "Kesariya", "Arijit Singh", 200_000, 200_000)
        now += PlayerEventTracker.REPLAY_WINDOW_MILLIS + 1
        val e = tracker.onTrackStart("uri/1", "Kesariya", "Arijit Singh")
        assertEquals(MusicEventType.PLAYED, e.type)
    }

    @Test
    fun `leaving a track under 30 seconds records SKIPPED_EARLY`() {
        tracker.onTrackStart("uri/1", "Kesariya", "Arijit Singh")
        val e = tracker.onTrackEnd("uri/1", "Kesariya", "Arijit Singh", 12_000, 200_000)
        assertEquals(MusicEventType.SKIPPED_EARLY, e!!.type)
        assertNull(tracker.openTrackId)
    }

    @Test
    fun `hearing at least half records COMPLETED with the real ratio`() {
        tracker.onTrackStart("uri/1", "Kesariya", "Arijit Singh")
        val e = tracker.onTrackEnd("uri/1", "Kesariya", "Arijit Singh", 150_000, 200_000)
        assertEquals(MusicEventType.COMPLETED, e!!.type)
        assertEquals(0.75f, e.completionRatio!!, 1e-4f)
    }

    @Test
    fun `a mid-track skip records nothing`() {
        tracker.onTrackStart("uri/1", "Kesariya", "Arijit Singh")
        val e = tracker.onTrackEnd("uri/1", "Kesariya", "Arijit Singh", 80_000, 200_000)
        assertNull(e)
    }

    @Test
    fun `unknown duration records nothing and never crashes`() {
        tracker.onTrackStart("uri/1", "Kesariya", "Arijit Singh")
        assertNull(tracker.onTrackEnd("uri/1", "Kesariya", "Arijit Singh", 60_000, 0))
        assertNull(tracker.onTrackEnd("uri/1", "Kesariya", "Arijit Singh", 60_000, -1))
    }

    @Test
    fun `a replay is consumed once`() {
        tracker.onTrackStart("uri/1", "Kesariya", "Arijit Singh")
        tracker.onTrackEnd("uri/1", "Kesariya", "Arijit Singh", 200_000, 200_000)
        now += 60_000
        assertEquals(MusicEventType.REPLAYED, tracker.onTrackStart("uri/1", "Kesariya", "Arijit Singh").type)
        tracker.onTrackEnd("uri/1", "Kesariya", "Arijit Singh", 200_000, 200_000)
        now += 60_000
        assertEquals(MusicEventType.REPLAYED, tracker.onTrackStart("uri/1", "Kesariya", "Arijit Singh").type)
    }

    @Test
    fun `events carry the local-library source and clock time`() {
        val e = tracker.onTrackStart("uri/9", "Raabta", "Arijit Singh")
        assertEquals(io.raijin.contract.MusicSource.LOCAL_LIBRARY, e.source)
        assertEquals(now, e.atEpochMillis)
        assertTrue(e.completionRatio == null)
    }
}
