package io.raijin.brain

/**
 * OB-3: detect "write me a note …" style utterances and extract the note body.
 *
 * This is the eighth intent probe in the STT hypothesis wiring: after the
 * resolver has chosen a hypothesis (e.g. substituting "write" for a misheard
 * "right"), this parser runs on the CHOSEN text. Matching here never writes
 * anything; it starts the consent -> draft -> review -> write-on-yes flow.
 *
 * Pure JVM, no Android deps.
 */
object NoteTriggerParser {

    data class NoteRequest(
        /** The note content as spoken, verbatim (never paraphrased here). */
        val body: String,
    )

    private val VERB_NOTE = Regex(
        """^(?:write|take|make|create|jot(?:\s+down)?|put\s+down)\s+(?:me\s+)?(?:a\s+|another\s+)?(?:new\s+|quick\s+)?note\s*(?::|that|saying)?\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    private val NOTE_FIRST = Regex(
        """^note\s*(?:this|down|it)?\s*(?::)?\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    private val OBSIDIAN = Regex(
        """^(?:obsidian|in\s+obsidian|to\s+(?:my\s+)?obsidian)\s*(?::)?\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** "make a new note" with nothing to put in it yet: the app asks what the note should say. */
    private val BARE = Regex(
        """^(?:please\s+)?(?:write|take|make|create|start|jot\s+down|put\s+down)\s+(?:me\s+)?(?:a\s+|another\s+)?(?:new\s+|quick\s+)?note(?:\s+(?:in|to|for)\s+(?:my\s+)?obsidian)?(?:\s+please)?[.!]?$|^(?:new|quick)\s+note[.!]?$""",
        RegexOption.IGNORE_CASE,
    )

    fun isBare(text: String): Boolean = BARE.matches(text.trim())

    /** True when the utterance is a note request (probe predicate). */
    fun matches(text: String): Boolean = extract(text) != null

    /** Extract the verbatim note body, or null when this is not a note request. */
    fun extract(text: String): NoteRequest? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val body = VERB_NOTE.find(t)?.groupValues?.get(1)
            ?: NOTE_FIRST.find(t)?.groupValues?.get(1)
            ?: OBSIDIAN.find(t)?.groupValues?.get(1)
            ?: return null
        val clean = body.trim()
        return if (clean.isNotEmpty()) NoteRequest(clean) else null
    }
}
