package com.raijin.app.ui

/** UI-8: builds the command from what the owner typed plus the text shared into Raijin. */
object ShareCompose {
    const val MAX_SHARED_CHARS = 2_000

    /** Shared text trimmed and capped; null when there is nothing usable. */
    fun clean(shared: CharSequence?): String? =
        shared?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_SHARED_CHARS)

    /** With no instruction the shared text alone is sent, like a typed command. */
    fun compose(instruction: String, shared: String?): String {
        val i = instruction.trim()
        return when {
            shared == null -> i
            i.isEmpty() -> shared
            else -> "$i: $shared"
        }
    }

    fun canSend(instruction: String, shared: String?): Boolean = instruction.isNotBlank() || shared != null
}
