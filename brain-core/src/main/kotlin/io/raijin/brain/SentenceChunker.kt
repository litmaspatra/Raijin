package io.raijin.brain

/**
 * BR-7: turns streamed text deltas into whole sentences for speaking.
 * A sentence ends at . ! ? or the Hindi danda followed by whitespace, or at a
 * line break. Decimals ("3.5") and abbreviations without a following space do
 * not split. Very short fragments are held and joined to the next sentence.
 */
class SentenceChunker(private val minLength: Int = 12) {
    private val buffer = StringBuilder()

    /** Adds [delta] and returns any sentences that are now complete. */
    fun feed(delta: String): List<String> {
        buffer.append(delta)
        return drain(final = false)
    }

    /** Returns whatever text is left once the stream ends. */
    fun finish(): List<String> = drain(final = true)

    private fun drain(final: Boolean): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val cut = findCut()
            if (cut < 0) break
            val sentence = buffer.substring(0, cut + 1).trim()
            if (sentence.length < minLength && !final) {
                // Too short to speak alone: look for the next boundary after it.
                val next = findCut(from = cut + 1)
                if (next < 0) break
                out += buffer.substring(0, next + 1).trim()
                buffer.delete(0, next + 1)
                continue
            }
            if (sentence.isNotEmpty()) out += sentence
            buffer.delete(0, cut + 1)
        }
        if (final) {
            val rest = buffer.toString().trim()
            if (rest.isNotEmpty()) out += rest
            buffer.setLength(0)
        }
        return out
    }

    private fun findCut(from: Int = 0): Int {
        var i = from
        while (i < buffer.length) {
            val c = buffer[i]
            if (c == '\n') return i
            if (c == '.' || c == '!' || c == '?' || c == '\u0964') {
                // Needs a following whitespace to be sure the sentence is over.
                if (i + 1 < buffer.length && buffer[i + 1].isWhitespace()) return i
            }
            i++
        }
        return -1
    }
}
