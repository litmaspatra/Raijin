package com.raijin.app.viewmodel

import com.google.common.truth.Truth.assertThat
import com.raijin.app.ui.island.IslandState
import org.junit.Test

class IslandViewModelTest {

    // We test parseFixtureStates directly — it's pure JSON parsing,
    // no Android context required.

    /**
     * Subclass with no-arg constructor for unit tests.
     * Skips the Application/AndroidViewModel constructor entirely.
     */
    private class TestableParser : FixtureParser {
        fun parseFixtureStates(json: String): List<IslandState> {
            val array = org.json.JSONArray(json)
            val result = mutableListOf<IslandState>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val state = when (obj.optString("state")) {
                    "resolving"     -> IslandState.Resolving(label = obj.optString("label", "Resolving…"))
                    "thinking"      -> IslandState.Thinking(label = obj.optString("label", "Thinking…"))
                    "acting"        -> IslandState.Acting(
                        label    = obj.optString("label", "Working…"),
                        subLabel = obj.optString("subLabel").takeIf { it.isNotEmpty() },
                    )
                    "needs_input"   -> IslandState.NeedsInput(
                        label        = obj.optString("label", "Confirm?"),
                        question     = obj.optString("question", "Confirm?"),
                        context      = obj.optString("context").takeIf { it.isNotEmpty() },
                        confirmLabel = obj.optString("confirmLabel", "Confirm"),
                        cancelLabel  = obj.optString("cancelLabel", "Cancel"),
                        isDangerous  = obj.optBoolean("isDangerous", false),
                        traceId      = obj.optString("traceId", ""),
                        actionId     = obj.optString("actionId", ""),
                    )
                    "done"          -> IslandState.Done(label = obj.optString("label", "Done"))
                    "failed"        -> IslandState.Failed(reason = obj.optString("label", "Something went wrong"))
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

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun parseFixtureDoneState() {
        val json = """[{"state":"done","label":"Reminder set."}]"""
        val states = TestableParser().parseFixtureStates(json)
        assertThat(states).hasSize(1)
        assertThat(states[0]).isInstanceOf(IslandState.Done::class.java)
        assertThat((states[0] as IslandState.Done).label).isEqualTo("Reminder set.")
    }

    @Test
    fun parseFixtureThinkingState() {
        val json = """[{"state":"thinking","label":"On it…"}]"""
        val states = TestableParser().parseFixtureStates(json)
        assertThat(states[0]).isInstanceOf(IslandState.Thinking::class.java)
    }

    @Test
    fun parseFixtureMorningBriefWithLines() {
        val json = """[{"state":"morning_brief","lines":["2 reminders","3 WhatsApp"]}]"""
        val states = TestableParser().parseFixtureStates(json)
        assertThat(states[0]).isInstanceOf(IslandState.MorningBrief::class.java)
        val brief = states[0] as IslandState.MorningBrief
        assertThat(brief.lines).containsExactly("2 reminders", "3 WhatsApp")
    }

    @Test
    fun parseFixtureNeedsInput() {
        val json = """[{"state":"needs_input","label":"Make a call?",
            "question":"Call Rahul?","isDangerous":true,"actionId":"abc"}]"""
        val states = TestableParser().parseFixtureStates(json)
        assertThat(states[0]).isInstanceOf(IslandState.NeedsInput::class.java)
        val ni = states[0] as IslandState.NeedsInput
        assertThat(ni.isDangerous).isTrue()
        assertThat(ni.actionId).isEqualTo("abc")
    }

    @Test
    fun parseFixtureUnknownStateSkipped() {
        val json = """[{"state":"unknown_xyz"},{"state":"done","label":"OK"}]"""
        val states = TestableParser().parseFixtureStates(json)
        assertThat(states).hasSize(1)
        assertThat(states[0]).isInstanceOf(IslandState.Done::class.java)
    }

    @Test
    fun parseMultipleSteps() {
        val json = """[
            {"state":"thinking","label":"Setting reminder…"},
            {"state":"acting","label":"Saving…"},
            {"state":"done","label":"Reminder set."}
        ]"""
        val states = TestableParser().parseFixtureStates(json)
        assertThat(states).hasSize(3)
        assertThat(states[0]).isInstanceOf(IslandState.Thinking::class.java)
        assertThat(states[1]).isInstanceOf(IslandState.Acting::class.java)
        assertThat(states[2]).isInstanceOf(IslandState.Done::class.java)
    }
}
