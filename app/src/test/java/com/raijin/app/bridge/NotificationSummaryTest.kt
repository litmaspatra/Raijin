package com.raijin.app.bridge

import android.app.Notification
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSummaryTest {

    private fun posted(pkg: String, id: Int, text: String, title: String = "App"): StatusBarNotification {
        val n = Notification().apply {
            extras = android.os.Bundle().apply {
                putString("android.title", title)
                putString("android.text", text)
            }
        }
        return StatusBarNotification(pkg, pkg, id, null, 0, 0, 0, n, UserHandle.getUserHandleForUid(0), System.currentTimeMillis())
    }

    // ── sanitizer ─────────────────────────────────────────────────────────

    @Test fun otpIsFullySuppressed() {
        assertNull(NotificationSanitizer.sanitize("Your Jio OTP is 482913. Do not share it."))
        assertNull(NotificationSanitizer.sanitize("Verification code: 123456"))
        assertNull(NotificationSanitizer.sanitize("Use 4829 as your one time passcode"))
    }

    @Test fun transactionalSnippetIsKept() {
        assertEquals(
            "You have used 50% of your data plan",
            NotificationSanitizer.sanitize("You have used 50% of your data plan"),
        )
    }

    @Test fun urlsEmailsPhonesAreRedacted() {
        assertEquals(
            "Track your order at [link] today",
            NotificationSanitizer.sanitize("Track your order at https://example.com/o/123 today"),
        )
        assertEquals(
            "Write to [email] for help",
            NotificationSanitizer.sanitize("Write to support@example.com for help"),
        )
        assertEquals(
            "Call [number] now",
            NotificationSanitizer.sanitize("Call +91 98765 43210 now"),
        )
    }

    @Test fun longTextIsTruncated() {
        val long = "word ".repeat(40).trim()
        val out = NotificationSanitizer.sanitize(long)!!
        assertEquals(true, out.length <= NotificationSanitizer.MAX_SNIPPET_LEN)
        assertEquals(true, out.endsWith("..."))
    }

    @Test fun blankBodyIsNull() {
        assertNull(NotificationSanitizer.sanitize("   "))
    }

    // ── cache / digests ───────────────────────────────────────────────────

    @Test fun transactionalAppGetsSnippetDigest() {
        val listener = NotificationListenerService()
        listener.onNotificationPosted(posted("com.myjio", 9001, "You have used 50% of your data plan", "MyJio"))
        val digest = NotificationListenerService.cachedAppDigests.single { it.label == "com.myjio" }
        assertEquals("50% of data used", digest.snippet)
        assertEquals(io.raijin.brain.BriefingText.SummaryKind.EXTRACTION, digest.kind)
        assertEquals(1, digest.count)
    }

    @Test fun messagingAppBodyIsSuppressedCountOnly() {
        val listener = NotificationListenerService()
        listener.onNotificationPosted(posted("com.whatsapp", 9002, "see you at 7", "Aisha"))
        val digest = NotificationListenerService.cachedAppDigests.single { it.label == "com.whatsapp" }
        assertNull(digest.snippet)
        assertEquals(1, digest.count)
    }

    @Test fun updatedNotificationReplacesOldSnippet() {
        val listener = NotificationListenerService()
        listener.onNotificationPosted(posted("com.myjio", 9003, "You have used 50% of your data plan"))
        listener.onNotificationPosted(posted("com.myjio", 9003, "You have used 60% of your data plan"))
        val digests = NotificationListenerService.cachedAppDigests.filter { it.label == "com.myjio" }
        assertEquals(1, digests.size)
        assertEquals("60% of data used", digests.single().snippet)
    }

    // ── deterministic alert extraction ────────────────────────────────────

    @Test fun dataUsageExtraction() {
        assertEquals("50% of data used", NotificationSanitizer.extractAlert("You have used 50% of your data plan"))
        assertEquals("80% of data used", NotificationSanitizer.extractAlert("80% of your quota is exhausted"))
    }

    @Test fun dataRemainingExtraction() {
        assertEquals("2 GB of data left", NotificationSanitizer.extractAlert("Only 2 GB left in your plan"))
        assertEquals("1.5 GB of data left", NotificationSanitizer.extractAlert("1.5 GB of data remaining"))
    }

    @Test fun deliveryExtraction() {
        assertEquals("Package out for delivery", NotificationSanitizer.extractAlert("Your order is out for delivery today"))
        assertEquals("Package delivered", NotificationSanitizer.extractAlert("Your package was delivered"))
        assertEquals("Arriving tomorrow", NotificationSanitizer.extractAlert("Your order is arriving tomorrow"))
    }

    @Test fun unrecognizedAlertFallsBackToPreview() {
        assertEquals(null, NotificationSanitizer.extractAlert("Team standup moved to 5 pm"))
        val listener = NotificationListenerService()
        listener.onNotificationPosted(posted("com.example.news", 9004, "Team standup moved to 5 pm"))
        val digest = NotificationListenerService.cachedAppDigests.single { it.label == "com.example.news" }
        assertEquals("Team standup moved to 5 pm", digest.snippet)
        assertEquals(io.raijin.brain.BriefingText.SummaryKind.PREVIEW, digest.kind)
    }
}
