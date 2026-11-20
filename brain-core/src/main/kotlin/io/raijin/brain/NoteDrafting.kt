package io.raijin.brain

/**
 * OB-3: parse the cloud drafter's response into structured draft notes.
 *
 * The drafter is instructed (see [NoteDraftPrompt]) to answer in a strict
 * block format so parsing never guesses:
 *
 *   ===NOTE===
 *   TITLE: ship of theseus
 *   LINKS: [[sapiens]], [[identity]]
 *   BODY:
 *   free markdown body, may span lines
 *   ===END===
 *
 * Anything outside a well-formed block is dropped (fail-closed): a garbled
 * cloud answer yields fewer or zero drafts, never invented notes.
 */
object NoteDraftParser {

    private val TITLE_RE = Regex("""^TITLE:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val LINKS_RE = Regex("""^LINKS:\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val LINK_TARGET_RE = Regex("""\[\[([^\]]+)]]""")
    private val DAILY_RE = Regex("""\d{4}-\d{2}-\d{2}""")

    fun parse(response: String): List<NoteProposalPlanner.DraftNote> {
        val drafts = mutableListOf<NoteProposalPlanner.DraftNote>()
        val lines = response.lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim().equals("===NOTE===", ignoreCase = true)) {
                var title: String? = null
                var links: List<String> = emptyList()
                var bodyLines: List<String> = emptyList()
                var j = i + 1
                var closed = false
                while (j < lines.size && !lines[j].trim().equals("===NOTE===", ignoreCase = true)) {
                    val line = lines[j]
                    if (line.trim().equals("===END===", ignoreCase = true)) {
                        closed = true
                        break
                    }
                    val titleMatch = TITLE_RE.matchEntire(line.trim())
                    val linksMatch = LINKS_RE.matchEntire(line.trim())
                    when {
                        titleMatch != null -> title = titleMatch.groupValues[1].trim()
                        linksMatch != null ->
                            links = LINK_TARGET_RE.findAll(linksMatch.groupValues[1])
                                .map { it.groupValues[1].trim() }
                                .filter { it.isNotEmpty() }
                                .toList()
                        line.trim().equals("BODY:", ignoreCase = true) -> {
                            val collected = mutableListOf<String>()
                            var k = j + 1
                            while (k < lines.size &&
                                !lines[k].trim().equals("===END===", ignoreCase = true) &&
                                !lines[k].trim().equals("===NOTE===", ignoreCase = true)
                            ) {
                                collected += lines[k]
                                k++
                            }
                            bodyLines = collected
                            j = k - 1
                        }
                    }
                    j++
                }
                val cleanTitle = title?.trim().orEmpty()
                val cleanBody = bodyLines.joinToString("\n").trim()
                // Fail-closed: a block without a usable title or body is dropped.
                if (cleanTitle.isNotEmpty() && cleanBody.isNotEmpty()) {
                    drafts += NoteProposalPlanner.DraftNote(
                        title = cleanTitle,
                        body = cleanBody,
                        linkTargets = links,
                        isDailyLike = DAILY_RE.containsMatchIn(cleanTitle),
                    )
                }
                i = if (closed) j + 1 else j
            } else {
                i++
            }
        }
        return drafts
    }
}

/**
 * OB-4 link safety: render a draft body so only links that resolve to exactly
 * one existing note stay [[linked]] (with the vault's exact title casing);
 * every unresolved target becomes plain text. No broken or ambiguous links.
 */
object NoteBodyRenderer {

    private val LINK_RE = Regex("""\[\[([^\]]+)]]""")

    fun render(
        body: String,
        resolvedLinks: List<String>,
        unresolvedLinks: List<String>,
    ): String {
        val resolvedByLower = resolvedLinks.associateBy { it.lowercase() }
        val unresolvedLower = unresolvedLinks.map { it.lowercase() }.toSet()
        return LINK_RE.replace(body) { m ->
            val target = m.groupValues[1].trim()
            val exact = resolvedByLower[target.lowercase()]
            when {
                exact != null -> "[[$exact]]"
                target.lowercase() in unresolvedLower -> target
                // A target the planner never classified is treated as unresolved
                // (fail-closed): render as plain text rather than risk a broken link.
                else -> target
            }
        }
    }
}

/**
 * OB-3: build the cloud drafting prompt from the consent-approved outbound
 * plan. Contains ONLY what the plan discloses: the raw utterance and the
 * (capped) folder/title context strings.
 */
object NoteDraftPrompt {

    fun build(plan: NoteDraftConsent.OutboundPlan): String = buildString {
        appendLine("You are drafting Obsidian notes from a voice dictation.")
        appendLine("Rules: preserve the speaker's meaning and uncertainty; do not paraphrase into a")
        appendLine("'better version'; split genuinely distinct ideas into separate notes; use")
        appendLine("[[wikilinks]] only for links to the existing notes listed below; mark")
        appendLine("uncertain attributions as uncertain.")
        appendLine()
        appendLine("Dictation (verbatim):")
        appendLine(plan.utterance)
        appendLine()
        if (plan.foldersSent.isNotEmpty()) {
            appendLine("Existing vault folders:")
            plan.foldersSent.forEach { appendLine("- $it") }
            appendLine()
        }
        if (plan.titlesSent.isNotEmpty()) {
            appendLine("Existing note titles (link only to these):")
            plan.titlesSent.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine("Answer ONLY in this format, one block per note:")
        appendLine("===NOTE===")
        appendLine("TITLE: <note title>")
        appendLine("LINKS: [[existing title]], [[another existing title]] (or none)")
        appendLine("BODY:")
        appendLine("<markdown body>")
        appendLine("===END===")
    }
}
