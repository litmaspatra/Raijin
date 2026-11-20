package com.raijin.app.media

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

/**
 * Per-controller access policy for the media session.
 *
 * Stock Media3 accepts every connecting controller with the full player
 * command set (MediaSession.Callback.onConnect default returns
 * `AcceptedResultBuilder(session).build()`). That lets ANY installed app read
 * the queue/timeline - track titles, artists and the local media URIs behind
 * them - and rewrite the queue. Broader than the transport control that lock
 * screen, Bluetooth, Wear and Android Auto actually need, so untrusted
 * controllers get a transport-only command set.
 *
 * Trust is `ControllerInfo.isTrusted` ONLY. That flag is framework-verified
 * (MediaSessionManager.isTrustedForMediaControl: system uid, signature-level
 * MEDIA_CONTENT_CONTROL, status-bar service, or an enabled notification
 * listener). The isAutomotiveController / isAutoCompanionController helpers
 * are documented by Media3 as "not a security validation" and are deliberately
 * NOT used to widen trust.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object SessionTrustPolicy {

    /**
     * Commands an untrusted controller may use: transport only. No timeline,
     * metadata or current-item reads (queue content and local URIs), and no
     * queue writes (SET_MEDIA_ITEM / CHANGE_MEDIA_ITEMS).
     */
    val TRANSPORT_ONLY_COMMANDS: Player.Commands = Player.Commands.Builder()
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_PREPARE)
        .add(Player.COMMAND_STOP)
        .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
        .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
        .add(Player.COMMAND_SEEK_TO_NEXT)
        .add(Player.COMMAND_SEEK_BACK)
        .add(Player.COMMAND_SEEK_FORWARD)
        .build()

    /**
     * Builds the connection result for a controller: the Media3 default
     * (full access) when [trusted], transport-only otherwise.
     */
    fun connectionResultFor(session: MediaSession, trusted: Boolean): MediaSession.ConnectionResult {
        val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
        if (!trusted) {
            builder.setAvailablePlayerCommands(TRANSPORT_ONLY_COMMANDS)
        }
        return builder.build()
    }
}
