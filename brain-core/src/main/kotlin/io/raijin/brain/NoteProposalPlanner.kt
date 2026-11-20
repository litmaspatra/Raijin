package io.raijin.brain

/**
 * OB-3/OB-4: shape vault-note proposals from a cloud draft, using ONLY the
 * real scanned vault structure (folders, note titles, aggregated conventions)
 * the app passes in. Never invents folders, never silently duplicates a
 * concept, never emits a broken wikilink.
 *
 * Pure JVM: every input is a plain snapshot, so the rules are unit-testable
 * without Android or a filesystem. The cloud draft text itself is data here;
 * this planner decides placement, dedup and link safety around it.
 */
object NoteProposalPlanner {

    /** Snapshot of the vault scan the app collected (VaultIndex). */
    data class VaultSnapshot(
        val folders: List<String>,
        /** Note title -> vault-relative path. */
        val notes: Map<String, String>,
        val dailyNoteFilenames: Boolean,
        val wikilinksUsed: Boolean,
    )

    /** One drafted note, as proposed by the cloud pass. */
    data class DraftNote(
        val title: String,
        /** The drafted markdown body. */
        val body: String = "",
        /** [[wikilink]] targets mentioned in the draft body. */
        val linkTargets: List<String>,
        /** True when the draft reads like a dated/journal entry. */
        val isDailyLike: Boolean,
    )

    data class Proposal(
        val title: String,
        /** Vault-relative path the note would be written to. */
        val targetPath: String,
        /** True = append to an existing note; false = create-only write. */
        val append: Boolean,
        /** Owner-facing reason for the placement/append decision. */
        val reason: String,
        /** Link targets that resolve to exactly one existing note. */
        val resolvedLinks: List<String>,
        /** Link targets with no existing note — must be shown, never emitted as [[links]]. */
        val unresolvedLinks: List<String>,
        /** The drafted body, carried through for the write step. */
        val body: String = "",
    )

    /**
     * Plan one proposal per draft note. Rules:
     *  - A title matching an existing note (case-insensitive) becomes an APPEND
     *    proposal to that note's real path — never a duplicate concept.
     *  - Otherwise placement follows detected conventions: daily-like drafts
     *    follow the daily convention only when the vault actually uses dated
     *    filenames; concept notes go to an existing folder whose name matches
     *    the draft's theme only when one exists; everything else lands at the
     *    vault root. Every choice carries its reason.
     *  - Links: only targets resolving to exactly one existing note stay
     *    linked; the rest are reported so the caller renders them as plain
     *    text (no broken/ambiguous [[links]]).
     */
    fun plan(snapshot: VaultSnapshot, drafts: List<DraftNote>): List<Proposal> =
        drafts.map { draft -> planOne(snapshot, draft) }

    private fun planOne(snapshot: VaultSnapshot, draft: DraftNote): Proposal {
        val existing = snapshot.notes.entries.firstOrNull { it.key.equals(draft.title, ignoreCase = true) }
        val resolved = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        for (target in draft.linkTargets) {
            val matches = snapshot.notes.keys.filter { it.equals(target, ignoreCase = true) }
            if (matches.size == 1) resolved += matches.single() else unresolved += target
        }

        if (existing != null) {
            return Proposal(
                title = existing.key,
                targetPath = existing.value,
                append = true,
                reason = "A note named '${existing.key}' already exists — appending instead of duplicating the concept.",
                resolvedLinks = resolved,
                unresolvedLinks = unresolved,
                body = draft.body,
            )
        }

        val (folder, reason) = when {
            draft.isDailyLike && snapshot.dailyNoteFilenames ->
                "" to "Dated entry; the vault already uses date-named daily notes, so this lands at the vault root with its date title."
            else -> {
                val themeFolder = snapshot.folders.firstOrNull { folderName ->
                    folderName.substringAfterLast('/').equals(draft.title, ignoreCase = true)
                }
                if (themeFolder != null) {
                    "$themeFolder/" to "Placed in the existing '$themeFolder' folder, which matches this note's topic."
                } else {
                    "" to "No existing folder matches this topic — created at the vault root rather than inventing one."
                }
            }
        }

        return Proposal(
            title = draft.title,
            targetPath = "$folder${sanitizeFilename(draft.title)}.md",
            append = false,
            reason = reason,
            resolvedLinks = resolved,
            unresolvedLinks = unresolved,
            body = draft.body,
        )
    }

    /** Characters that must never reach a vault-relative filename. */
    private val FORBIDDEN_FILENAME_CHARS =
        setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '#', '^', '[', ']')

    /**
     * Filename-safe form of a title: keeps the owner's words (any script),
     * replaces path-hostile characters with spaces via explicit filtering,
     * collapses whitespace, never blank. Combined with the VaultNote args
     * contract (relative .md, no ".." or empty segments) this keeps writes
     * inside the vault; a title of ".." becomes the harmless file "...md".
     */
    fun sanitizeFilename(title: String): String =
        title.map { if (it in FORBIDDEN_FILENAME_CHARS) ' ' else it }
            .joinToString("")
            .trim()
            .replace(Regex("""\s+"""), " ")
            .ifBlank { "untitled" }
}
