package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OB-3/OB-4: cloud draft parsing, link-safe rendering, prompt contents. */
class NoteDraftingTest {

    // ── NoteDraftParser ─────────────────────────────────────────────────

    @Test
    fun `well-formed blocks parse into drafts`() {
        val response = """
            Some preamble the model should not add but might.
            ===NOTE===
            TITLE: ship of theseus
            LINKS: [[sapiens]], [[identity]]
            BODY:
            The ship of Theseus asks whether identity survives replacement.

            Connected to [[sapiens]] through the idea of constructed stories.
            ===END===
            ===NOTE===
            TITLE: 2026-11-19
            LINKS: none
            BODY:
            Today I thought about identity.
            ===END===
        """.trimIndent()
        val drafts = NoteDraftParser.parse(response)
        assertEquals(2, drafts.size)
        assertEquals("ship of theseus", drafts[0].title)
        assertEquals(listOf("sapiens", "identity"), drafts[0].linkTargets)
        assertTrue(drafts[0].body.contains("identity survives replacement"))
        assertFalse(drafts[0].isDailyLike)
        assertEquals("2026-11-19", drafts[1].title)
        assertTrue(drafts[1].isDailyLike)
        assertTrue(drafts[1].linkTargets.isEmpty())
    }

    @Test
    fun `garbled or incomplete blocks are dropped fail-closed`() {
        assertTrue(NoteDraftParser.parse("total garbage with no blocks").isEmpty())
        // body missing -> dropped
        assertTrue(
            NoteDraftParser.parse("===NOTE===\nTITLE: x\n===END===").isEmpty()
        )
        // title missing -> dropped
        assertTrue(
            NoteDraftParser.parse("===NOTE===\nBODY:\nsome body\n===END===").isEmpty()
        )
    }

    // ── NoteBodyRenderer ────────────────────────────────────────────────

    @Test
    fun `resolved links keep wikilink form with vault casing`() {
        val out = NoteBodyRenderer.render(
            "See [[Sapiens]] and [[ship-of-theseus]].",
            resolvedLinks = listOf("sapiens", "ship-of-theseus"),
            unresolvedLinks = emptyList(),
        )
        assertEquals("See [[sapiens]] and [[ship-of-theseus]].", out)
    }

    @Test
    fun `unresolved and unclassified links become plain text`() {
        val out = NoteBodyRenderer.render(
            "See [[sapiens]] and [[made up idea]] and [[unseen target]].",
            resolvedLinks = listOf("sapiens"),
            unresolvedLinks = listOf("made up idea"),
        )
        assertEquals("See [[sapiens]] and made up idea and unseen target.", out)
    }

    // ── NoteDraftPrompt ─────────────────────────────────────────────────

    @Test
    fun `prompt contains only the disclosed outbound set`() {
        val plan = NoteDraftConsent.plan(
            provider = "P",
            recipient = "host",
            utterance = "write a note about theseus",
            folders = listOf("concepts"),
            titles = listOf("sapiens"),
        )
        val prompt = NoteDraftPrompt.build(plan)
        assertTrue(prompt.contains("write a note about theseus"))
        assertTrue(prompt.contains("- concepts"))
        assertTrue(prompt.contains("- sapiens"))
        assertTrue(prompt.contains("preserve the speaker's meaning"))
        assertTrue(prompt.contains("===NOTE==="))
        // The prompt must not reference anything beyond the disclosed set.
        assertFalse(prompt.contains("frontmatter"))
    }
}
