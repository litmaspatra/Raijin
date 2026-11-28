package com.raijin.app.audio

/**
 * The on-device Kokoro voice turns words into sounds with a word list only (no espeak-ng).
 * A word that is not in the list would be skipped silently, so callers check first and
 * let the system voice read any sentence that has an unknown word.
 */
class SpokenLexicon(private val words: Set<String>) {

    /** Words (or numbers) in [text] that the lexicon cannot pronounce. Empty means safe to speak. */
    fun unknownWords(text: String): List<String> =
        TOKEN.findAll(text).map { it.value.trim('\'') }
            .filter { it.isNotEmpty() }
            .filter { it.any(Char::isDigit) || it.lowercase() !in words }
            .toList()

    fun canSpeak(text: String): Boolean = unknownWords(text).isEmpty()

    companion object {
        private val TOKEN = Regex("[\\p{L}\\p{N}']+")

        /** Lexicon lines look like `word p h o n e m e s`; the first field is the word. */
        fun parse(lines: Sequence<String>): SpokenLexicon {
            val set = HashSet<String>(100_000)
            for (line in lines) {
                val word = line.substringBefore(' ').trim()
                if (word.isNotEmpty()) set.add(word.lowercase())
            }
            return SpokenLexicon(set)
        }
    }
}
