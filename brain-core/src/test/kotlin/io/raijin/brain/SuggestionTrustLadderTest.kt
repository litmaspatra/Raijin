package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** NT-4: suggestion-first, offer once at the crossing, grant-only auto-create. */
class SuggestionTrustLadderTest {

    private val ladder = SuggestionTrustLadder() // default threshold 3

    @Test
    fun `unknown pattern starts as suggestion`() {
        assertEquals(
            SuggestionTrustLadder.Decision.Suggest,
            ladder.decisionFor(null),
        )
    }

    @Test
    fun `offer fires exactly once at the threshold crossing`() {
        var state: SuggestionTrustLadder.PatternState? = null
        val (s1, offer1) = ladder.onAccepted(state)
        assertFalse(offer1)
        val (s2, offer2) = ladder.onAccepted(s1)
        assertFalse(offer2)
        val (s3, offer3) = ladder.onAccepted(s2)
        assertTrue(offer3, "third acceptance crosses the default threshold")
        // Further acceptances never re-offer.
        val (_, offer4) = ladder.onAccepted(s3)
        assertFalse(offer4)
    }

    @Test
    fun `auto-create only after explicit grant`() {
        var state: SuggestionTrustLadder.PatternState? = null
        repeat(3) { state = ladder.onAccepted(state).first }
        // Threshold crossed but no answer yet: still suggest.
        assertEquals(SuggestionTrustLadder.Decision.Suggest, ladder.decisionFor(state))

        state = ladder.onAutoCreateAnswer(state, granted = true)
        assertEquals(SuggestionTrustLadder.Decision.AutoCreateWithUndo, ladder.decisionFor(state))
    }

    @Test
    fun `denial is sticky and never re-offered`() {
        var state: SuggestionTrustLadder.PatternState? = null
        repeat(3) { state = ladder.onAccepted(state).first }
        state = ladder.onAutoCreateAnswer(state, granted = false)
        assertEquals(SuggestionTrustLadder.Decision.Suggest, ladder.decisionFor(state))

        // Many more acceptances: still no second offer.
        repeat(10) {
            val (s, offer) = ladder.onAccepted(state)
            state = s
            assertFalse(offer)
        }
        assertEquals(SuggestionTrustLadder.Decision.Suggest, ladder.decisionFor(state))
    }

    @Test
    fun `declined suggestions do not count toward the threshold`() {
        var state: SuggestionTrustLadder.PatternState? = null
        state = ladder.onDeclined(state)
        state = ladder.onDeclined(state)
        val (_, offer) = ladder.onAccepted(state)
        assertFalse(offer, "two declines then one accept must not cross threshold 3")
    }

    @Test
    fun `owner setting change overrides in both directions`() {
        var state = ladder.ownerSetAutoCreate(null, granted = true)
        assertEquals(SuggestionTrustLadder.Decision.AutoCreateWithUndo, ladder.decisionFor(state))
        state = ladder.ownerSetAutoCreate(state, granted = false)
        assertEquals(SuggestionTrustLadder.Decision.Suggest, ladder.decisionFor(state))
    }

    @Test
    fun `custom threshold respected`() {
        val strict = SuggestionTrustLadder(threshold = 1)
        val (_, offer) = strict.onAccepted(null)
        assertTrue(offer, "threshold 1 offers on the first acceptance")
    }
}
