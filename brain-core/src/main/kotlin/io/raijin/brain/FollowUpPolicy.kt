package io.raijin.brain

import io.raijin.contract.InputKind
import io.raijin.contract.InputOption

/**
 * UI-7: when Raijin asks a clarifying question, it listens again for
 * [WINDOW_MS] without a tap. Only clarifications qualify. Confirmations,
 * consents, permissions and countdowns never open the mic on their own, so a
 * stray "yes" from the room cannot approve an action.
 */
object FollowUpPolicy {
    const val WINDOW_MS = 6_000L

    fun shouldListen(kind: InputKind): Boolean = kind == InputKind.CLARIFICATION

    /** Question as spoken: includes the choices so the owner can answer by name. */
    fun spokenPrompt(prompt: String, options: List<InputOption>): String {
        val names = options.map { it.label }.filter { it.isNotBlank() }.take(4)
        return if (names.isEmpty()) prompt else "$prompt ${names.joinToString(", ")}?"
    }
}
