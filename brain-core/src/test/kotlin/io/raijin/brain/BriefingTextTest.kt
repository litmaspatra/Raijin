package io.raijin.brain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class BriefingTextTest {
    @Test fun `greeting follows current device local hour`() {
        assertEquals("Good morning", BriefingText.greeting(8))
        assertEquals("Good afternoon", BriefingText.greeting(14))
        assertEquals("Good evening", BriefingText.greeting(21))
    }
    @Test fun `denied access never implies zero unread`() {
        assertTrue(BriefingText.notifications(false, false, emptyMap()).contains("couldn't be checked"))
    }
    @Test fun `disconnected listener never implies zero unread`() {
        assertTrue(BriefingText.notifications(true, false, emptyMap()).contains("isn't connected"))
    }
    @Test fun `no notifications does not mean clean inbox`() {
        assertTrue(BriefingText.notifications(true, true, emptyMap()).contains("Unread messages inside apps"))
    }
    @Test fun `counts describe active notifications only`() {
        assertTrue(BriefingText.notifications(true, true, mapOf("com.whatsapp" to 3)).contains("3 active message notification"))
    }
}
