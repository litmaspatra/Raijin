package io.raijin.brain

import io.raijin.brain.skills.SkillDryRun
import io.raijin.brain.skills.SkillManifest
import io.raijin.brain.skills.SkillRunResult
import io.raijin.brain.skills.SkillStep
import io.raijin.brain.skills.SkillTriggers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillDryRunTest {

    private fun manifest(steps: List<SkillStep>) = SkillManifest(
        id = "t", version = 1, name = "T", description = "",
        triggers = SkillTriggers(phrases = listOf("t")),
        steps = steps,
    )

    @Test
    fun `preview logs every step without executing tools`() = runTest {
        val result = SkillDryRun.preview(
            manifest(
                listOf(
                    SkillStep.CallTool("SET_ALARM", mapOf("time" to "6:30")),
                    SkillStep.Speak("done"),
                    SkillStep.WaitMillis(60_000),
                )
            )
        )
        assertTrue(result is SkillRunResult.Completed)
        val log = (result as SkillRunResult.Completed).log
        assertEquals(3, log.size)
        assertTrue(log[0].contains("SET_ALARM"))
        assertTrue(log[0].contains("(dry run)"))
        assertTrue(log[2].contains("60000"))
    }

    @Test
    fun `ask_user is answered with a placeholder so branches resolve`() = runTest {
        val result = SkillDryRun.preview(
            manifest(
                listOf(
                    SkillStep.AskUser("Who?", "p"),
                    SkillStep.Speak("hello {p}"),
                )
            )
        )
        assertTrue(result is SkillRunResult.Completed)
    }

    @Test
    fun `repeat expands in the preview log`() = runTest {
        val result = SkillDryRun.preview(
            manifest(listOf(SkillStep.Repeat(3, listOf(SkillStep.Speak("x")))))
        )
        assertTrue(result is SkillRunResult.Completed)
        assertEquals(3, (result as SkillRunResult.Completed).log.size)
    }
}
