package com.raijin.app.bridge

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NT-2 classifier coverage for the deterministic notification parser.
 * Pins the fail-safe rules: non-clock numbers never become reminder times,
 * and "tomorrow" wins over the same clock time later today.
 */
class ProactiveNotificationParserTest {

    private lateinit var previousZone: TimeZone

    @Before
    fun pinZone() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(previousZone)
    }

    /** 2026-11-23 13:00 IST. */
    private val nowMs: Long = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
        set(2026, Calendar.NOVEMBER, 23, 13, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun expectedAt(dayOffset: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            set(2026, Calendar.NOVEMBER, 23 + dayOffset, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `relative time in minutes`() {
        val e = ProactiveNotificationParser.parse(
            "Rahul", "call me in 30 mins about the meeting", nowMs,
        )
        assertEquals("MEETING", e?.category)
        assertEquals(nowMs + 30 * 60_000L, e?.triggerEpochMs)
    }

    @Test
    fun `relative time in hours`() {
        val e = ProactiveNotificationParser.parse(
            "Reminder", "don't forget the deadline in 2 hours", nowMs,
        )
        assertEquals(nowMs + 2 * 3600_000L, e?.triggerEpochMs)
    }

    @Test
    fun `absolute time later today`() {
        val e = ProactiveNotificationParser.parse(
            "Calendar", "interview at 5:30 pm", nowMs,
        )
        assertEquals(expectedAt(0, 17, 30), e?.triggerEpochMs)
    }

    @Test
    fun `absolute time already passed rolls to tomorrow`() {
        val e = ProactiveNotificationParser.parse(
            "Calendar", "meeting at 9 am", nowMs,
        )
        assertEquals(expectedAt(1, 9, 0), e?.triggerEpochMs)
    }

    @Test
    fun `tomorrow with explicit time means tomorrow even if time is ahead today`() {
        // Regression: "tomorrow" was ignored whenever a clock time matched.
        val e = ProactiveNotificationParser.parse(
            "Calendar", "meeting tomorrow at 5 pm", nowMs,
        )
        assertEquals("MEETING", e?.category)
        assertEquals(expectedAt(1, 17, 0), e?.triggerEpochMs)
    }

    @Test
    fun `tomorrow without time defaults to 9 am`() {
        val e = ProactiveNotificationParser.parse(
            "Todo", "deadline tomorrow", nowMs,
        )
        assertEquals(expectedAt(1, 9, 0), e?.triggerEpochMs)
    }

    @Test
    fun `due date numbers are not clock hours`() {
        // Regression: "28" in a due date was fed to Calendar as hour 28 and
        // rolled over leniently into a garbage reminder time.
        val e = ProactiveNotificationParser.parse(
            "Electricity", "electricity bill due 28/11, pay by 5 pm", nowMs,
        )
        assertEquals("BILL", e?.category)
        assertEquals(expectedAt(0, 17, 0), e?.triggerEpochMs)
    }

    @Test
    fun `invalid minutes are rejected`() {
        val e = ProactiveNotificationParser.parse(
            "PNR status", "flight 6E 2345 gate 12:75 boarding", nowMs,
        )
        // 12:75 is not a clock time; falls back to the +2h default.
        assertEquals("FLIGHT", e?.category)
        assertEquals(nowMs + 2 * 3600_000L, e?.triggerEpochMs)
    }

    @Test
    fun `bare number over 23 without meridiem is not a clock hour`() {
        val e = ProactiveNotificationParser.parse(
            "Airline", "flight AI 454 boarding at gate 9", nowMs,
        )
        // "454" contains no boundary-delimited 2-digit hour; "9" is valid and
        // already passed at 13:00, so it rolls to tomorrow 9:00.
        assertEquals(expectedAt(1, 9, 0), e?.triggerEpochMs)
    }

    @Test
    fun `unrelated notification returns null`() {
        assertNull(ProactiveNotificationParser.parse("WhatsApp", "ok see you", nowMs))
        assertNull(ProactiveNotificationParser.parse("Bank", "Your OTP is 123456", nowMs))
    }

    @Test
    fun `midnight and noon edge cases`() {
        val midnight = ProactiveNotificationParser.parse("Cal", "meeting at 12 am tomorrow", nowMs)
        assertEquals(expectedAt(1, 0, 0), midnight?.triggerEpochMs)
        val noon = ProactiveNotificationParser.parse("Cal", "meeting at 12 pm", nowMs)
        // 12:00 already passed at 13:00, so it rolls to tomorrow noon.
        assertEquals(expectedAt(1, 12, 0), noon?.triggerEpochMs)
    }

    @Test
    fun `titles are sanitized per category`() {
        val e = ProactiveNotificationParser.parse(
            "Airline", "flight delayed, new departure at 6 pm", nowMs,
        )
        assertTrue(e?.title?.startsWith("Flight Update:") == true)
    }
}
