package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** STT phase 0: hypothesis selection for the intent layer. */
class IntentAlternativeResolverTest {

    private fun noteProbe() = IntentAlternativeResolver.Probe("obsidian_note") {
        it.lowercase().startsWith("write a note")
    }

    private fun searchProbe() = IntentAlternativeResolver.Probe("search") {
        it.lowercase().startsWith("search for")
    }

    @Test
    fun `empty and blank alternatives yield null`() {
        val r = IntentAlternativeResolver(listOf(noteProbe()))
        assertNull(r.select(emptyList()))
        assertNull(r.select(listOf("  ", " ")))
    }

    @Test
    fun `recognizable primary is never replaced`() {
        val r = IntentAlternativeResolver(listOf(noteProbe()))
        val s = r.select(listOf("write a note about theseus", "right a note about theseus"))!!
        assertEquals("write a note about theseus", s.chosen)
        assertFalse(s.substituted)
        assertEquals("obsidian_note", s.probeId)
    }

    @Test
    fun `homophone error substitutes with marking - the write-right case`() {
        val r = IntentAlternativeResolver(listOf(noteProbe()))
        // Recognizer's primary mishears "write" as "right"; the n-best list
        // carries the correct hypothesis.
        val s = r.select(listOf("right a note about theseus", "write a note about theseus"))!!
        assertTrue(s.substituted)
        assertEquals("right a note about theseus", s.primary)
        assertEquals("write a note about theseus", s.chosen)
        assertEquals("obsidian_note", s.probeId)
    }

    @Test
    fun `no probe hits keeps the primary unmarked`() {
        val r = IntentAlternativeResolver(listOf(noteProbe()))
        val s = r.select(listOf("play some music", "play sum music"))!!
        assertFalse(s.substituted)
        assertEquals("none", s.probeId)
        assertEquals("play some music", s.chosen)
    }

    @Test
    fun `divergent probe hits are ambiguous - no substitution`() {
        val r = IntentAlternativeResolver(listOf(noteProbe(), searchProbe()))
        // "write a note" hits the note probe, "search for x" hits the search
        // probe: genuinely different actions, must not guess.
        assertNull(r.select(listOf("unrecognizable mumble", "write a note", "search for theseus")))
    }

    @Test
    fun `far alternative never hijacks intent even when a probe matches it`() {
        val r = IntentAlternativeResolver(listOf(noteProbe()))
        // Primary is a different sentence entirely; the note-matching
        // alternative is too far to be a recognizer variant of it.
        assertNull(r.select(listOf("play some music for me", "write a note")))
    }

    @Test
    fun `converging probes on one close alternative substitute`() {
        val both = IntentAlternativeResolver.Probe("a") { it.startsWith("write a note") }
        val also = IntentAlternativeResolver.Probe("b") { it.startsWith("write a note") }
        val r = IntentAlternativeResolver(listOf(both, also))
        val s = r.select(listOf("right a note about theseus", "write a note about theseus"))!!
        assertTrue(s.substituted)
        assertEquals("write a note about theseus", s.chosen)
    }

    @Test
    fun `negation flip is never substituted even when close`() {
        val deleteProbe = IntentAlternativeResolver.Probe("delete_note") {
            it.lowercase().startsWith("delete the note")
        }
        val r = IntentAlternativeResolver(listOf(deleteProbe))
        // "don't delete the note" vs "delete the note": close in distance,
        // opposite in meaning. Must refuse (null = keep raw + clarify).
        assertNull(r.select(listOf("don't delete the note", "delete the note")))
    }

    @Test
    fun `number change is never substituted even when close`() {
        val alarmProbe = IntentAlternativeResolver.Probe("alarm") {
            it.lowercase().startsWith("set alarm for")
        }
        val r = IntentAlternativeResolver(listOf(alarmProbe))
        // Primary garbled past the probe; the matching alternative carries a
        // DIFFERENT number. Refuse: a number change is not a homophone fix.
        assertNull(r.select(listOf("said alarm for 7", "set alarm for 11")))
    }

    @Test
    fun `competing executable intents stay ambiguous`() {
        val note = IntentAlternativeResolver.Probe("note") { it.lowercase().startsWith("write a note") }
        val call = IntentAlternativeResolver.Probe("call") { it.lowercase().startsWith("call ") }
        val r = IntentAlternativeResolver(listOf(note, call))
        assertNull(r.select(listOf("garbled primary words", "write a note", "call ma")))
    }

    @Test
    fun `digits in differing tokens block substitution`() {
        assertTrue(IntentAlternativeResolver.diffTouchesDigitsOrNegation("alarm at 7", "alarm at 11"))
        assertTrue(IntentAlternativeResolver.diffTouchesDigitsOrNegation("don't go", "go"))
        assertFalse(IntentAlternativeResolver.diffTouchesDigitsOrNegation("right a note", "write a note"))
    }

    @Test
    fun `close variant gate`() {
        assertTrue(IntentAlternativeResolver.isCloseVariant("right a note", "write a note"))
        assertTrue(IntentAlternativeResolver.isCloseVariant("same", "same"))
        assertFalse(IntentAlternativeResolver.isCloseVariant("play some music for me", "write a note"))
    }
}
