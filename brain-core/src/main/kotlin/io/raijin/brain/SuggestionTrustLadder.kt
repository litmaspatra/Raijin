package io.raijin.brain

/**
 * NT-4 trust ladder (PRD 7.8). Pure Kotlin, JVM-testable (PRD principle 7);
 * the Android side persists the state and surfaces the offers, this class
 * owns the decisions.
 *
 * Flow per pattern (e.g. BILL, FLIGHT, MEETING from the notification
 * classifier):
 *   1. Every detected event starts as a SUGGESTION the owner must accept
 *      (NT-3). Nothing is auto-created.
 *   2. After [threshold] accepted suggestions of the same pattern, the owner
 *      is OFFERED "auto-create these with undo" — once per crossing.
 *   3. Auto-create runs only with an explicit grant, and always with undo
 *      (the caller wires the undo chip). A denial is sticky: the ladder
 *      never re-offers on its own; the owner can still change the setting.
 *
 * Fail-safe defaults: unknown patterns suggest; a pattern the owner denied
 * keeps suggesting forever unless the owner explicitly grants later.
 */
class SuggestionTrustLadder(
    private val threshold: Int = 3,
) {
    init {
        require(threshold >= 1) { "threshold must be >= 1" }
    }

    data class PatternState(
        val acceptedCount: Int = 0,
        val offerMade: Boolean = false,
        /** null = undecided, true = granted, false = denied. */
        val autoCreateGranted: Boolean? = null,
    )

    sealed interface Decision {
        /** Show the suggestion chip; the owner must accept (NT-3). */
        data object Suggest : Decision

        /** Auto-create is granted for this pattern; create with undo shown. */
        data object AutoCreateWithUndo : Decision
    }

    /** What to do when an event of this pattern is detected. */
    fun decisionFor(state: PatternState?): Decision =
        if (state?.autoCreateGranted == true) Decision.AutoCreateWithUndo else Decision.Suggest

    /**
     * The owner accepted a suggestion. Returns the new state and whether the
     * auto-create offer should be shown now (exactly once, at the crossing).
     */
    fun onAccepted(state: PatternState?): Pair<PatternState, Boolean> {
        val s = state ?: PatternState()
        val newCount = s.acceptedCount + 1
        val crossed = newCount >= threshold && !s.offerMade
        return s.copy(acceptedCount = newCount, offerMade = s.offerMade || crossed) to crossed
    }

    /** The owner dismissed or declined a suggestion: no count change. */
    fun onDeclined(state: PatternState?): PatternState = state ?: PatternState()

    /**
     * The owner answered the auto-create offer. Both answers are sticky: a
     * grant enables auto-create, a denial keeps suggestion mode and the
     * ladder never re-offers by itself.
     */
    fun onAutoCreateAnswer(state: PatternState?, granted: Boolean): PatternState =
        (state ?: PatternState()).copy(autoCreateGranted = granted, offerMade = true)

    /** Owner-initiated setting change (Settings screen); always honored. */
    fun ownerSetAutoCreate(state: PatternState?, granted: Boolean): PatternState =
        (state ?: PatternState()).copy(autoCreateGranted = granted, offerMade = true)
}
