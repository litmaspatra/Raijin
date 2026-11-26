package com.raijin.app.ui.island

import com.raijin.app.ui.island.IslandTapPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class IslandTapPolicyTest {
    @Test fun tapDuringDoneSpeechStopsSpeech() =
        assertEquals(Decision.STOP_SPEECH, IslandTapPolicy.decide(IslandState.Done("ok"), true))

    @Test fun tapDuringFailedSpeechStopsSpeech() =
        assertEquals(Decision.STOP_SPEECH, IslandTapPolicy.decide(IslandState.Failed("no"), true))

    @Test fun tapOnIdleWhileStillSpeakingStopsSpeechNotListening() =
        assertEquals(Decision.STOP_SPEECH, IslandTapPolicy.decide(IslandState.Idle, true))

    @Test fun tapOnIdleSilentStartsListening() =
        assertEquals(Decision.START_LISTENING, IslandTapPolicy.decide(IslandState.Idle, false))

    @Test fun tapOnListeningStopsListening() =
        assertEquals(Decision.STOP_LISTENING, IslandTapPolicy.decide(IslandState.Listening, false))

    @Test fun tapOnDoneSilentDismisses() =
        assertEquals(Decision.DISMISS, IslandTapPolicy.decide(IslandState.Done("ok"), false))

    @Test fun needsInputTapIsIgnoredEvenIfSpeaking() {
        val s = IslandState.NeedsInput(label = "l", question = "q", traceId = "t", actionId = "a")
        assertEquals(Decision.IGNORE, IslandTapPolicy.decide(s, true))
    }
}
