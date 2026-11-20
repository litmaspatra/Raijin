package io.raijin.brain

import io.raijin.brain.skills.Condition
import io.raijin.brain.skills.SkillInterpreter
import io.raijin.brain.skills.SkillManifest
import io.raijin.brain.skills.SkillMatcher
import io.raijin.brain.skills.SkillParameter
import io.raijin.brain.skills.SkillRunResult
import io.raijin.brain.skills.SkillRuntime
import io.raijin.brain.skills.SkillStep
import io.raijin.brain.skills.SkillToolResult
import io.raijin.brain.skills.SkillTriggers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillInterpreterTest {

    private class FakeRuntime(
        private val failOnTool: String? = null,
        private val answer: String = "yes",
    ) : SkillRuntime {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val spoken = mutableListOf<String>()
        val asked = mutableListOf<String>()
        var waited = 0L

        override suspend fun callTool(tool: String, args: Map<String, String>): SkillToolResult {
            calls += tool to args
            return if (tool == failOnTool) SkillToolResult(false, "boom") else SkillToolResult(true)
        }

        override suspend fun askUser(prompt: String): String {
            asked += prompt
            return answer
        }

        override suspend fun speak(text: String) {
            spoken += text
        }

        override suspend fun waitMillis(millis: Long) {
            waited += millis
        }
    }

    private fun manifest(steps: List<SkillStep>) = SkillManifest(
        id = "s",
        version = 1,
        name = "s",
        description = "d",
        triggers = SkillTriggers(phrases = listOf("go")),
        steps = steps,
    )

    @Test
    fun `steps run in order and complete`() = runTest {
        val rt = FakeRuntime()
        val result = SkillInterpreter.run(
            manifest(
                listOf(
                    SkillStep.CallTool("SET_ALARM", mapOf("time" to "06:30")),
                    SkillStep.Speak("done"),
                ),
            ),
            rt,
        )
        assertIs<SkillRunResult.Completed>(result)
        assertEquals(listOf("SET_ALARM"), rt.calls.map { it.first })
        assertEquals(listOf("done"), rt.spoken)
    }

    @Test
    fun `failing tool stops execution fail-closed`() = runTest {
        val rt = FakeRuntime(failOnTool = "SET_ALARM")
        val result = SkillInterpreter.run(
            manifest(
                listOf(
                    SkillStep.CallTool("SET_ALARM"),
                    SkillStep.CallTool("MUSIC_CONTROL"),
                ),
            ),
            rt,
        )
        assertIs<SkillRunResult.FailedAt>(result)
        assertEquals(1, rt.calls.size)
    }

    @Test
    fun `ask stores answer and placeholders resolve`() = runTest {
        val rt = FakeRuntime(answer = "Rahul")
        SkillInterpreter.run(
            manifest(
                listOf(
                    SkillStep.AskUser("Who?", "person"),
                    SkillStep.CallTool("SET_ALARM", mapOf("label" to "{person}")),
                    SkillStep.Speak("Calling {person}"),
                ),
            ),
            rt,
        )
        assertEquals(mapOf("label" to "Rahul"), rt.calls[0].second)
        assertEquals(listOf("Calling Rahul"), rt.spoken)
    }

    @Test
    fun `ifelse picks the matching branch`() = runTest {
        val rt = FakeRuntime()
        SkillInterpreter.run(
            manifest(
                listOf(
                    SkillStep.IfElse(
                        Condition.Equals("mode", "night"),
                        then = listOf(SkillStep.Speak("night")),
                        otherwise = listOf(SkillStep.Speak("day")),
                    ),
                ),
            ),
            rt,
            parameterValues = mapOf("mode" to "night"),
        )
        assertEquals(listOf("night"), rt.spoken)
    }

    @Test
    fun `repeat runs the body exactly times`() = runTest {
        val rt = FakeRuntime()
        SkillInterpreter.run(
            manifest(listOf(SkillStep.Repeat(3, listOf(SkillStep.CallTool("SET_TIMER"))))),
            rt,
        )
        assertEquals(3, rt.calls.size)
    }

    @Test
    fun `parameter default applies when no value given`() = runTest {
        val rt = FakeRuntime()
        val m = manifest(listOf(SkillStep.Speak("alarm {time}"))).copy(
            parameters = listOf(
                SkillParameter("time", SkillParameter.Type.TIME_OF_DAY, required = false, default = "07:00"),
            ),
        )
        SkillInterpreter.run(m, rt)
        assertEquals(listOf("alarm 07:00"), rt.spoken)
    }

    @Test
    fun `matcher hits phrase and regex, misses unrelated`() {
        val m = manifest(emptyList()).copy(
            triggers = SkillTriggers(phrases = listOf("good night"), regexes = listOf("alarm \\d+")),
        )
        assertTrue(SkillMatcher.matches(m, "ok GOOD NIGHT everyone"))
        assertTrue(SkillMatcher.matches(m, "set alarm 630"))
        assertTrue(!SkillMatcher.matches(m, "what is the weather"))
    }
}
