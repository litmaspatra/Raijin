package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultQueryParserTest {
    @Test fun routesFolderQuestions() {
        listOf(
            "what is inside my obsidian folder", "What's in my Obsidian vault?", "what is in my obsidian",
            "list my notes", "show my obsidian vault", "what notes do I have", "what is inside my vault",
        ).forEach { assertTrue(VaultQueryParser.matches(it), it) }
    }

    @Test fun leavesOtherQuestionsAlone() {
        listOf("what is the capital of France", "what is inside a black hole", "write a note buy milk").forEach {
            assertFalse(VaultQueryParser.matches(it), it)
        }
    }

    @Test fun summaryUsesOnlyScanData() {
        val s = VaultQueryParser.summarize(listOf("Daily", "Daily/2026", "Ideas"), listOf("b", "a"))
        assertEquals("Your vault has 2 notes in 3 folders. Top folders: Daily, Ideas. Some notes: a, b.", s)
        assertEquals("Your vault folder is empty, boss.", VaultQueryParser.summarize(emptyList(), emptyList()))
    }

    @Test fun bareNoteRequestsAndBodies() {
        listOf("make a new note", "create a note", "take a quick note", "new note", "make a note in obsidian").forEach {
            assertTrue(NoteTriggerParser.isBare(it), it)
        }
        assertFalse(NoteTriggerParser.isBare("make a note buy milk"))
        assertEquals("buy milk", NoteTriggerParser.extract("make a new note buy milk")?.body)
        assertEquals("buy milk", NoteTriggerParser.extract("write a quick note that buy milk")?.body)
        assertNull(NoteTriggerParser.extract("make a new note"))
        assertNotNull(NoteTriggerParser.extract("note this call mom"))
    }
}
