package io.raijin.brain

/**
 * OB-3 pre-send consent: compute the EXACT outbound set for a cloud note-draft
 * request, before anything leaves the device. The review card shows these
 * verbatim lines; sending happens only on an explicit yes.
 *
 * Minimization rule: the drafter gets the raw utterance plus ONLY the
 * folder/title strings it needs for placement and linking — never the whole
 * vault, never note contents, never frontmatter. Title lists are capped; the
 * disclosure says exactly how many titles are sent out of how many exist.
 *
 * Pure JVM: inputs are plain data, so the disclosure is unit-testable.
 */
object NoteDraftConsent {

    /** Maximum note titles sent as drafting context. */
    const val MAX_CONTEXT_TITLES = 20

    /** Maximum folder names sent as drafting context. */
    const val MAX_CONTEXT_FOLDERS = 20

    data class OutboundPlan(
        /** Human name of the drafting provider, e.g. "CloudBrain (Claude API)". */
        val provider: String,
        /** The exact recipient the payload goes to, e.g. the API host. */
        val recipient: String,
        /** The owner's raw utterance, verbatim. */
        val utterance: String,
        /** Folder names that will be sent (possibly a capped subset). */
        val foldersSent: List<String>,
        /** Note titles that will be sent (possibly a capped subset). */
        val titlesSent: List<String>,
        val totalFolders: Int,
        val totalTitles: Int,
    ) {
        /** Verbatim disclosure lines for the pre-send review card. */
        fun disclosureLines(): List<String> = buildList {
            add("Sending to $provider at $recipient.")
            add("Note text sent, verbatim:")
            add("  \"$utterance\"")
            if (foldersSent.isNotEmpty()) {
                add("Folder names from your vault (${foldersSent.size} of $totalFolders):")
                foldersSent.forEach { add("  $it") }
            } else {
                add("No folder names are sent.")
            }
            if (titlesSent.isNotEmpty()) {
                add("Note titles from your vault (${titlesSent.size} of $totalTitles):")
                titlesSent.forEach { add("  $it") }
            } else {
                add("No note titles are sent.")
            }
            add("Nothing else leaves: no note contents, no frontmatter, no files.")
        }
    }

    /**
     * Build the outbound plan. [folders]/[titles] are the vault scan's real
     * structure; both are capped (scan order preserved) so a large vault can
     * never push an unbounded payload.
     */
    fun plan(
        provider: String,
        recipient: String,
        utterance: String,
        folders: List<String>,
        titles: List<String>,
    ): OutboundPlan = OutboundPlan(
        provider = provider,
        recipient = recipient,
        utterance = utterance,
        foldersSent = folders.take(MAX_CONTEXT_FOLDERS),
        titlesSent = titles.take(MAX_CONTEXT_TITLES),
        totalFolders = folders.size,
        totalTitles = titles.size,
    )
}
