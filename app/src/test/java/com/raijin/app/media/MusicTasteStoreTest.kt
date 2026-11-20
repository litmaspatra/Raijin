package com.raijin.app.media

import io.raijin.contract.MusicEvent
import io.raijin.contract.MusicEventType
import io.raijin.contract.MusicSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.TimeZone

class MusicTasteStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = TimeZone.getTimeZone("Asia/Calcutta")
    private val now = System.currentTimeMillis()

    private fun event(
        type: MusicEventType,
        artist: String = "Arijit Singh",
        track: String = "Kesariya",
        language: String? = "Hindi",
        at: Long = now,
        completion: Float? = null,
    ) = MusicEvent(
        type = type,
        track = track,
        artist = artist,
        language = language,
        source = MusicSource.LOCAL_LIBRARY,
        atEpochMillis = at,
        completionRatio = completion,
    )

    private fun store(maxEvents: Int = MusicTasteStore.MAX_EVENTS) =
        MusicTasteStore(tmp.newFile("taste.json"), zone, maxEvents)

    @Test
    fun `recorded events persist across store instances`() = runBlocking {
        val file = tmp.newFile("persist.json")
        val first = MusicTasteStore(file, zone)
        first.recordMusicEvent(event(MusicEventType.LIKED))
        first.recordMusicEvent(event(MusicEventType.COMPLETED, completion = 0.9f))

        val second = MusicTasteStore(file, zone)
        val recent = second.recentEvents()
        assertEquals(2, recent.size)
        assertEquals(MusicEventType.LIKED, recent[0].type)
        assertEquals(0.9f, recent[1].completionRatio!!, 1e-4f)
        assertEquals("Hindi", recent[1].language)
    }

    @Test
    fun `ring keeps only the newest events`() = runBlocking {
        val s = store(maxEvents = 3)
        repeat(5) { i -> s.recordMusicEvent(event(MusicEventType.PLAYED, track = "t$i")) }
        val recent = s.recentEvents(10)
        assertEquals(3, recent.size)
        assertEquals("t2", recent.first().track)
        assertEquals("t4", recent.last().track)
    }

    @Test
    fun `top artists aggregate through the model`() = runBlocking {
        val s = store()
        s.recordMusicEvent(event(MusicEventType.PLAYED, artist = "Jubin Nautiyal"))
        s.recordMusicEvent(event(MusicEventType.LIKED, artist = "Arijit Singh"))
        s.recordMusicEvent(event(MusicEventType.LIKED, artist = "Arijit Singh"))
        val top = s.topArtists(nowEpochMillis = now)
        assertEquals("Arijit Singh", top.first().name)
        assertEquals(2, top.first().likes)
    }

    @Test
    fun `missing language and genre are excluded from those rankings`() = runBlocking {
        val s = store()
        s.recordMusicEvent(event(MusicEventType.PLAYED, language = null))
        assertTrue(s.topLanguages(nowEpochMillis = now).isEmpty())
        assertTrue(s.topGenres(nowEpochMillis = now).isEmpty())
        assertEquals(1, s.topArtists(nowEpochMillis = now).size)
    }

    @Test
    fun `corrupt log file starts empty instead of crashing`() = runBlocking {
        val file = tmp.newFile("corrupt.json")
        file.writeText("{ not json !!!")
        val s = MusicTasteStore(file, zone)
        assertTrue(s.recentEvents().isEmpty())
        // And it recovers: new events record fine afterwards.
        s.recordMusicEvent(event(MusicEventType.PLAYED))
        assertEquals(1, s.recentEvents().size)
    }
}
