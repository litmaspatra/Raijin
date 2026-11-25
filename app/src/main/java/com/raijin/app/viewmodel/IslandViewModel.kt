package com.raijin.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raijin.app.brain.FixtureBrain
import com.raijin.app.ui.island.IslandState
import com.raijin.app.ui.island.TraceLine
import io.raijin.contract.ActionRisk
import io.raijin.contract.InputKind
import io.raijin.contract.IslandVisualState
import io.raijin.contract.RaijinBrainBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

// ── Mock scenarios ────────────────────────────────────────────────────────
// Fixture JSON files drive FixtureBrain which emits IslandVisualState; the
// ViewModel maps those to the app-local IslandState for the composable.
enum class MockScenario(val assetFile: String) {
    RESOLVING        ("fixtures/demo_resolving.json"),
    NEEDS_INPUT      ("fixtures/demo_needs_input.json"),
    DANGEROUS        ("fixtures/demo_dangerous.json"),
    DONE             ("fixtures/demo_done.json"),
    FAILED           ("fixtures/demo_failed.json"),
    MORNING          ("fixtures/demo_morning_brief.json"),
    REMINDER_SET     ("fixtures/demo_reminder_set.json"),
    REMINDER_FIRED   ("fixtures/demo_reminder_fired.json"),
    SKILL_LEARNED    ("fixtures/demo_skill_learned.json"),
}

/** Marker interface so tests can share parseFixtureStates logic without an Application. */
interface FixtureParser

class IslandViewModel @JvmOverloads constructor(
    app: Application,
    /** Injected in tests; defaults to FixtureBrain for F0. */
    private val brain: RaijinBrainBridge = FixtureBrain(),
) : AndroidViewModel(app) {

    companion object {
        private const val TAG           = "IslandViewModel"
        private const val MOCK_DELAY_MS = 1_400L
    }

    // ── Island state ──────────────────────────────────────────────────────────
    private val _island = MutableStateFlow<IslandState>(IslandState.Idle)
    val island: StateFlow<IslandState> = _island.asStateFlow()

    @Suppress("unused") // kept for fixture playback compat
    private var autoHideJob: Job? = null

    init {
        // Collect brain's IslandVisualState and map to app-local IslandState
        viewModelScope.launch {
            brain.islandState.collect { visualState ->
                _island.value = visualState.toIslandState()
            }
        }
    }

    // ── Tap to wake ────────────────────────────────────────────────────────────
    fun onTap() {
        when (_island.value) {
            is IslandState.Idle      -> _island.value = IslandState.Listening
            is IslandState.Listening -> _island.value = IslandState.Idle
            else                     -> { /* ignore taps mid-action */ }
        }
    }

    // ── User response (NeedsInput confirm/cancel) ─────────────────────────────
    fun onUserResponse(traceId: String, @Suppress("UNUSED_PARAMETER") actionId: String, payload: String) {
        viewModelScope.launch {
            if (payload == "confirm") {
                brain.resolveActionConfirmation(traceId, confirmed = true)
            } else {
                brain.resolveActionConfirmation(traceId, confirmed = false)
            }
        }
    }

    // ── Mock scenario playback (demo / dev) ───────────────────────────────────
    fun playMock(scenario: MockScenario) {
        viewModelScope.launch {
            val states = loadFixtureStates(scenario.assetFile) ?: return@launch
            for (state in states) {
                _island.value = state
                delay(MOCK_DELAY_MS)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun loadFixtureStates(assetPath: String): List<IslandState>? {
        return try {
            val ctx: android.content.Context = getApplication()
            val json = ctx.assets.open(assetPath).bufferedReader().readText()
            parseFixtureStates(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load fixture $assetPath: ${e.message}")
            null
        }
    }

    /**
     * Parses fixture JSON into app-local [IslandState] snapshots for mock playback.
     * Uses the same "state" discriminator as the Phase 3 fixture files.
     * Internal so [IslandViewModelTest] can test it via [TestableParser].
     */
    internal fun parseFixtureStates(json: String): List<IslandState> {
        val array = org.json.JSONArray(json)
        val result = mutableListOf<IslandState>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val state = when (obj.optString("state")) {
                "resolving"  -> IslandState.Resolving(
                    label      = obj.optString("label", "Resolving…"),
                    traceLines = emptyList(),
                )
                "thinking"   -> IslandState.Thinking(label = obj.optString("label", "Thinking…"))
                "acting"     -> IslandState.Acting(
                    label    = obj.optString("label", "Working…"),
                    subLabel = obj.optString("subLabel").takeIf { it.isNotEmpty() },
                )
                "needs_input" -> IslandState.NeedsInput(
                    label        = obj.optString("label", "Confirm?"),
                    question     = obj.optString("question", "Confirm?"),
                    context      = obj.optString("context").takeIf { it.isNotEmpty() },
                    confirmLabel = obj.optString("confirmLabel", "Confirm"),
                    cancelLabel  = obj.optString("cancelLabel", "Cancel"),
                    isDangerous  = obj.optBoolean("isDangerous", false),
                    traceId      = obj.optString("traceId", UUID.randomUUID().toString()),
                    actionId     = obj.optString("actionId", ""),
                )
                "done"        -> IslandState.Done(label = obj.optString("label", "Done"))
                "failed"      -> IslandState.Failed(reason = obj.optString("label", "Something went wrong").ifBlank { "Something went wrong" })
                "morning_brief" -> {
                    val linesArr = obj.optJSONArray("lines")
                    val lines = if (linesArr != null) {
                        (0 until linesArr.length()).map { linesArr.getString(it) }
                    } else emptyList()
                    IslandState.MorningBrief(lines)
                }
                else -> continue
            }
            result += state
        }
        return result
    }
}

// ── IslandVisualState → IslandState mapping ───────────────────────────────
// Maps the brain-contract sealed interface to the app-local UI state.
// ProxyCall and MorningBrief have no contract counterpart yet (parked per PRD §5).
private fun IslandVisualState.toIslandState(): IslandState = when (this) {
    IslandVisualState.Idle       -> IslandState.Idle
    IslandVisualState.Listening  -> IslandState.Listening

    is IslandVisualState.Resolving -> IslandState.Resolving(
        label      = label ?: "Resolving…",
        traceLines = stepTrace.map { TraceLine(it) },
    )

    is IslandVisualState.Thinking -> IslandState.Thinking(label = taskLabel)

    is IslandVisualState.Acting -> IslandState.Acting(actionSummary)

    is IslandVisualState.NeedsInput -> IslandState.NeedsInput(
        label        = prompt,
        question     = prompt,
        context      = null,
        confirmLabel = if (options.size >= 1) options[0].label else "Confirm",
        cancelLabel  = if (options.size >= 2) options[1].label else "Cancel",
        isDangerous  = risk == ActionRisk.HIGH,
        // An option-less clarification is answered by voice/text, not a
        // button: hide the affirmative control instead of showing an inert
        // one. (Contact clarifications carry candidate options and are
        // unchanged.) CT-8: a countdown confirms itself, so it renders
        // only its cancel control.
        confirmEnabled = !(kind == InputKind.CLARIFICATION && options.isEmpty()) &&
            kind != InputKind.COUNTDOWN,
        traceId      = traceId,
        actionId     = traceId, // contract has only traceId; actionId kept for UI compat
    )

    is IslandVisualState.Done   -> IslandState.Done(label = feedbackLine)

    is IslandVisualState.Undoable -> IslandState.Undoable(
        label     = feedbackLine,
        undoToken = undoToken,
        windowMs  = windowMs,
        undoLabel = undoLabel,
    )

    is IslandVisualState.Failed -> IslandState.Failed(reason = errorReason)

    // Brief has no app-local counterpart yet (PRD §5 — MorningBrief parked).
    // Fall back to Idle so the island clears rather than crashes.
    is IslandVisualState.Brief  -> IslandState.Idle
}
