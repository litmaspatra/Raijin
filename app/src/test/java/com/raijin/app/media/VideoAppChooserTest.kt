package com.raijin.app.media

import com.raijin.app.media.VideoAppChooser.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoAppChooserTest {
    private val yt = Candidate(VideoAppChooser.YOUTUBE, "yt.Watch")
    private val music = Candidate(VideoAppChooser.YOUTUBE_MUSIC, "ytm.Watch")
    private val browser = Candidate("com.android.chrome", "chrome.Main")

    @Test fun youtubeWinsEvenWhenMusicIsListedFirst() {
        assertEquals(yt, VideoAppChooser.pick(listOf(music, yt, browser)))
    }

    @Test fun musicIsNeverChosenForVideo() {
        assertNull(VideoAppChooser.pick(listOf(music)))
        assertEquals(browser, VideoAppChooser.pick(listOf(music, browser)))
    }

    @Test fun noHandlersGivesNull() {
        assertNull(VideoAppChooser.pick(emptyList()))
    }
}
