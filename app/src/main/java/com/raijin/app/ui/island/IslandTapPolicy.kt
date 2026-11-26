package com.raijin.app.ui.island

/** Pure decision for an island tap, so it can be unit tested without a Service. */
object IslandTapPolicy {
    enum class Decision { START_LISTENING, STOP_LISTENING, STOP_SPEECH, DISMISS, IGNORE }

    fun decide(state: IslandState, speaking: Boolean): Decision {
        // A tap while Raijin is talking always means "stop talking".
        if (speaking && (state is IslandState.Idle || state is IslandState.Done ||
                state is IslandState.Failed || state is IslandState.MorningBrief ||
                state is IslandState.Undoable)) {
            return Decision.STOP_SPEECH
        }
        return when (state) {
            is IslandState.Idle -> Decision.START_LISTENING
            is IslandState.Listening -> Decision.STOP_LISTENING
            is IslandState.Done, is IslandState.Failed, is IslandState.MorningBrief -> Decision.DISMISS
            else -> Decision.IGNORE
        }
    }
}
