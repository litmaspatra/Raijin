package io.raijin.brain

/**
 * The owner's chosen form of address ("call me sir always"). Parsing is cue-gated so
 * "call me a cab" or "call me back" never changes it. The saved term replaces the
 * default "boss" in every spoken reply (see [rewrite]).
 */
object AddressForm {
    private const val DEFAULT = "boss"

    private val A = Regex("""^(?:please\s+)?(?:from\s+now\s+on|always|going\s+forward|from\s+here\s+on)[,\s]+(?:call|address|refer\s+to)\s+me\s+(?:as\s+)?(.+)$""", RegexOption.IGNORE_CASE)
    private val B = Regex("""^(?:please\s+)?(?:call|address|refer\s+to)\s+me\s+(?:as\s+)?(.+?)[,\s]+(?:always|from\s+now\s+on|going\s+forward|from\s+here\s+on)$""", RegexOption.IGNORE_CASE)
    private val C = Regex("""^(?:please\s+)?(?:address|refer\s+to)\s+me\s+as\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val D = Regex("""^(?:please\s+)?stop\s+(?:calling|addressing)\s+me\s+.+?[,.]?\s+(?:and\s+)?(?:call|address)\s+me\s+(?:as\s+)?(.+)$""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""^(?:please\s+)?(?:call|address|refer\s+to)\s+me\s+(?:as\s+)?(.+)$""", RegexOption.IGNORE_CASE)

    private val BAD_FIRST = setOf(
        "a", "an", "the", "back", "later", "at", "on", "when", "if", "my", "up", "tomorrow", "now",
        "soon", "in", "once", "after", "before", "tonight", "today", "again", "me", "to",
    )
    private val WORD = Regex("""^[\p{L}][\p{L}'-]*$""")

    /**
     * The new form of address in [text], or null. [remembered] is true when [text] is the body
     * of an explicit "remember ..." request, where the persistence cue is already there.
     */
    fun parse(text: String, remembered: Boolean = false): String? {
        val t = text.trim().trimEnd('.', '!', '?')
        val raw = A.matchEntire(t)?.groupValues?.get(1)
            ?: B.matchEntire(t)?.groupValues?.get(1)
            ?: D.matchEntire(t)?.groupValues?.get(1)
            ?: C.matchEntire(t)?.groupValues?.get(1)
            ?: (if (remembered) BARE.matchEntire(t)?.groupValues?.get(1) else null)
            ?: return null
        return clean(raw)
    }

    private fun clean(raw: String): String? {
        var s = raw.trim().trimEnd('.', '!', '?', ',').trim()
        s = s.replace(Regex("""\s+(?:please|always|from\s+now\s+on)$""", RegexOption.IGNORE_CASE), "").trim()
        val words = s.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 3 || s.length > 30) return null
        if (words.any { !WORD.matches(it) }) return null
        if (words.first().lowercase() in BAD_FIRST) return null
        return words.joinToString(" ")
    }

    /** Replaces the default "boss" with [term] in spoken text, keeping sentence-start capitalisation. */
    fun rewrite(text: String, term: String?): String {
        val t = term?.trim().orEmpty()
        if (t.isEmpty() || t.equals(DEFAULT, ignoreCase = true)) return text
        return Regex("""\bboss\b""", RegexOption.IGNORE_CASE).replace(text) { m ->
            if (m.value.first().isUpperCase()) t.replaceFirstChar { it.uppercaseChar() } else t
        }
    }

    fun confirmation(term: String): String = "Okay, I'll call you $term from now on."
}
