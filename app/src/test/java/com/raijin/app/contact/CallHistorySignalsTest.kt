package com.raijin.app.contact

import com.raijin.app.contact.CallHistorySignals.CallRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallHistorySignalsTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test fun keyMatchesAcrossFormats() {
        assertEquals(CallHistorySignals.key("+91 98765 43210"), CallHistorySignals.key("09876543210"))
        assertEquals("9876543210", CallHistorySignals.key("+919876543210"))
    }

    @Test fun countsOnlyLast30DaysAndKeepsLatest() {
        val rows = listOf(
            CallRow("+919876543210", now - 1 * day),
            CallRow("09876543210", now - 10 * day),
            CallRow("9876543210", now - 60 * day),
        )
        val s = CallHistorySignals.buildSignals(rows, now, nowHour = 9)["9876543210"]!!
        assertEquals(2, s.callCount30d)
        assertEquals((now - 1 * day) / 1000L, s.lastContactEpochSec)
    }

    @Test fun ignoresTooShortNumbers() {
        assertNull(CallHistorySignals.buildSignals(listOf(CallRow("123", now)), now, 9)["123"])
    }

    @Test fun daypartBuckets() {
        assertEquals(0, CallHistorySignals.daypartOf(8))
        assertEquals(1, CallHistorySignals.daypartOf(14))
        assertEquals(2, CallHistorySignals.daypartOf(19))
        assertEquals(3, CallHistorySignals.daypartOf(23))
        assertEquals(3, CallHistorySignals.daypartOf(2))
    }
}
