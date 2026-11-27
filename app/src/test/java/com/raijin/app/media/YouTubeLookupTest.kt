package com.raijin.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YouTubeLookupTest {
    private val fixture: String =
        javaClass.classLoader!!.getResourceAsStream("youtube_search_fixture.json")!!
            .bufferedReader().readText()

    @Test fun parsesHitsInOrderSkippingNonVideoShelf() {
        val hits = YouTubeLookup.parseHits(fixture)
        assertEquals(7, hits.size)
        assertEquals("LONGMIX0001", hits[0].videoId)
        assertTrue(hits[1].videoId.isNotBlank() && hits[1].title.isNotBlank())
    }

    @Test fun songPickSkipsHourLongMix() {
        val hits = YouTubeLookup.parseHits(fixture)
        val song = YouTubeLookup.pick(hits, preferSong = true)
        assertNotNull(song)
        assertTrue(song!!.lengthSeconds in 1..720)
        assertEquals("LONGMIX0001", YouTubeLookup.pick(hits, preferSong = false)!!.videoId)
    }

    @Test fun lengthParsing() {
        assertEquals(268, YouTubeLookup.parseLength("4:28"))
        assertEquals(3723, YouTubeLookup.parseLength("1:02:03"))
        assertEquals(0, YouTubeLookup.parseLength(null))
        assertEquals(0, YouTubeLookup.parseLength("LIVE"))
    }

    @Test fun urlsAreWatchLinks() {
        val h = VideoHit("abc123", "t", 10)
        assertEquals("https://www.youtube.com/watch?v=abc123", h.watchUrl)
        assertEquals("https://music.youtube.com/watch?v=abc123", h.musicWatchUrl)
    }

    @Test fun garbageResponseGivesNoHits() {
        assertTrue(YouTubeLookup.parseHits("not json").isEmpty())
        assertTrue(YouTubeLookup.parseHits("{}").isEmpty())
    }

    @Test fun spokenNamesTitleAndChannel() {
        assertEquals("Playing Song, from Artist - Topic.", VideoHit("a", "Song", 200, "Artist - Topic").spoken)
        assertEquals("Playing Song.", VideoHit("a", "Song", 200).spoken)
    }

    @Test fun parsesChannelFromOwnerText() {
        val json = """{"x":{"videoRenderer":{"videoId":"abc","title":{"runs":[{"text":"Song"}]},""" +
            """"lengthText":{"simpleText":"3:20"},"ownerText":{"runs":[{"text":"Some Channel"}]}}}}"""
        val hit = YouTubeLookup.parseHits(json).single()
        assertEquals("Some Channel", hit.channel)
    }
}
