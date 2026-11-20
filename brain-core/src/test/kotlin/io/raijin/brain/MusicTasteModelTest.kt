package io.raijin.brain

import io.raijin.contract.DayPart
import io.raijin.contract.MusicEvent
import io.raijin.contract.MusicEventType
import io.raijin.contract.MusicSource
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MusicTasteModelTest {

    private val zone = TimeZone.getTimeZone("Asia/Calcutta")
    private val now = 1_800_000_000_000L

    private fun event(
        type: MusicEventType,
        artist: String = "Arijit Singh",
        track: String = "Kesariya",
        language: String? = "Hindi",
        genre: String? = "Romantic",
        ageMillis: Long = 0L,
        completion: Float? = null,
    ) = MusicEvent(
        type = type,
        track = track,
        artist = artist,
        language = language,
        genre = genre,
        source = MusicSource.LOCAL_LIBRARY,
        atEpochMillis = now - ageMillis,
        completionRatio = completion,
    )

    @Test
    fun `likes outrank plain plays`() {
        val events = listOf(
            event(MusicEventType.PLAYED, artist = "Artist A"),
            event(MusicEventType.PLAYED, artist = "Artist A"),
            event(MusicEventType.LIKED, artist = "Artist B"),
        )
        val top = MusicTasteModel.aggregate(events, now, zone, { it.artist })
        assertEquals("Artist B", top.first().name)
    }

    @Test
    fun `dislike ranks below fewer plays`() {
        val events = listOf(
            event(MusicEventType.DISLIKED, artist = "Bad"),
            event(MusicEventType.PLAYED, artist = "Good"),
        )
        val top = MusicTasteModel.aggregate(events, now, zone, { it.artist })
        assertEquals("Good", top.first().name)
        assertTrue(top.last().score < 0)
    }

    @Test
    fun `completed weight scales with completion ratio`() {
        val half = MusicTasteModel.aggregate(
            listOf(event(MusicEventType.COMPLETED, completion = 0.5f)), now, zone, { it.artist },
        ).first().score
        val full = MusicTasteModel.aggregate(
            listOf(event(MusicEventType.COMPLETED, completion = 1.0f)), now, zone, { it.artist },
        ).first().score
        assertEquals(full / 2, half, 1e-9)
    }

    @Test
    fun `decay halves score after one half life`() {
        val fresh = MusicTasteModel.aggregate(
            listOf(event(MusicEventType.PLAYED, ageMillis = 0)), now, zone, { it.artist },
        ).first().score
        val old = MusicTasteModel.aggregate(
            listOf(event(MusicEventType.PLAYED, ageMillis = MusicTasteModel.HALF_LIFE_MILLIS)),
            now, zone, { it.artist },
        ).first().score
        assertEquals(fresh / 2, old, 1e-9)
    }

    @Test
    fun `aggregation keys languages and skips blanks`() {
        val events = listOf(
            event(MusicEventType.PLAYED, language = "Hindi"),
            event(MusicEventType.PLAYED, language = "Punjabi"),
            event(MusicEventType.PLAYED, language = null),
            event(MusicEventType.PLAYED, language = "  "),
        )
        val top = MusicTasteModel.aggregate(events, now, zone, { it.language })
        assertEquals(2, top.size)
        assertEquals(setOf("Hindi", "Punjabi"), top.map { it.name }.toSet())
    }

    @Test
    fun `dominant day part and weekend share are computed`() {
        // 2027-01-17 is a Sunday; pick hours inside EVENING in IST.
        val cal = java.util.Calendar.getInstance(zone).apply {
            set(2027, 0, 17, 20, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val sundayEvening = cal.timeInMillis
        val events = (1..3).map {
            event(MusicEventType.PLAYED, ageMillis = now - sundayEvening)
        }
        val signal = MusicTasteModel.aggregate(events, now, zone, { it.artist }).first()
        assertEquals(DayPart.EVENING, signal.dominantDayPart)
        assertEquals(1.0f, signal.weekendShare, 1e-6f)
        assertEquals(3, signal.plays)
    }

    @Test
    fun `explain renders interpretable evidence`() {
        val events = listOf(
            event(MusicEventType.PLAYED),
            event(MusicEventType.LIKED),
            event(MusicEventType.SKIPPED_EARLY),
        )
        val signal = MusicTasteModel.aggregate(events, now, zone, { it.artist }).first()
        val why = MusicTasteModel.explain(signal)
        assertTrue(why.contains("played 1 time"))
        assertTrue(why.contains("liked 1 time"))
        assertTrue(why.contains("skipped early 1 time"))
    }

    @Test
    fun `completed events require a completion ratio`() {
        assertFailsWith<IllegalArgumentException> {
            MusicEvent(
                type = MusicEventType.COMPLETED,
                track = "t",
                artist = "a",
                atEpochMillis = now,
            )
        }
    }

    @Test
    fun `limit caps the ranking`() {
        val events = (1..5).flatMap { i ->
            listOf(event(MusicEventType.PLAYED, artist = "A$i"), event(MusicEventType.PLAYED, artist = "A$i"))
        }
        val top = MusicTasteModel.aggregate(events, now, zone, { it.artist }, limit = 3)
        assertEquals(3, top.size)
    }
}
