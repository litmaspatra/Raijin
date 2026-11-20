package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OB-3: note trigger parsing + pre-send consent disclosure. */
class NoteOb3Test {

    // ── NoteTriggerParser ───────────────────────────────────────────────

    @Test
    fun `write a note variants extract the verbatim body`() {
        assertEquals("ship of theseus idea", NoteTriggerParser.extract("write a note ship of theseus idea")?.body)
        assertEquals("call the plumber", NoteTriggerParser.extract("Write me a note: call the plumber")?.body)
        assertEquals("sapiens chapter 4", NoteTriggerParser.extract("take a note sapiens chapter 4")?.body)
        assertEquals("buy milk", NoteTriggerParser.extract("jot down a note buy milk")?.body)
    }

    @Test
    fun `note-first and obsidian forms extract the body`() {
        assertEquals("this idea about identity", NoteTriggerParser.extract("note this: this idea about identity")?.body)
        assertEquals("remember the ship of theseus", NoteTriggerParser.extract("note down remember the ship of theseus")?.body)
        assertEquals("link sapiens to theseus", NoteTriggerParser.extract("obsidian: link sapiens to theseus")?.body)
    }

    @Test
    fun `non-note utterances do not match`() {
        assertNull(NoteTriggerParser.extract("play some music"))
        assertNull(NoteTriggerParser.extract("write an email to ma"))
        assertNull(NoteTriggerParser.extract("right a note about theseus")) // pre-substitution form
        assertNull(NoteTriggerParser.extract("write a note"))
        assertNull(NoteTriggerParser.extract("note"))
        assertFalse(NoteTriggerParser.matches("set alarm for 7"))
    }

    // ── NoteDraftConsent ────────────────────────────────────────────────

    @Test
    fun `disclosure shows exact provider recipient and verbatim utterance`() {
        val plan = NoteDraftConsent.plan(
            provider = "CloudBrain (Claude API)",
            recipient = "api.anthropic.com",
            utterance = "write a note about the ship of theseus",
            folders = listOf("books", "concepts"),
            titles = listOf("sapiens", "ship-of-theseus"),
        )
        val lines = plan.disclosureLines()
        assertTrue(lines.any { it.contains("CloudBrain (Claude API)") && it.contains("api.anthropic.com") })
        assertTrue(lines.any { it.contains("\"write a note about the ship of theseus\"") })
        assertTrue(lines.any { it.contains("Folder names from your vault (2 of 2)") })
        assertTrue(lines.any { it.contains("Note titles from your vault (2 of 2)") })
        assertTrue(lines.any { it.contains("no note contents") })
    }

    @Test
    fun `large vault is capped and the cap is disclosed`() {
        val folders = (1..30).map { "folder-$it" }
        val titles = (1..50).map { "title-$it" }
        val plan = NoteDraftConsent.plan("P", "host", "u", folders, titles)
        assertEquals(NoteDraftConsent.MAX_CONTEXT_FOLDERS, plan.foldersSent.size)
        assertEquals(NoteDraftConsent.MAX_CONTEXT_TITLES, plan.titlesSent.size)
        assertEquals(30, plan.totalFolders)
        assertEquals(50, plan.totalTitles)
        val lines = plan.disclosureLines()
        assertTrue(lines.any { it.contains("(20 of 30)") })
        assertTrue(lines.any { it.contains("(20 of 50)") })
    }

    @Test
    fun `empty vault sends no structure and says so`() {
        val plan = NoteDraftConsent.plan("P", "host", "u", emptyList(), emptyList())
        assertTrue(plan.foldersSent.isEmpty())
        assertTrue(plan.titlesSent.isEmpty())
        val lines = plan.disclosureLines()
        assertTrue(lines.any { it.contains("No folder names are sent") })
        assertTrue(lines.any { it.contains("No note titles are sent") })
    }
}
