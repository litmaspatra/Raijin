package com.raijin.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLibraryIndexTest {

    private val tracks = listOf(
        LibraryTrack(1, "Kesariya", "Arijit Singh", "Brahmastra", 268000, "content://media/1"),
        LibraryTrack(2, "Tum Hi Ho", "Arijit Singh", "Aashiqui 2", 262000, "content://media/2"),
        LibraryTrack(3, "Night Changes", "One Direction", "Four", 227000, "content://media/3"),
        LibraryTrack(4, "Apna Bana Le", "Arijit Singh", "Bhediya", 235000, "content://media/4"),
        LibraryTrack(5, "Raabta", "Arijit Singh", "Agent Vinod", 244000, "content://media/5"),
        LibraryTrack(6, "ओ बेदरदेया", "Arijit Singh", "Tu Jhoothi", 312000, "content://media/6"),
        LibraryTrack(7, "Bedardi Se Pyaar Ka", "Jubin Nautiyal", "Single", 200000, "content://media/7"),
    )
    private val index = MusicLibraryIndex(tracks)

    @Test
    fun `exact artist query ranks that artist's tracks on top`() {
        val results = index.search("arijit singh")
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.track.artist == "Arijit Singh" })
        assertEquals(100, results.first().score)
    }

    @Test
    fun `exact title beats substring hits`() {
        val results = index.search("raabta")
        assertEquals("Raabta", results.first().track.title)
        assertEquals(90, results.first().score)
    }

    @Test
    fun `devanagari title query matches via normalizer`() {
        // The stored title is Devanagari; a Latin transcript should still hit it.
        val results = index.search("o bedardaya")
        assertTrue(results.any { it.track.id == 6L })
        // Skeleton matches are a safety net only - they must not auto-play.
        assertTrue(results.first { it.track.id == 6L }.score < MusicLibraryIndex.AUTO_PLAY_THRESHOLD)
    }

    @Test
    fun `bestMatch clears threshold for artist and prefix queries`() {
        assertEquals("Arijit Singh", index.bestMatch("arijit")!!.track.artist)
        assertNull(index.bestMatch("changes")) // substring title hit (50) stays below the bar
    }

    @Test
    fun `blank and unmatched queries return empty`() {
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
        assertTrue(index.search("zzzz nothing like this").isEmpty())
        assertNull(index.bestMatch(""))
    }

    @Test
    fun `multiword artist token match outranks substring title`() {
        val results = index.search("jubin nautiyal")
        assertEquals("Jubin Nautiyal", results.first().track.artist)
        assertTrue(results.first().score >= 65)
    }

    @Test
    fun `album substring matches rank below artist and title`() {
        val results = index.search("bhediya")
        assertEquals(1, results.size)
        assertEquals(30, results.first().score)
        assertNull(index.bestMatch("bhediya"))
    }

    @Test
    fun `limit is respected`() {
        assertEquals(2, index.search("arijit", limit = 2).size)
    }
}
