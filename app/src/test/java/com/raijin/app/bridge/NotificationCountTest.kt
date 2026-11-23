package com.raijin.app.bridge

import android.app.Notification
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationCountTest {
    private fun posted(id: Int, summary: Boolean = false): StatusBarNotification {
        val n = Notification().apply {
            extras = android.os.Bundle().apply {
                putString("android.title", "Test sender")
                putString("android.text", "Hello")
            }
            if (summary) flags = flags or Notification.FLAG_GROUP_SUMMARY
        }
        return StatusBarNotification("com.whatsapp", "com.whatsapp", id, null, 0, 0, 0, n, UserHandle.getUserHandleForUid(0), System.currentTimeMillis())
    }
    @Test fun repeatedUpdatesAreNotNewUnreadMessages() {
        val listener = NotificationListenerService()
        val n = posted(7654)
        listener.onNotificationPosted(n)
        listener.onNotificationPosted(n)
        assertEquals(1, NotificationListenerService.cachedUnread["com.whatsapp"])
        listener.onNotificationRemoved(n)
        assertEquals(null, NotificationListenerService.cachedUnread["com.whatsapp"])
    }
    @Test fun groupSummaryIsNotAnExtraMessage() {
        val listener = NotificationListenerService()
        listener.onNotificationPosted(posted(7655, true))
        assertEquals(null, NotificationListenerService.cachedUnread["com.whatsapp"])
    }
}
