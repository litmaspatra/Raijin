package com.raijin.app.brain

import io.raijin.contract.ActionRisk
import io.raijin.contract.ActionType
import io.raijin.contract.ActiveContext
import io.raijin.contract.CapabilitySnapshot
import io.raijin.contract.DispatchResult
import io.raijin.contract.IslandVisualState
import io.raijin.contract.ProcessingMode
import io.raijin.contract.RaijinBrainBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TEMPORARY app-local stub — implements RaijinBrainBridge for F0/F1 demo/testing.
 *
 * FixtureBrain replays scripted [IslandVisualState] sequences so the UI can be
 * exercised end-to-end without a real brain.  It is NOT a brain-contract type;
 * it lives only in :app and will be deleted when the backend brain is wired.
 *
 * Usage: call [emit] to push any [IslandVisualState] directly
 * (driven by IslandViewModel.playMock for fixture playback).
 */
class FixtureBrain : RaijinBrainBridge {

    private val _islandState = MutableStateFlow<IslandVisualState>(IslandVisualState.Idle)
    override val islandState: StateFlow<IslandVisualState> = _islandState.asStateFlow()

    private val _capabilityState = MutableStateFlow(CapabilitySnapshot())
    override val capabilityState: StateFlow<CapabilitySnapshot> = _capabilityState.asStateFlow()

    /** Emit a state directly — used by IslandViewModel fixture playback. */
    fun emit(state: IslandVisualState) {
        _islandState.value = state
    }

    // ── RaijinBrainBridge — stubs (real routing comes with F1 backend brain) ──

    override suspend fun processUtterance(rawText: String, traceId: String): DispatchResult {
        _islandState.value = IslandVisualState.Thinking(
            taskLabel = "Processing…",
            // PLACEHOLDER: LOCAL_REASONING not yet in contract per PRD §3.4 gap 5.
            // Using LOCAL_CODING until the gap is addressed in a backend ADR.
            mode = ProcessingMode.LOCAL_CODING,
        )
        return DispatchResult(
            traceId    = traceId,
            actionType = ActionType.NONE,
            confidence = 0f,
            risk       = ActionRisk.LOW,
        )
    }

    override suspend fun resolveActionConfirmation(traceId: String, confirmed: Boolean) {
        _islandState.value = if (confirmed) {
            IslandVisualState.Acting("Executing…")
        } else {
            IslandVisualState.Idle
        }
    }

    override suspend fun resolveChoice(traceId: String, optionId: String) {
        _islandState.value = IslandVisualState.Acting("Selecting…")
    }

    override suspend fun cancelCurrentAction(traceId: String) {
        _islandState.value = IslandVisualState.Idle
    }

    override suspend fun resolveClarification(traceId: String, answer: String) {
        // No-op for F0 fixture brain.
    }

    override suspend fun resolveUndo(undoToken: String, accepted: Boolean) {
        // No-op for F0 fixture brain.
    }

    override fun submitContextUpdate(context: ActiveContext) {
        // No-op for F0 fixture brain.
    }

    override fun submitCapabilitySnapshot(snapshot: CapabilitySnapshot) {
        _capabilityState.value = snapshot
    }
}
