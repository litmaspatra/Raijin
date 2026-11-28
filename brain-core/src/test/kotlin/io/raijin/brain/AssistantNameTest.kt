package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AssistantNameTest {
    @Test fun parsesRenames() {
        assertEquals(AssistantName.Rename("Eva", null), AssistantName.parseRename("call yourself Eva"))
        assertEquals(AssistantName.Rename("Eva", null), AssistantName.parseRename("Your name is now eva."))
        assertEquals(AssistantName.Rename("George", true), AssistantName.parseRename("rename yourself to George, male"))
        assertEquals(AssistantName.Rename("Sam", false), AssistantName.parseRename("call yourself Sam she"))
    }

    @Test fun ignoresOtherSentences() {
        assertNull(AssistantName.parseRename("call me sir"))
        assertNull(AssistantName.parseRename("call yourself it"))
        assertNull(AssistantName.parseRename("what is your name"))
    }

    @Test fun genderTable() {
        assertEquals(false, AssistantName.genderOf("Eva"))
        assertEquals(false, AssistantName.genderOf("priya"))
        assertEquals(true, AssistantName.genderOf("Rahul"))
        assertEquals(true, AssistantName.genderOf("George"))
        assertNull(AssistantName.genderOf("Zyxel"))
    }

    @Test fun genderAnswerAndWakePhrase() {
        assertEquals(true, AssistantName.parseGenderAnswer("male"))
        assertEquals(false, AssistantName.parseGenderAnswer("She."))
        assertNull(AssistantName.parseGenderAnswer("maybe"))
        assertEquals("hey eva", AssistantName.wakePhrase("Eva"))
    }

    @Test fun selfEditRoutesRenameAndAwaitingAnswer() {
        assertEquals(SelfEdit.Command.SetName("Eva", null), SelfEdit.parse("call yourself Eva"))
        assertNull(SelfEdit.parse("female"))
        assertEquals(SelfEdit.Command.SetVoiceGender(false), SelfEdit.parse("female", awaitingGender = true))
    }
}
