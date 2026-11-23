package com.raijin.app.reminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TM-9: the 30-minute fire-time check window. */
class ConditionalReminderPolicyTest {

    private val now = 1_000_000_000L

    @Test
    fun `no reply seen means the reminder fires`() {
        assertFalse(ConditionalReminderPolicy.shouldSuppressFire(null, now))
    }

    @Test
    fun `reply just now suppresses the fire`() {
        assertTrue(ConditionalReminderPolicy.shouldSuppressFire(now, now))
    }

    @Test
    fun `reply inside the window suppresses the fire`() {
        assertTrue(
            ConditionalReminderPolicy.shouldSuppressFire(
                now - ConditionalReminderPolicy.CHECK_WINDOW_MS + 1,
                now,
            ),
        )
    }

    @Test
    fun `reply at the window edge still suppresses`() {
        assertTrue(
            ConditionalReminderPolicy.shouldSuppressFire(
                now - ConditionalReminderPolicy.CHECK_WINDOW_MS,
                now,
            ),
        )
    }

    @Test
    fun `reply older than the window lets the reminder fire`() {
        assertFalse(
            ConditionalReminderPolicy.shouldSuppressFire(
                now - ConditionalReminderPolicy.CHECK_WINDOW_MS - 1,
                now,
            ),
        )
    }

    @Test
    fun `future timestamp does not suppress`() {
        assertFalse(ConditionalReminderPolicy.shouldSuppressFire(now + 60_000L, now))
    }
}
