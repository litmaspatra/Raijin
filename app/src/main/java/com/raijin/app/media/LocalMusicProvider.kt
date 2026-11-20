package com.raijin.app.media

import android.content.Context
import android.util.Log

/**
 * MU-1: cached access to the owner's local library index. The index is
 * built on first use and refreshed when stale; scanning happens off the
 * caller's thread (the actuator path is already on a background dispatcher).
 * An empty index is a valid state (no permission, no local music) and means
 * "fall through to Tier 2", never an error.
 */
object LocalMusicProvider {

    private const val TAG = "LocalMusicProvider"
    private const val STALE_AFTER_MS = 10 * 60 * 1000L

    @Volatile
    private var cached: Pair<Long, MusicLibraryIndex>? = null

    suspend fun index(context: Context, scanner: MusicLibraryScanner = MediaStoreMusicScanner(context.applicationContext)): MusicLibraryIndex {
        val now = System.currentTimeMillis()
        cached?.let { (builtAt, idx) ->
            if (now - builtAt < STALE_AFTER_MS) return idx
        }
        val tracks = scanner.scan()
        val idx = MusicLibraryIndex(tracks)
        cached = now to idx
        Log.i(TAG, "Local library index rebuilt: ${idx.size} tracks")
        return idx
    }

    /** Drop the cache (tests, or when media storage changes). */
    fun invalidate() {
        cached = null
    }
}
