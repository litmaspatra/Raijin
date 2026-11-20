package com.raijin.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionLogicTest {

    // PlaybackState constants: PLAYING=3, PAUSED=2, BUFFERING=6, NONE=0, STOPPED=1
    private val playing = 3
    private val paused = 2
    private val buffering = 6
    private val stopped = 1
    private val none = 0

    @Test
    fun `playing session outranks paused and stopped`() {
        assertEquals(1, MediaSessionLogic.pickIndex(listOf(paused, playing, stopped)))
    }

    @Test
    fun `paused session picked when nothing is playing`() {
        assertEquals(0, MediaSessionLogic.pickIndex(listOf(paused, stopped)))
    }

    @Test
    fun `buffering counts as resumable`() {
        assertEquals(1, MediaSessionLogic.pickIndex(listOf(stopped, buffering)))
    }

    @Test
    fun `first playing wins among several`() {
        assertEquals(0, MediaSessionLogic.pickIndex(listOf(playing, playing)))
    }

    @Test
    fun `no live session returns null`() {
        assertNull(MediaSessionLogic.pickIndex(listOf(stopped, none)))
        assertNull(MediaSessionLogic.pickIndex(emptyList()))
    }

    @Test
    fun `now playing formats title and artist`() {
        assertEquals(
            "Now playing Kesariya by Arijit Singh.",
            MediaSessionLogic.formatNowPlaying("Kesariya", "Arijit Singh"),
        )
        assertEquals("Now playing Kesariya.", MediaSessionLogic.formatNowPlaying("Kesariya", null))
        assertEquals("Now playing Kesariya.", MediaSessionLogic.formatNowPlaying(" Kesariya ", "  "))
        assertEquals(
            "I can see an active player, but it isn't reporting track info.",
            MediaSessionLogic.formatNowPlaying(null, null),
        )
    }
    @Test
    fun `MU-8 like and more-like-this map to LIKED, dislike to DISLIKED`() {
        assertEquals(
            io.raijin.contract.MusicEventType.LIKED,
            MediaSessionLogic.feedbackEventType(io.raijin.contract.MusicFeedback.LIKED),
        )
        assertEquals(
            io.raijin.contract.MusicEventType.LIKED,
            MediaSessionLogic.feedbackEventType(io.raijin.contract.MusicFeedback.MORE_LIKE_THIS),
        )
        assertEquals(
            io.raijin.contract.MusicEventType.DISLIKED,
            MediaSessionLogic.feedbackEventType(io.raijin.contract.MusicFeedback.DISLIKED),
        )
    }

    @Test
    fun `MU-8 feedback message names the tagged track`() {
        val msg = MediaSessionLogic.feedbackMessage(
            io.raijin.contract.MusicFeedback.LIKED, "Kesariya", "Arijit Singh",
        )
        assertTrue(msg.contains("Kesariya by Arijit Singh"))
        val noArtist = MediaSessionLogic.feedbackMessage(
            io.raijin.contract.MusicFeedback.DISLIKED, "Kesariya", "",
        )
        assertTrue(noArtist.contains("Kesariya") && !noArtist.contains(" by "))
    }
}
