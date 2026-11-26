package com.raijin.app.reminder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ReminderListingTest {
    private fun r(id: Long, t: Long?, fired: Int = 0, rule: String? = null, cond: String? = null) =
        ReminderEntity(id = id, text = "r$id", triggerTime = t, recurrenceRule = rule,
            condition = cond, fired = fired, createdAt = 0)

    @Test fun pendingSortsSoonestFirstAndDropsFiredAndUntimed() {
        val out = ReminderListing.pending(listOf(r(1, 300), r(2, 100), r(3, 200, fired = 1), r(4, null)))
        assertEquals(listOf(2L, 1L), out.map { it.id })
    }

    @Test fun cancelledReminderLeavesList() {
        val all = listOf(r(1, 100), r(2, 200))
        val after = all.map { if (it.id == 1L) it.copy(fired = 1) else it }
        assertEquals(listOf(2L), ReminderListing.pending(after).map { it.id })
    }

    @Test fun detailShowsTimeRepeatAndCondition() {
        val utc = ZoneId.of("UTC")
        assertEquals("Thu 1 Jan, 12:00 AM", ReminderListing.detail(r(1, 0), utc))
        assertEquals("Thu 1 Jan, 12:00 AM - repeats daily",
            ReminderListing.detail(r(1, 0, rule = "DAILY"), utc))
        assertEquals("Thu 1 Jan, 12:00 AM - if Rahul doesn't reply",
            ReminderListing.detail(r(1, 0, cond = "if Rahul doesn't reply"), utc))
    }
}
