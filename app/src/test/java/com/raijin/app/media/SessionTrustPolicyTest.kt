package com.raijin.app.media

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the media-session disclosure fix: stock Media3 hands
 * every connecting controller the full player command set, which exposes the
 * queue/timeline (track titles, artists, local media URIs) to any installed
 * app and lets it rewrite the queue. SessionTrustPolicy must restrict
 * untrusted controllers to transport-only commands while trusted system
 * controllers (lock screen, BT, Wear, Auto via system mediation) keep the
 * full default set.
 *
 * JVM-level verification only; on-device controller behavior is unverified.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTrustPolicyTest {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        player = ExoPlayer.Builder(context).build()
        session = MediaSession.Builder(context, player!!).build()
    }

    @After
    fun tearDown() {
        session?.release()
        player?.release()
    }

    @Test
    fun untrustedControllerGetsTransportOnlyNoQueueReadOrWrite() {
        val result = SessionTrustPolicy.connectionResultFor(session!!, trusted = false)
        val commands = result.availablePlayerCommands

        // Transport retained.
        assertTrue(commands.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))

        // Queue/metadata reads blocked (titles, artists, local URIs).
        assertFalse(commands.contains(Player.COMMAND_GET_TIMELINE))
        assertFalse(commands.contains(Player.COMMAND_GET_METADATA))
        assertFalse(commands.contains(Player.COMMAND_GET_CURRENT_MEDIA_ITEM))

        // Queue writes blocked.
        assertFalse(commands.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
    }

    @Test
    fun trustedControllerKeepsFullDefaultAccess() {
        val result = SessionTrustPolicy.connectionResultFor(session!!, trusted = true)
        val commands = result.availablePlayerCommands

        assertTrue(commands.contains(Player.COMMAND_GET_TIMELINE))
        assertTrue(commands.contains(Player.COMMAND_GET_METADATA))
        assertTrue(commands.contains(Player.COMMAND_GET_CURRENT_MEDIA_ITEM))
        assertTrue(commands.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertTrue(commands.contains(Player.COMMAND_PLAY_PAUSE))
    }
}
