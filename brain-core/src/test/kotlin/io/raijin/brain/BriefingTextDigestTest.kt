package io.raijin.brain

import io.raijin.brain.BriefingText.AppDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BriefingTextDigestTest {

    @Test fun accessOffIsHonest() {
        val s = BriefingText.notificationDigest(false, false, emptyList())
        assertTrue(s.contains("Notification access is off"))
    }

    @Test fun disconnectedIsHonest() {
        val s = BriefingText.notificationDigest(true, false, emptyList())
        assertTrue(s.contains("isn't connected"))
    }

    @Test fun emptyIsHonestAboutCoverage() {
        val s = BriefingText.notificationDigest(true, true, emptyList())
        assertTrue(s.contains("No active notifications"))
        assertTrue(s.contains("aren't covered"))
    }

    @Test fun snippetForm() {
        val s = BriefingText.notificationDigest(true, true, listOf(
            AppDigest(label = "MyJio", count = 1, snippet = "You have used 50% of your data plan"),
        ))
        assertEquals("MyJio: You have used 50% of your data plan.", s)
    }

    @Test fun suppressedAppIsCountOnly() {
        val s = BriefingText.notificationDigest(true, true, listOf(
            AppDigest(label = "WhatsApp", count = 2, snippet = null),
        ))
        assertEquals("WhatsApp: 2 notifications.", s)
    }

    @Test fun extraAppsAreCountedHonestly() {
        val digests = (1..5).map { AppDigest(label = "App$it", count = 1, snippet = "update $it") }
        val s = BriefingText.notificationDigest(true, true, digests)
        assertTrue(s.contains("App1: update 1."))
        assertTrue(s.contains("And 2 more app(s) not summarized."))
        assertFalse(s.contains("App4: update 4."))
    }

    @Test fun multiCountWithSnippet() {
        val s = BriefingText.notificationDigest(true, true, listOf(
            AppDigest(label = "MyJio", count = 3, snippet = "You have used 50% of your data plan"),
        ))
        assertEquals("MyJio: You have used 50% of your data plan, plus 2 more.", s)
    }
}
