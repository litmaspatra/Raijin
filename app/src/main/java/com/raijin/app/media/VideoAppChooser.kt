package com.raijin.app.media

/**
 * Picks which installed app opens a video watch link. Video requests must open the
 * regular YouTube app, never YouTube Music, even when YouTube Music has claimed the
 * youtube.com watch links on the phone. Pure logic so it is unit-tested.
 */
object VideoAppChooser {
    const val YOUTUBE = "com.google.android.youtube"
    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

    data class Candidate(val pkg: String, val activity: String)

    /** The regular YouTube app first, otherwise any non-music handler (a browser); never YouTube Music. */
    fun pick(handlers: List<Candidate>): Candidate? =
        handlers.firstOrNull { it.pkg == YOUTUBE }
            ?: handlers.firstOrNull { it.pkg != YOUTUBE_MUSIC }
}
