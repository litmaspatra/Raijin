package io.raijin.brain

/**
 * "What is inside my Obsidian folder?" style questions. Answered on the phone from the
 * selected vault; must be recognised before the generic "what is ..." web research
 * reflex so the question is never sent anywhere.
 */
object VaultQueryParser {
    private val QUERIES = listOf(
        Regex(
            """^(?:please\\s+)?(?:what(?:'s|\\s+is|\\s+are)?|tell\\s+me\\s+what(?:'s|\\s+is)?|show\\s+me\\s+what(?:'s|\\s+is)?)\\s+(?:inside|in|within)\\s+(?:my\\s+|the\\s+)?(?:obsidian|vault|notes?)(?:\\s+(?:folder|vault|notes?))?[?.!]?$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """^(?:please\\s+)?(?:list|show|read|open|check)(?:\\s+me)?\\s+(?:all\\s+)?(?:of\\s+)?(?:my\\s+|the\\s+)?(?:obsidian|vault|notes?)(?:\\s+(?:folder|vault|notes?|contents?|folders?))?(?:\\s+please)?[?.!]?$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """^(?:what|which)\\s+notes\\s+(?:do\\s+i\\s+have|are\\s+there)(?:\\s+in\\s+(?:my\\s+)?(?:obsidian|vault)(?:\\s+(?:folder|vault))?)?[?.!]?$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """^(?:what(?:'s|\\s+is)\\s+)?(?:inside|in)\\s+(?:my\\s+)?obsidian(?:\\s+(?:folder|vault))?[?.!]?$""",
            RegexOption.IGNORE_CASE,
        ),
    )

    fun matches(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && QUERIES.any { it.matches(t) }
    }

    /** Spoken summary built only from the vault scan: counts, folder names, a few titles. */
    fun summarize(folders: List<String>, noteTitles: List<String>): String {
        if (folders.isEmpty() && noteTitles.isEmpty()) return "Your vault folder is empty, boss."
        val n = noteTitles.size
        val top = folders.filter { !it.contains('/') }.take(8)
        return buildString {
            append("Your vault has $n note${if (n == 1) "" else "s"}")
            append(" in ${folders.size} folder${if (folders.size == 1) "" else "s"}. ")
            if (top.isNotEmpty()) append("Top folders: ${top.joinToString(", ")}. ")
            val sample = noteTitles.sorted().take(5)
            if (sample.isNotEmpty()) append("Some notes: ${sample.joinToString(", ")}.")
        }.trim()
    }
}
