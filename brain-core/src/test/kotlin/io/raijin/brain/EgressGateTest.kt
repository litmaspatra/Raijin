package io.raijin.brain

import io.raijin.brain.skills.SkillDryRun
import io.raijin.brain.skills.SkillEgress
import io.raijin.brain.skills.SkillInterpreter
import io.raijin.brain.skills.SkillManifest
import io.raijin.brain.skills.SkillRunResult
import io.raijin.brain.skills.SkillRuntime
import io.raijin.brain.skills.SkillStep
import io.raijin.brain.skills.SkillToolResult
import io.raijin.brain.skills.SkillTriggers
import io.raijin.brain.skills.ToolEgressPolicy
import io.raijin.contract.ActionType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EgressGateTest {

    private class RecordingRuntime(
        private val consentAnswer: String = "yes",
    ) : SkillRuntime {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val questions = mutableListOf<String>()

        override suspend fun callTool(tool: String, args: Map<String, String>): SkillToolResult {
            calls += tool to args
            return SkillToolResult(ok = true)
        }

        override suspend fun askUser(prompt: String): String {
            questions += prompt
            return consentAnswer
        }

        override suspend fun speak(text: String) = Unit
        override suspend fun waitMillis(millis: Long) = Unit
    }

    private fun manifest(tool: String, egress: SkillEgress, args: Map<String, String> = emptyMap()) =
        SkillManifest(
            id = "t", version = 1, name = "T", description = "",
            triggers = SkillTriggers(phrases = listOf("t")),
            steps = listOf(SkillStep.CallTool(tool, args)),
            egress = egress,
        )

    @Test
    fun `every registry tool has an egress classification`() {
        ActionType.entries.forEach { type ->
            assertNotNull(ToolEgressPolicy.requiredEgress(type.name), "unclassified: ${type.name}")
        }
    }

    @Test
    fun `local tool under local declaration dispatches`() = runTest {
        val runtime = RecordingRuntime()
        val result = SkillInterpreter.run(manifest("SET_ALARM", SkillEgress.L0_LOCAL), runtime)
        assertTrue(result is SkillRunResult.Completed)
        assertEquals(1, runtime.calls.size)
    }

    @Test
    fun `consent tool under local declaration is blocked and never dispatched`() = runTest {
        val runtime = RecordingRuntime()
        val result = SkillInterpreter.run(manifest("SEND_MESSAGE", SkillEgress.L0_LOCAL), runtime)
        assertTrue(result is SkillRunResult.FailedAt)
        assertTrue(runtime.calls.isEmpty())
        assertTrue((result as SkillRunResult.FailedAt).log.any { it.contains("egress blocked") })
    }

    @Test
    fun `clean args under redacted declaration dispatch unchanged`() = runTest {
        val runtime = RecordingRuntime()
        val result = SkillInterpreter.run(
            manifest(
                "MUSIC_CONTROL", SkillEgress.L1_REDACTED,
                mapOf("command" to "PLAY_QUERY", "query" to "arijit singh"),
            ),
            runtime,
        )
        assertTrue(result is SkillRunResult.Completed)
        assertEquals("arijit singh", runtime.calls.single().second.getValue("query"))
        assertTrue(runtime.questions.isEmpty())
    }

    @Test
    fun `PII-shaped args escalate to consent and are never silently rewritten`() = runTest {
        val runtime = RecordingRuntime(consentAnswer = "yes")
        val result = SkillInterpreter.run(
            manifest(
                "MUSIC_CONTROL", SkillEgress.L1_REDACTED,
                mapOf("command" to "PLAY_QUERY", "query" to "voicemail +91 98765 43210"),
            ),
            runtime,
        )
        assertTrue(result is SkillRunResult.Completed)
        assertEquals(1, runtime.questions.size)
        // Consent granted: the ORIGINAL operand dispatches - redaction never
        // corrupts the intended action.
        assertEquals("voicemail +91 98765 43210", runtime.calls.single().second.getValue("query"))
    }

    @Test
    fun `PII-shaped args with consent denied fail closed`() = runTest {
        val runtime = RecordingRuntime(consentAnswer = "no")
        val result = SkillInterpreter.run(
            manifest(
                "MUSIC_CONTROL", SkillEgress.L1_REDACTED,
                mapOf("command" to "PLAY_QUERY", "query" to "call a@b.co"),
            ),
            runtime,
        )
        assertTrue(result is SkillRunResult.FailedAt)
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `consent tool under consent declaration asks and dispatches on yes`() = runTest {
        val runtime = RecordingRuntime(consentAnswer = "yes")
        val result = SkillInterpreter.run(manifest("SEND_MESSAGE", SkillEgress.L2_CONSENT), runtime)
        assertTrue(result is SkillRunResult.Completed)
        assertEquals(1, runtime.questions.size)
        assertEquals(1, runtime.calls.size)
    }

    @Test
    fun `consent denied fails closed and never dispatches`() = runTest {
        val runtime = RecordingRuntime(consentAnswer = "no")
        val result = SkillInterpreter.run(manifest("SEND_MESSAGE", SkillEgress.L2_CONSENT), runtime)
        assertTrue(result is SkillRunResult.FailedAt)
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `consent tool under redacted declaration is blocked`() = runTest {
        val runtime = RecordingRuntime()
        val result = SkillInterpreter.run(manifest("CALL_CONTACT", SkillEgress.L1_REDACTED), runtime)
        assertTrue(result is SkillRunResult.FailedAt)
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `dry run walks the consent path without dispatching`() = runTest {
        val result = SkillDryRun.preview(manifest("SEND_MESSAGE", SkillEgress.L2_CONSENT))
        assertTrue(result is SkillRunResult.Completed)
        assertTrue((result as SkillRunResult.Completed).log.any { it.contains("consent granted") })
    }
}
