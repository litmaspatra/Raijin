package com.raijin.app.skill

import io.raijin.brain.skills.SkillRuntime
import io.raijin.brain.skills.SkillToolMapper
import io.raijin.brain.skills.SkillToolResult
import io.raijin.contract.ActionResult
import io.raijin.contract.ActionRisk
import io.raijin.contract.ActuatorPort
import io.raijin.contract.DispatchResult
import kotlinx.coroutines.delay

/**
 * SK-2: bridges the brain-core skill interpreter to the device's real
 * capabilities. Tools dispatch through the same [ActuatorPort] the voice
 * pipeline uses, so a skill can never do more than a spoken command.
 *
 * ask_user steps are intentionally unsupported until the island ask flow
 * exists (SK-3): they throw, the interpreter catches, and the run ends
 * FailedAt with the log intact - never a fabricated answer.
 */
class AppSkillRuntime(
    private val actuator: ActuatorPort,
    private val traceId: String,
    private val speakLine: (String) -> Unit,
    private val askHandler: (suspend (String) -> String)? = null,
) : SkillRuntime {

    override suspend fun callTool(tool: String, args: Map<String, String>): SkillToolResult {
        val mapped = when (val result = SkillToolMapper.map(tool, args)) {
            is SkillToolMapper.MapResult.Error -> return SkillToolResult(false, result.reason)
            is SkillToolMapper.MapResult.Ok -> result.call
        }
        val dispatch = DispatchResult(
            traceId = traceId,
            actionType = mapped.actionType,
            args = mapped.args,
            confidence = 1.0f,
            risk = ActionRisk.LOW,
        )
        return when (val result = actuator.execute(dispatch)) {
            is ActionResult.EXECUTED -> SkillToolResult(true, result.message.orEmpty())
            is ActionResult.DENIED -> SkillToolResult(false, result.reason)
            is ActionResult.FAILED -> SkillToolResult(false, result.reason)
        }
    }

    override suspend fun askUser(prompt: String): String {
        // Without an ask channel the step fails closed - never fabricated.
        val handler = askHandler
            ?: throw UnsupportedOperationException("ask_user steps need the island ask flow (SK-3)")
        return handler(prompt)
    }

    override suspend fun speak(text: String) = speakLine(text)

    override suspend fun waitMillis(millis: Long) = delay(millis)
}
