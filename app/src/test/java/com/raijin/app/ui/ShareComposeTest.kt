package com.raijin.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareComposeTest {
    @Test fun cleanTrimsAndCaps() {
        assertEquals("hello", ShareCompose.clean("  hello \n"))
        assertNull(ShareCompose.clean("   "))
        assertNull(ShareCompose.clean(null))
        assertEquals(ShareCompose.MAX_SHARED_CHARS, ShareCompose.clean("a".repeat(5_000))!!.length)
    }

    @Test fun instructionAndSharedAreJoined() =
        assertEquals("remind me about this: Dentist at 5", ShareCompose.compose(" remind me about this ", "Dentist at 5"))

    @Test fun sharedAloneIsSentAsIs() = assertEquals("Dentist at 5", ShareCompose.compose("", "Dentist at 5"))

    @Test fun typedAloneStillWorks() = assertEquals("open whatsapp", ShareCompose.compose("open whatsapp", null))

    @Test fun cannotSendNothing() {
        assertFalse(ShareCompose.canSend("  ", null))
        assertTrue(ShareCompose.canSend("", "x"))
        assertTrue(ShareCompose.canSend("x", null))
    }
}
