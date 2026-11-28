package io.raijin.brain

import io.raijin.brain.SelfEdit.Command
import io.raijin.brain.SelfEdit.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfEditTest {
    @Test fun addressStillParses() {
        assertEquals(Command.SetAddress("sir"), SelfEdit.parse("always call me sir"))
        assertNull(SelfEdit.parse("call me a cab"))
    }

    @Test fun speedCommands() {
        assertEquals(Command.SpeedStep(0.2f), SelfEdit.parse("speak faster"))
        assertEquals(Command.SpeedStep(-0.2f), SelfEdit.parse("Please speak a bit slower."))
        assertEquals(Command.SetSpeed(1.5f), SelfEdit.parse("set your speech speed to 1.5"))
        assertNull(SelfEdit.parse("set your speech speed to 9"))
    }

    @Test fun voiceGender() {
        assertEquals(Command.SetVoiceGender(true), SelfEdit.parse("use a male voice"))
        assertEquals(Command.SetVoiceGender(false), SelfEdit.parse("switch to female voice"))
        assertEquals(Command.SetVoiceGender(false), SelfEdit.parse("change your voice to a woman"))
        assertNull(SelfEdit.parse("play a female voice podcast"))
    }

    @Test fun undo() {
        assertEquals(Command.Undo, SelfEdit.parse("undo that"))
        assertEquals(Command.Undo, SelfEdit.parse("Undo."))
        assertNull(SelfEdit.parse("undo the alarm tomorrow"))
    }

    @Test fun coreIsLockedAndLeavingThePhoneAsksFirst() {
        assertEquals(Command.Refuse(Reason.CORE_LOCKED), SelfEdit.parse("turn off your privacy checks"))
        assertEquals(Command.Refuse(Reason.CORE_LOCKED), SelfEdit.parse("change your own code"))
        assertEquals(Command.Refuse(Reason.CORE_LOCKED), SelfEdit.parse("give yourself permission to read my sms"))
        assertEquals(Command.Refuse(Reason.ASK_FIRST), SelfEdit.parse("send my contacts to the cloud"))
        assertEquals(Command.Refuse(Reason.ASK_FIRST), SelfEdit.parse("turn off cloud context"))
        assertTrue(SelfEdit.refusal(Reason.ASK_FIRST).contains("Settings"))
    }

    @Test fun ordinaryRequestsAreNotSelfEdits() {
        assertNull(SelfEdit.parse("what's the weather"))
        assertNull(SelfEdit.parse("turn off the alarm"))
        assertNull(SelfEdit.parse("send my mother a message"))
    }

    @Test fun logIsLastInFirstOut() {
        var raw = ""
        val log = SelfEditLog({ raw }, { raw = it })
        log.push(SelfEditEntry("ADDRESS", null, "sir", "call me sir"))
        log.push(SelfEditEntry("SPEED", "1.0", "1.2"))
        assertEquals(SelfEditEntry("SPEED", "1.0", "1.2", null), log.pop())
        assertEquals(SelfEditEntry("ADDRESS", null, "sir", "call me sir"), log.pop())
        assertNull(log.pop())
    }

    @Test fun logKeepsOnlyRecentEntriesAndOddText() {
        var raw = ""
        val log = SelfEditLog({ raw }, { raw = it }, max = 2)
        log.push(SelfEditEntry("A", "x y\nz", "1"))
        log.push(SelfEditEntry("B", null, "2"))
        log.push(SelfEditEntry("C", "3", null))
        assertEquals(listOf("B", "C"), log.all().map { it.key })
        assertEquals("3", log.all().last().before)
        assertNull(log.all().last().after)
    }
}
