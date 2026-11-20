package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OB-3/OB-4: convention-following placement, dedup, link safety. */
class NoteProposalPlannerTest {

    private val vault = NoteProposalPlanner.VaultSnapshot(
        folders = listOf("books", "concepts"),
        notes = mapOf(
            "sapiens" to "books/sapiens.md",
            "ship-of-theseus" to "concepts/ship-of-theseus.md",
        ),
        dailyNoteFilenames = false,
        wikilinksUsed = true,
    )

    @Test
    fun `existing concept title becomes an append to the real path`() {
        val drafts = listOf(
            NoteProposalPlanner.DraftNote("Sapiens", linkTargets = emptyList(), isDailyLike = false),
        )
        val p = NoteProposalPlanner.plan(vault, drafts).single()
        assertTrue(p.append)
        assertEquals("books/sapiens.md", p.targetPath)
        assertEquals("sapiens", p.title)
        assertTrue(p.reason.contains("already exists"))
    }

    @Test
    fun `new concept with matching folder is placed there`() {
        val drafts = listOf(
            NoteProposalPlanner.DraftNote("concepts", linkTargets = emptyList(), isDailyLike = false),
        )
        val p = NoteProposalPlanner.plan(vault, drafts).single()
        assertFalse(p.append)
        assertEquals("concepts/concepts.md", p.targetPath)
    }

    @Test
    fun `new concept with no matching folder lands at root rather than inventing one`() {
        val drafts = listOf(
            NoteProposalPlanner.DraftNote("stoicism", linkTargets = emptyList(), isDailyLike = false),
        )
        val p = NoteProposalPlanner.plan(vault, drafts).single()
        assertEquals("stoicism.md", p.targetPath)
        assertTrue(p.reason.contains("vault root"))
    }

    @Test
    fun `daily-like draft follows daily convention only when the vault uses one`() {
        val dailyVault = vault.copy(dailyNoteFilenames = true)
        val drafts = listOf(
            NoteProposalPlanner.DraftNote("2026-11-19", linkTargets = emptyList(), isDailyLike = true),
        )
        val p = NoteProposalPlanner.plan(dailyVault, drafts).single()
        assertEquals("2026-11-19.md", p.targetPath)
        assertTrue(p.reason.contains("daily"))
    }

    @Test
    fun `links resolve only against exactly one existing note`() {
        val drafts = listOf(
            NoteProposalPlanner.DraftNote("identity", linkTargets = listOf("ship-of-theseus", "nonexistent idea"),
                isDailyLike = false,
            ),
        )
        val p = NoteProposalPlanner.plan(vault, drafts).single()
        assertEquals(listOf("ship-of-theseus"), p.resolvedLinks)
        assertEquals(listOf("nonexistent idea"), p.unresolvedLinks)
    }

    @Test
    fun `filename sanitizer keeps words, strips path-hostile chars, never blank`() {
        assertEquals("ship of theseus", NoteProposalPlanner.sanitizeFilename("ship/of:theseus"))
        assertEquals("untitled", NoteProposalPlanner.sanitizeFilename("  "))
        assertEquals("a b", NoteProposalPlanner.sanitizeFilename("a   b"))
    }

    @Test
    fun `sanitizer covers brackets backslash and slash`() {
        assertEquals("a b c d", NoteProposalPlanner.sanitizeFilename("a[b]c\\d"))
        assertEquals("x y", NoteProposalPlanner.sanitizeFilename("x/y"))
    }

    @Test
    fun `sanitizer traversal title yields harmless file inside vault`() {
        val drafts = listOf(
            NoteProposalPlanner.DraftNote("..", linkTargets = emptyList(), isDailyLike = false),
        )
        val p = NoteProposalPlanner.plan(vault, drafts).single()
        assertEquals("...md", p.targetPath)
        assertFalse(p.targetPath.split('/').any { it == ".." })
    }

    @Test
    fun `sanitizer keeps Devanagari titles intact`() {
        assertEquals("संकल्पना", NoteProposalPlanner.sanitizeFilename("संकल्पना"))
    }
}
