package io.raijin.brain

/**
 * Turns a wake phrase ("hey eva") into the token line the on-device keyword spotter needs.
 * The spotter's model uses a SentencePiece unigram vocabulary; the best split of each word is the
 * highest-scoring one (Viterbi), which matches SentencePiece's own output. Pure Kotlin, no I/O.
 */
class UnigramTokenizer(private val scores: Map<String, Float>) {
    private val maxLen = scores.keys.maxOfOrNull { it.length } ?: 0

    /** Pieces for [phrase] (upper-cased, each word prefixed with the word-start mark), or null if a word cannot be split. */
    fun encode(phrase: String): List<String>? {
        val out = ArrayList<String>()
        for (word in phrase.uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            out += (split(WORD_START + word) ?: return null)
        }
        return out.ifEmpty { null }
    }

    private fun split(s: String): List<String>? {
        val n = s.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1)
        best[0] = 0.0
        for (j in 1..n) {
            for (i in maxOf(0, j - maxLen) until j) {
                val sc = scores[s.substring(i, j)] ?: continue
                if (best[i] + sc > best[j]) { best[j] = best[i] + sc; from[j] = i }
            }
        }
        if (best[n] == Double.NEGATIVE_INFINITY) return null
        val seg = ArrayList<String>()
        var j = n
        while (j > 0) { seg += s.substring(from[j], j); j = from[j] }
        return seg.reversed()
    }

    companion object {
        const val WORD_START = "\u2581"

        /** Parses lines of "piece<TAB>score". */
        fun parse(text: String): UnigramTokenizer = UnigramTokenizer(
            text.lineSequence().mapNotNull { line ->
                val i = line.lastIndexOf('\t')
                if (i <= 0) null else line.substring(i + 1).trim().toFloatOrNull()?.let { line.substring(0, i) to it }
            }.toMap(),
        )
    }
}

object WakeKeywords {
    const val DEFAULT_SCORE = 1.5f
    const val DEFAULT_THRESHOLD = 0.25f

    /** One keyword-file line for "hey <name>", or null if the name cannot be tokenised. */
    fun line(name: String, tokenizer: UnigramTokenizer, score: Float = DEFAULT_SCORE, threshold: Float = DEFAULT_THRESHOLD): String? {
        val pieces = tokenizer.encode(AssistantName.wakePhrase(name)) ?: return null
        return pieces.joinToString(" ") + " :$score #$threshold"
    }
}
