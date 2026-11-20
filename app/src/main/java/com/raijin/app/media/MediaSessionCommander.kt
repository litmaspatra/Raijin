package com.raijin.app.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import io.raijin.contract.ActionResult

/**
 * MU-2: control the currently active media player through its published
 * [MediaController] session instead of blind media-key dispatches, and read
 * now-playing metadata back for "ye gaana kya hai".
 *
 * Listing active sessions requires Raijin's notification listener to be
 * enabled; when it is not, every operation fails honestly with guidance
 * instead of pretending to work.
 */
object MediaSessionLogic {
    const val STATE_UNKNOWN = -1

    /** Lower is better: actively playing first, then resumable, then the rest. */
    fun rank(playbackState: Int): Int = when (playbackState) {
        PlaybackState.STATE_PLAYING -> 0
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        -> 1
        else -> 2
    }

    /** Index of the best controllable session, or null when none is live. */
    fun pickIndex(playbackStates: List<Int>): Int? {
        if (playbackStates.isEmpty()) return null
        var bestIdx = -1
        var bestRank = Int.MAX_VALUE
        playbackStates.forEachIndexed { idx, state ->
            val r = rank(state)
            if (r < bestRank) {
                bestRank = r
                bestIdx = idx
            }
        }
        return bestIdx.takeIf { bestRank <= 1 }
    }

    /** MU-8: which taste event a feedback phrase records ("aur aisa" counts as a like). */
    fun feedbackEventType(sentiment: io.raijin.contract.MusicFeedback): io.raijin.contract.MusicEventType =
        when (sentiment) {
            io.raijin.contract.MusicFeedback.LIKED,
            io.raijin.contract.MusicFeedback.MORE_LIKE_THIS,
            -> io.raijin.contract.MusicEventType.LIKED

            io.raijin.contract.MusicFeedback.DISLIKED -> io.raijin.contract.MusicEventType.DISLIKED
        }

    /** MU-8: spoken confirmation naming the tagged track honestly. */
    fun feedbackMessage(sentiment: io.raijin.contract.MusicFeedback, title: String, artist: String): String {
        val name = if (artist.isBlank()) title else "$title by $artist"
        return when (sentiment) {
            io.raijin.contract.MusicFeedback.LIKED -> "Noted - I'll play more like $name."
            io.raijin.contract.MusicFeedback.MORE_LIKE_THIS -> "Noted - more songs like $name."
            io.raijin.contract.MusicFeedback.DISLIKED -> "Noted - I'll avoid $name."
        }
    }

    fun formatNowPlaying(title: String?, artist: String?): String {
        val t = title?.trim().orEmpty()
        val a = artist?.trim().orEmpty()
        return when {
            t.isNotEmpty() && a.isNotEmpty() -> "Now playing $t by $a."
            t.isNotEmpty() -> "Now playing $t."
            else -> "I can see an active player, but it isn't reporting track info."
        }
    }
}

class MediaSessionCommander(private val context: Context) {

    class ListenerUnavailableException : Exception("notification listener not enabled")

    private fun activeControllers(): List<MediaController> {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(context, com.raijin.app.bridge.NotificationListenerService::class.java)
        return try {
            manager.getActiveSessions(component)
        } catch (se: SecurityException) {
            throw ListenerUnavailableException()
        }
    }

    private fun pickActive(): MediaController? {
        val controllers = activeControllers()
        val idx = MediaSessionLogic.pickIndex(
            controllers.map { it.playbackState?.state ?: MediaSessionLogic.STATE_UNKNOWN },
        ) ?: return null
        return controllers[idx]
    }

    /**
     * Route a transport command through the active player's session.
     * Returns true when a live session accepted it; false means the caller
     * should fall back to media-key dispatch.
     */
    fun transport(command: io.raijin.contract.MusicCommand): Boolean {
        val controller = try {
            pickActive()
        } catch (e: ListenerUnavailableException) {
            return false
        } ?: return false
        val controls = controller.transportControls
        when (command) {
            io.raijin.contract.MusicCommand.PLAY,
            io.raijin.contract.MusicCommand.RESUME,
            -> controls.play()
            io.raijin.contract.MusicCommand.PAUSE -> controls.pause()
            io.raijin.contract.MusicCommand.NEXT -> controls.skipToNext()
            io.raijin.contract.MusicCommand.PREVIOUS -> controls.skipToPrevious()
            else -> return false
        }
        return true
    }

    /** MU-8: raw (title, artist) of the active session, or null when nothing is visible. */
    fun currentTrack(): Pair<String, String>? {
        val controller = pickActive() ?: return null
        val metadata: MediaMetadata? = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        return title to artist
    }

    fun nowPlaying(): ActionResult {
        val controller = try {
            pickActive()
        } catch (e: ListenerUnavailableException) {
            return ActionResult.FAILED(
                "I can't see what's playing until notification access is on for Raijin - it is how I read the active player.",
            )
        } ?: return ActionResult.FAILED("I don't see anything playing right now.")
        val metadata: MediaMetadata? = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        return ActionResult.EXECUTED(MediaSessionLogic.formatNowPlaying(title, artist))
    }
}
