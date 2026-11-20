package io.raijin.brain.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.time.ZoneId

class InteractionPatternsTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val dayMillis = 24L * 60 * 60 * 1000
    // 2026-11-18 12:00 IST as "now"
    private val now = Z(2026, 11, 18, 12, 0)

    private fun Z(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        java.time.ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(hour: Int, daysAgo: Int, sig: String) =
        InteractionPatterns.Interaction(now - daysAgo * dayMillis + (hour - 12) * 3600_000L, sig)

    @Test
    fun `five or more in the same band become a pattern`() {
        val interactions = (1..5).map { at(8, it, "set_alarm:6:30") }
        val patterns = InteractionPatterns.findPatterns(interactions, now, zone)
        assertEquals(1, patterns.size)
        assertEquals("set_alarm:#:#", patterns[0].signature)
        assertEquals(InteractionPatterns.DayPart.MORNING, patterns[0].dayPart)
        assertEquals(5, patterns[0].count)
    }

    @Test
    fun `four in a band stays below the threshold`() {
        val interactions = (1..4).map { at(8, it, "set_alarm:6:30") }
        assertTrue(InteractionPatterns.findPatterns(interactions, now, zone).isEmpty())
    }

    @Test
    fun `different bands do not merge`() {
        val morning = (1..3).map { at(8, it, "play_music") }
        val night = (1..3).map { at(23, it, "play_music") }
        assertTrue(InteractionPatterns.findPatterns(morning + night, now, zone).isEmpty())
    }

    @Test
    fun `digits normalize so different times are one habit`() {
        val interactions = listOf(
            at(7, 1, "set_alarm:6:30"), at(7, 2, "set_alarm:7:00"),
            at(7, 3, "set_alarm:6:45"), at(7, 4, "set_alarm:7:15"),
            at(7, 5, "set_alarm:6:00"),
        )
        val patterns = InteractionPatterns.findPatterns(interactions, now, zone)
        assertEquals(1, patterns.size)
        assertEquals(5, patterns[0].count)
    }

    @Test
    fun `interactions older than the window are ignored`() {
        val recent = (1..2).map { at(8, it, "call_contact:m paa") }
        val old = (31..33).map { at(8, it, "call_contact:m paa") }
        assertTrue(InteractionPatterns.findPatterns(recent + old, now, zone).isEmpty())
    }

    @Test
    fun `description is owner-facing`() {
        val interactions = (1..5).map { at(22, it, "enable_dnd") }
        val pattern = InteractionPatterns.findPatterns(interactions, now, zone).single()
        assertEquals("You often enable dnd at night (5 times)", pattern.describe())
    }

    @Test
    fun `day part boundaries`() {
        assertEquals(InteractionPatterns.DayPart.NIGHT, InteractionPatterns.dayPartOf(Z(2026, 11, 1, 4, 0), zone))
        assertEquals(InteractionPatterns.DayPart.MORNING, InteractionPatterns.dayPartOf(Z(2026, 11, 1, 5, 0), zone))
        assertEquals(InteractionPatterns.DayPart.AFTERNOON, InteractionPatterns.dayPartOf(Z(2026, 11, 1, 12, 0), zone))
        assertEquals(InteractionPatterns.DayPart.EVENING, InteractionPatterns.dayPartOf(Z(2026, 11, 1, 17, 0), zone))
        assertEquals(InteractionPatterns.DayPart.NIGHT, InteractionPatterns.dayPartOf(Z(2026, 11, 1, 22, 0), zone))
    }

    @Test
    fun `minCount below two rejected`() {
        assertFailsWith<IllegalArgumentException> {
            InteractionPatterns.findPatterns(emptyList(), now, zone, minCount = 1)
        }
    }
}
