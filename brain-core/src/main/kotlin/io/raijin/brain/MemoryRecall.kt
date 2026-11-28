package io.raijin.brain

/**
 * Remember and recall for personal facts ("my name is ...", "call me sir"). Pure parsing and
 * ranking; the app does the storage. Everything here runs on the phone.
 */
object MemoryRecall {
    private val REMEMBER = Regex(
        """^(?:please\s+)?(?:remember(?:\s+that|:)?|don'?t\s+forget(?:\s+that)?|note\s+that)[,\s]*(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** The fact to store from a "remember ..." request, or null. */
    fun extractFact(text: String): String? =
        REMEMBER.matchEntire(text.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private val RECALL = listOf(
        Regex("""^who\s+am\s+i[?.!]*$""", RegexOption.IGNORE_CASE) to "name",
        Regex("""^what\s+did\s+i\s+tell\s+you\s+about\s+(.+)$""", RegexOption.IGNORE_CASE) to null,
        Regex("""^do\s+you\s+remember\s+(.+)$""", RegexOption.IGNORE_CASE) to null,
        Regex("""^(?:what(?:'s|s|\s+is|\s+are)|tell\s+me)\s+(my\s+.+)$""", RegexOption.IGNORE_CASE) to null,
        Regex("""^recall\s+(.+)$""", RegexOption.IGNORE_CASE) to null,
    )

    /** The recall subject ("my name", "my birthday") without punctuation, or null when not a recall question. */
    fun extractQuery(text: String): String? {
        val t = text.trim()
        for ((re, fixed) in RECALL) {
            val m = re.matchEntire(t) ?: continue
            val q = fixed ?: m.groupValues[1]
            val clean = q.trim().trimEnd('?', '.', '!', ',').trim()
            if (clean.isNotEmpty()) return clean
        }
        return null
    }

    private val STOP = setOf(
        "the", "a", "an", "my", "me", "is", "are", "about", "of", "to", "that", "did", "i", "you", "tell",
        "was", "do", "does", "in", "on", "for", "and", "it", "this",
    )

    fun keywords(query: String): List<String> =
        query.lowercase().split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.isNotEmpty() && it !in STOP }
            .distinct()

    /** Facts that contain the most of [keywords], best first; facts matching none are dropped. */
    fun rank(facts: List<String>, keywords: List<String>): List<String> =
        facts.map { f -> f to keywords.count { f.lowercase().contains(it) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }

    /** "my name is Kartik" -> "Your name is Kartik". */
    fun phrase(fact: String): String {
        var s = fact.trim().trimEnd('.', '!')
        s = s.replace(Regex("""\bI am\b"""), "you are")
            .replace(Regex("""\bI'm\b""", RegexOption.IGNORE_CASE), "you're")
            .replace(Regex("""\bmy\b""", RegexOption.IGNORE_CASE)) { m -> if (m.value[0].isUpperCase()) "Your" else "your" }
            .replace(Regex("""\bmine\b""", RegexOption.IGNORE_CASE), "yours")
            .replace(Regex("""\bme\b""", RegexOption.IGNORE_CASE), "you")
            .replace(Regex("""\bI\b"""), "you")
        return s.replaceFirstChar { it.uppercaseChar() } + "."
    }
}
