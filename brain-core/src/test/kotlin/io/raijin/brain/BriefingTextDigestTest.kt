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

/** NT-5: on-demand notification-question answers. */
class NotificationQueryAnswerTest {

    private val digests = listOf(
        AppDigest(label = "WhatsApp", count = 3, snippet = null),
        AppDigest(label = "MyJio", count = 1, snippet = "You have used 50% of your data plan"),
    )

    @Test fun accessOffIsHonest() {
        val s = BriefingText.notificationQueryAnswer(false, false, digests, null)
        assertTrue(s.contains("Notification access is off"))
    }

    @Test fun disconnectedIsHonest() {
        val s = BriefingText.notificationQueryAnswer(true, false, digests, null)
        assertTrue(s.contains("isn't connected"))
    }

    @Test fun generalQueryUsesFullDigest() {
        val s = BriefingText.notificationQueryAnswer(true, true, digests, null)
        assertTrue(s.contains("WhatsApp: 3 notifications."))
    }

    @Test fun appFilterMatchesLabelCaseInsensitively() {
        val s = BriefingText.notificationQueryAnswer(true, true, digests, "whatsapp")
        assertTrue(s.contains("WhatsApp: 3 notifications."))
        assertFalse(s.contains("MyJio"))
    }

    @Test fun appFilterWithNoMatchIsHonest() {
        val s = BriefingText.notificationQueryAnswer(true, true, digests, "Gmail")
        assertEquals("No active Gmail notifications found. Messages without notifications aren't covered.", s)
    }

    @Test fun suppressedMessagingAppStaysCountOnly() {
        val s = BriefingText.notificationQueryAnswer(true, true, digests, "WhatsApp")
        assertFalse(s.contains("snippet"))
        assertTrue(s.contains("3 notifications"))
    }
}
