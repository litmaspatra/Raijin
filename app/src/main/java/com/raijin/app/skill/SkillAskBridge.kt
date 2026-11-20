package com.raijin.app.skill

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * SK-3: routes questions from a running skill (ask_user steps, egress
 * consent prompts, authoring consent) to the owner and the answer back.
 * The service shows the prompt on the island and speaks it; the owner's
 * next utterance - or an island confirm/cancel tap - is the answer. One
 * question at a time. A question never gets a fabricated answer: cancel
 * or timeout ends the skill run fail-closed.
 */
class SkillAskBridge(
    private val timeoutMs: Long = 60_000L,
) {
    @Volatile private var pending: CompletableDeferred<String>? = null

    /** True while a skill question waits for its answer. */
    val isAwaiting: Boolean get() = pending != null

    /**
     * Ask [prompt] and suspend for the owner's answer. [showPrompt] runs on
     * registration (island + TTS). Throws [AskCancelledException] on cancel
     * or timeout; the interpreter turns that into FailedAt with the log.
     */
    suspend fun ask(prompt: String, showPrompt: (String) -> Unit): String {
        check(pending == null) { "another skill question is already pending" }
        val answer = CompletableDeferred<String>()
        pending = answer
        showPrompt(prompt)
        try {
            return withTimeout(timeoutMs) { answer.await() }
        } catch (e: Exception) {
            if (e is AskCancelledException) throw e
            throw AskCancelledException(if (answer.isCancelled) "cancelled" else "no answer in time")
        } finally {
            pending = null
        }
    }

    /**
     * Route an utterance into the pending question. Returns true when the
     * utterance was consumed as an answer.
     */
    fun tryAnswer(utterance: String): Boolean = pending?.complete(utterance) == true

    /** Island confirm tap: completes with an affirmative answer. */
    fun confirm(): Boolean = tryAnswer("yes")

    /** Owner dismissed the question; the run fails closed. */
    fun cancel() {
        pending?.cancel()
        pending = null
    }

    class AskCancelledException(message: String) : Exception(message)
}
