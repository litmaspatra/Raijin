package com.raijin.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * MU-1: Raijin's own audio player for the on-device library (Tier 1).
 *
 * A [MediaSessionService], so while anything plays, the system posts the
 * media notification with transport controls and exposes the session to
 * lock-screen / bluetooth / assistant controllers for free. [MusicCommander]
 * in the actuator reaches this player through that session like any other.
 *
 * Start with [playTrack]; the service foregrounds itself when playback
 * starts and stops itself when the queue ends and nothing is playing.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RaijinPlayerService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    // MU-6: taste-event capture from real playback (see PlayerEventTracker).
    private val eventTracker = PlayerEventTracker()
    private val tasteStore by lazy { MusicTasteStore(java.io.File(filesDir, "music_taste.json")) }
    private val eventScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    private var openTrackId: String? = null
    private var openTitle: String? = null
    private var openArtist: String? = null
    private var openDurationMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        val p = ExoPlayer.Builder(this).build().apply {
            // Pause for calls/other audio, resume when the focus comes back.
            setHandleAudioBecomingNoisy(true)
        }
        p.addListener(object : Player.Listener {
            // A track change (skip, replace, auto-advance) discontinues the
            // old item: its end position is the honest "how much was heard".
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                val id = openTrackId ?: return
                // Attribute only when the discontinuity really closed the open
                // track; if the transition arrived first, skip honestly.
                val oldUri = oldPosition.mediaItem?.localConfiguration?.uri?.toString()
                if (oldUri != null && oldUri != id) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SKIP ||
                    reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
                    reason == Player.DISCONTINUITY_REASON_REMOVE
                ) {
                    recordEnd(id, oldPosition.positionMs)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri?.toString() ?: return
                // If a transition replaces the open track without a preceding
                // discontinuity, the old ending has no honest position - drop it.
                if (openTrackId != null && openTrackId != uri) {
                    eventTracker.onOpenAbandoned()
                    openTrackId = null
                    openDurationMs = 0L
                }
                openTrackId = uri
                openTitle = mediaItem?.mediaMetadata?.title?.toString() ?: uri
                openArtist = mediaItem?.mediaMetadata?.artist?.toString() ?: ""
                openDurationMs = 0L
                recordEvent(eventTracker.onTrackStart(uri, openTitle ?: uri, openArtist ?: ""))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        val d = p.duration
                        if (d > 0) openDurationMs = d
                    }
                    Player.STATE_ENDED -> {
                        val id = openTrackId ?: return
                        // Natural completion: the whole track was heard.
                        recordEnd(id, openDurationMs)
                    }
                }
            }
        })
        player = p
        mediaSession = MediaSession.Builder(this, p)
            .setCallback(object : MediaSession.Callback {
                // Per-controller access policy: trusted system controllers keep
                // full access; arbitrary apps get transport-only commands so
                // they cannot read the queue/timeline (track metadata and local
                // URIs) or rewrite the queue. See SessionTrustPolicy.
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult =
                    SessionTrustPolicy.connectionResultFor(session, controller.isTrusted)
            })
            .build()
    }

    private fun recordEnd(id: String, positionMs: Long) {
        // Close the open track even when no event results, so a later
        // STATE_ENDED for the same ending never double-records.
        val title = openTitle ?: id
        val artist = openArtist ?: ""
        val duration = openDurationMs
        openTrackId = null
        openDurationMs = 0L
        val event = eventTracker.onTrackEnd(id, title, artist, positionMs, duration) ?: return
        recordEvent(event)
    }

    private fun recordEvent(event: io.raijin.contract.MusicEvent) {
        eventScope.launch {
            runCatching { tasteStore.recordMusicEvent(event) }
                .onFailure { Log.w(TAG, "taste event not recorded: ${event.type}") }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY_TRACK) {
            val uri = intent.getStringExtra(EXTRA_URI)
            if (uri.isNullOrBlank()) {
                Log.w(TAG, "ACTION_PLAY_TRACK without a uri; ignoring")
            } else {
                playNow(uri, intent.getStringExtra(EXTRA_TITLE), intent.getStringExtra(EXTRA_ARTIST))
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun playNow(uri: String, title: String?, artist: String?) {
        val p = player ?: return
        val item = MediaItem.Builder()
            .setUri(Uri.parse(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
        p.setMediaItem(item)
        p.prepare()
        p.play()
        Log.i(TAG, "Playing local track: $title")
    }

    override fun onDestroy() {
        eventTracker.onOpenAbandoned()
        eventScope.cancel()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY_TRACK = "com.raijin.app.media.action.PLAY_TRACK"
        const val EXTRA_URI = "com.raijin.app.media.extra.URI"
        const val EXTRA_TITLE = "com.raijin.app.media.extra.TITLE"
        const val EXTRA_ARTIST = "com.raijin.app.media.extra.ARTIST"
        private const val TAG = "RaijinPlayerService"

        /** Ask the player service to start a local library track. */
        fun playTrack(context: Context, track: LibraryTrack) {
            val intent = Intent(context, RaijinPlayerService::class.java).apply {
                action = ACTION_PLAY_TRACK
                putExtra(EXTRA_URI, track.contentUri)
                putExtra(EXTRA_TITLE, track.title)
                putExtra(EXTRA_ARTIST, track.artist)
            }
            context.startForegroundService(intent)
        }
    }
}
