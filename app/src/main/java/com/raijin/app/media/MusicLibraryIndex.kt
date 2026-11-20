package com.raijin.app.media

import io.raijin.brain.ContactNameNormalizer

/**
 * MU-1: a track from the owner's on-device library (MediaStore audio).
 * [contentUri] is what the in-app player plays; it is never uploaded.
 */
data class LibraryTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val contentUri: String,
)

data class LibraryMatch(
    val track: LibraryTrack,
    val score: Int,
)

/**
 * MU-1: deterministic, instant in-memory index over the owner's local
 * library. Matching reuses CT-3's normalizer, so a Devanagari query
 * ("एरिजीत") matches a Latin-script artist ("Arijit") and the reverse.
 *
 * Ranking is deliberately simple and explainable (MU-6 builds the learned
 * preference model on top later): exact artist > exact title > prefix >
 * substring, album matches last.
 */
class MusicLibraryIndex(tracks: List<LibraryTrack>) {

    private class Indexed(
        val track: LibraryTrack,
        val title: String,
        val artist: String,
        val album: String,
        val titleSkeleton: String,
        val artistSkeleton: String,
    )

    private val items: List<Indexed> = tracks.map { t ->
        val title = ContactNameNormalizer.normalize(t.title)
        val artist = ContactNameNormalizer.normalize(t.artist)
        Indexed(
            track = t,
            title = title,
            artist = artist,
            album = ContactNameNormalizer.normalize(t.album),
            titleSkeleton = skeleton(title),
            artistSkeleton = skeleton(artist),
        )
    }

    val size: Int get() = items.size

    /** Every indexed track (MU-7 sampling candidates). */
    fun all(): List<LibraryTrack> = items.map { it.track }

    fun search(query: String, limit: Int = 10): List<LibraryMatch> {
        val q = ContactNameNormalizer.normalize(query)
        if (q.isBlank()) return emptyList()
        return items
            .mapNotNull { it.scoreAgainst(q) }
            .sortedWith(compareByDescending<LibraryMatch> { it.score }.thenBy { it.track.title })
            .take(limit)
    }

    /** The single best match when it clears the auto-play bar, else null. */
    fun bestMatch(query: String): LibraryMatch? =
        search(query, limit = 1).firstOrNull()?.takeIf { it.score >= AUTO_PLAY_THRESHOLD }

    private fun Indexed.scoreAgainst(q: String): LibraryMatch? {
        val qSkeleton = skeleton(q)
        val score = when {
            artist == q -> 100
            title == q -> 90
            artist.startsWith(q) -> 80
            title.startsWith(q) -> 70
            q.split(' ').all { artist.split(' ').contains(it) } && q.contains(' ') -> 65
            artist.contains(q) -> 60
            // Cross-script safety net: the Devanagari transliterator inserts
            // inherent 'a's that natural Latin spellings drop ("bedaradeya"
            // vs "bedardaya"). Vowel-skeleton equality ("bdrdy") bridges
            // that without risking auto-play - it sits below the threshold.
            qSkeleton.length >= 4 &&
                (titleSkeleton == qSkeleton || artistSkeleton == qSkeleton) -> 55
            title.contains(q) -> 50
            album.contains(q) -> 30
            else -> return null
        }
        return LibraryMatch(track, score)
    }

    companion object {
        /** Normalized form with vowels and spaces stripped - script-agnostic shape. */
        private fun skeleton(normalized: String): String =
            normalized.replace(Regex("[aeiou ]"), "")

        /**
         * Minimum score for the Tier-1 auto-play decision ("play X" starts
         * local playback without asking). Substring-title hits (50) stay
         * below it on purpose: "play night" should not hijack a vague query
         * when streaming search would do better.
         */
        const val AUTO_PLAY_THRESHOLD = 70
    }
}

/** MU-1: where library tracks come from. MediaStore on device; fakes in tests. */
interface MusicLibraryScanner {
    /**
     * Scan the device library. Returns an empty list (never throws) when the
     * media-audio permission is missing or the provider errors - the caller
     * degrades to Tier 2 streaming search honestly.
     */
    suspend fun scan(): List<LibraryTrack>
}
