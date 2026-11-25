package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BR-9: per-request relevant-context selection for cloud reasoning.
 * History is in-memory only; follow-ups carry up to the last 3 session
 * turns; anything uncertain sends the current message only.
 */
class SessionContextTest {

    private fun turn(user: String, response: String = "ok") =
        SessionTurn(user, response, 0L)

    // ---- SessionTurnLog ---------------------------------------------------

    @Test
    fun logKeepsNewestUpToCap() {
        val log = SessionTurnLog(cap = 3)
        for (i in 1..5) log.record(turn("u$i"))
        assertEquals(listOf("u3", "u4", "u5"), log.recent(10).map { it.userText })
        assertEquals(3, log.size)
    }

    @Test
    fun recentReturnsOldestToNewestLimitedToN() {
        val log = SessionTurnLog()
        for (i in 1..5) log.record(turn("u$i"))
        assertEquals(listOf("u3", "u4", "u5"), log.recent(3).map { it.userText })
        assertEquals(listOf("u5"), log.recent(1).map { it.userText })
    }

    @Test
    fun clearEmptiesTheLog() {
        val log = SessionTurnLog()
        log.record(turn("u1"))
        log.clear()
        assertEquals(0, log.size)
        assertTrue(log.recent(3).isEmpty())
    }

    // ---- FollowUpDetector -------------------------------------------------

    @Test
    fun clearFollowUpsAreDetected() {
        val followUps = listOf(
            "what about tomorrow?",
            "What about the weekend?",
            "how about friday",
            "and the next day?",
            "why is that?",
            "how much will it cost?",
            "tell me more about it",
            "is that true",
            "what about them",
        )
        for (q in followUps) assertTrue(FollowUpDetector.isFollowUp(q), q)
    }

    @Test
    fun standaloneQuestionsAreNotFollowUps() {
        val standalone = listOf(
            "What is quantum entanglement?",
            "What is the capital of France?",
            "Tell me a joke",
            "How far is the moon from earth",
            "nice",
            "okay cool",
            "Draft a speech",
        )
        for (q in standalone) assertFalse(FollowUpDetector.isFollowUp(q), q)
    }

    // ---- SessionContextPrompt ----------------------------------------------

    @Test
    fun followUpWithHistoryCarriesTheLastThreeTurns() {
        val log = SessionTurnLog()
        for (i in 1..5) log.record(turn("question $i", "answer $i"))
        val prompt = SessionContextPrompt.build("what about tomorrow?", log)
        assertTrue(prompt.startsWith("Recent conversation:\n"), prompt)
        assertFalse(prompt.contains("question 1"), prompt)
        assertFalse(prompt.contains("question 2"), prompt)
        assertTrue(prompt.contains("User: question 3"), prompt)
        assertTrue(prompt.contains("Raijin: answer 5"), prompt)
        assertTrue(prompt.endsWith("Current message: what about tomorrow?"), prompt)
    }

    @Test
    fun nonFollowUpSendsTheCurrentMessageByteIdentical() {
        val log = SessionTurnLog()
        log.record(turn("secret earlier question", "some answer"))
        val text = "What is the capital of France?"
        assertEquals(text, SessionContextPrompt.build(text, log))
    }

    @Test
    fun followUpWithEmptyHistorySendsTheCurrentMessageByteIdentical() {
        val text = "what about tomorrow?"
        assertEquals(text, SessionContextPrompt.build(text, SessionTurnLog()))
    }

    // ---- SessionHistoryClear ----------------------------------------------

    @Test
    fun clearPhrasesMatchExactly() {
        assertTrue(SessionHistoryClear.matches("clear history"))
        assertTrue(SessionHistoryClear.matches("Clear History!"))
        assertTrue(SessionHistoryClear.matches("forget this conversation."))
        assertFalse(SessionHistoryClear.matches("clear my browser history"))
        assertFalse(SessionHistoryClear.matches("what is my history"))
    }
}
