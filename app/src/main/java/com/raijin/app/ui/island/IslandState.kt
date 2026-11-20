package com.raijin.app.ui.island

// ── Island action (button in NeedsInput) ─────────────────────────────────
data class IslandAction(
    val label: String,
    val payload: String,
    val isPrimary: Boolean = false,
    val isDangerous: Boolean = false,
)

// ── Trace line (shown during Resolving) ──────────────────────────────────
data class TraceLine(
    val text: String,
    val isConclusion: Boolean = false,
)

// ── Island state hierarchy ────────────────────────────────────────────────
sealed class IslandState {

    /** Tiny breathing pill · no active task */
    object Idle : IslandState()

    /** Expanded · listening for voice input */
    object Listening : IslandState()

    /** Expanded · showing reasoning trace lines */
    data class Resolving(
        val label: String = "Thinking…",
        val traceLines: List<TraceLine> = emptyList(),
    ) : IslandState()

    /** Expanded · single-line thinking label */
    data class Thinking(
        val label: String,
    ) : IslandState()

    /** Expanded · action in progress */
    data class Acting(
        val label: String,
        val subLabel: String? = null,
    ) : IslandState()

    /** Expanded · requires user decision */
    data class NeedsInput(
        val label: String,
        val question: String,
        val context: String? = null,
        val confirmLabel: String = "Confirm",
        val cancelLabel: String = "Cancel",
        val isDangerous: Boolean = false,
        // False for option-less clarifications: the answer is spoken/typed,
        // so no affirmative control is rendered (Cancel remains).
        val confirmEnabled: Boolean = true,
        val traceId: String,
        val actionId: String,
    ) : IslandState()

    /** Briefly expanded green flash, then shrinks */
    data class Done(
        val label: String,
        val autoHideMs: Long = 2200L,
    ) : IslandState()

    /** Red pulse + reason, then shrinks */
    data class Failed(
        val reason: String,
        val autoHideMs: Long = 3000L,
    ) : IslandState()

    /** Proxy call in progress · live transcript */
    data class ProxyCall(
        val contactName: String,
        val liveTranscript: String = "",
        val isListening: Boolean = false,
    ) : IslandState()

    /** Morning brief · multi-line summary */
    data class MorningBrief(
        val lines: List<String>,
    ) : IslandState()

    /** UI-5: long answer · expandable card, scrollable, tap to dismiss */
    data class LongAnswer(
        val text: String,
    ) : IslandState()
}

// ── Width each state requests (dp) ──────────────────────────────────────
fun IslandState.targetWidthDp(): Int = when (this) {
    // Collapsed pill should read as a system-island-sized chip, not a banner.
    is IslandState.Idle          -> 88
    is IslandState.Listening     -> 200
    is IslandState.Done          -> if (label.length > 48) 310 else 180
    is IslandState.Resolving,
    is IslandState.Thinking,
    is IslandState.Acting,
    is IslandState.NeedsInput,
    is IslandState.Failed,
    is IslandState.ProxyCall,
    is IslandState.MorningBrief,
    is IslandState.LongAnswer   -> 310
}
