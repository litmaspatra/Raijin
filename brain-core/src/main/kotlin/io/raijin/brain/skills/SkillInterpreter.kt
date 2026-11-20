package io.raijin.brain.skills

/**
 * SK-1: skill interpreter. Executes a VALIDATED manifest step by step.
 * The interpreter never invents tools: [SkillRuntime.callTool] is the
 * only dispatch path and is fed only names the validator accepted.
 */
interface SkillRuntime {
    /** Invoke a registry tool. Returns a short outcome line for the run log. */
    suspend fun callTool(tool: String, args: Map<String, String>): SkillToolResult

    /** Ask the owner a question and return their answer. */
    suspend fun askUser(prompt: String): String

    /** Speak a line to the owner. */
    suspend fun speak(text: String)

    /** Wait; the app side maps this to a delay or scheduled continuation. */
    suspend fun waitMillis(millis: Long)
}

data class SkillToolResult(
    val ok: Boolean,
    val detail: String = "",
)

sealed interface SkillRunResult {
    /** Every step executed; [log] is the owner-visible run record (SK-2 shows it). */
    data class Completed(val log: List<String>) : SkillRunResult

    /** A tool call failed; execution stopped at [atStep] (fail-closed, no skipping). */
    data class FailedAt(val atStep: Int, val reason: String, val log: List<String>) : SkillRunResult
}

object SkillInterpreter {

    suspend fun run(
        manifest: SkillManifest,
        runtime: SkillRuntime,
        parameterValues: Map<String, String> = emptyMap(),
    ): SkillRunResult {
        // Provided values seed the scope; declared defaults fill what is missing.
        val values = parameterValues.toMutableMap()
        for (param in manifest.parameters) {
            if (values[param.name] == null && param.default != null) {
                values[param.name] = param.default
            }
        }
        val log = mutableListOf<String>()
        val ok = try {
            runSteps(manifest.steps, runtime, values, log, depth = 1, declaredEgress = manifest.egress)
        } catch (e: Exception) {
            // Fail-closed: a runtime error stops the run with its record intact.
            log += "error: ${e.message ?: e.javaClass.simpleName}"
            return SkillRunResult.FailedAt(log.size, e.message ?: "step failed", log)
        }
        return if (ok.first) {
            SkillRunResult.Completed(log)
        } else {
            SkillRunResult.FailedAt(ok.second, log.lastOrNull() ?: "step failed", log)
        }
    }

    /** @return Pair(success, failingStepOrdinal) */
    private suspend fun runSteps(
        steps: List<SkillStep>,
        runtime: SkillRuntime,
        values: MutableMap<String, String>,
        log: MutableList<String>,
        depth: Int,
        declaredEgress: SkillEgress,
    ): Pair<Boolean, Int> {
        var ordinal = 0
        for (step in steps) {
            ordinal++
            when (step) {
                is SkillStep.CallTool -> {
                    var resolvedArgs = step.args.mapValues { (_, v) -> resolve(v, values) }
                    // Runtime egress gate: the declared class is enforced, not
                    // trusted. Unknown tools never reach here (validator).
                    val required = ToolEgressPolicy.requiredEgress(step.tool)
                        ?: return run {
                            log += "egress: unknown tool ${step.tool}"
                            false to ordinal
                        }
                    when (val decision = ToolEgressPolicy.decide(declaredEgress, required)) {
                        is ToolEgressPolicy.Decision.Blocked -> {
                            log += "egress blocked: ${decision.reason}"
                            return false to ordinal
                        }
                        ToolEgressPolicy.Decision.RedactThenAllow -> {
                            if (ToolEgressPolicy.redactArgs(resolvedArgs) == resolvedArgs) {
                                log += "egress: no sensitive patterns in ${step.tool} args"
                            } else {
                                // Redaction would change what the action means
                                // (a song query, a message text). Never
                                // substitute silently - ask the owner to send
                                // the original, or fail closed.
                                val answer = runtime.askUser(
                                    "Skill wants to send possibly private data off-device with ${step.tool}. Allow?"
                                )
                                log += "egress: consent ${if (ToolEgressPolicy.isConsent(answer)) "granted" else "denied"} for ${step.tool}"
                                if (!ToolEgressPolicy.isConsent(answer)) return false to ordinal
                            }
                        }
                        ToolEgressPolicy.Decision.NeedsConsent -> {
                            val answer = runtime.askUser(
                                "Skill wants to send data off-device with ${step.tool}. Allow?"
                            )
                            log += "egress: consent ${if (ToolEgressPolicy.isConsent(answer)) "granted" else "denied"} for ${step.tool}"
                            if (!ToolEgressPolicy.isConsent(answer)) return false to ordinal
                        }
                        ToolEgressPolicy.Decision.Allowed -> Unit
                    }
                    val result = runtime.callTool(step.tool, resolvedArgs)
                    // Resolved args are part of the owner-visible record: a
                    // preview or run log must show WHAT will be/was done,
                    // not just the tool name.
                    val argsText = resolvedArgs.entries.joinToString(", ") { (k, v) -> "$k=$v" }
                    log += "call ${step.tool}($argsText) -> ${if (result.ok) "ok" else "failed"} ${result.detail}".trim()
                    if (!result.ok) return false to ordinal
                }

                is SkillStep.AskUser -> {
                    val answer = runtime.askUser(resolve(step.prompt, values))
                    values[step.storeAs] = answer
                    log += "ask ${step.storeAs}"
                }

                is SkillStep.Speak -> {
                    val line = resolve(step.text, values)
                    runtime.speak(line)
                    log += "speak"
                }

                is SkillStep.IfElse -> {
                    val branch = if (evaluate(step.condition, values)) step.then else step.otherwise
                    val nested = runSteps(branch, runtime, values, log, depth + 1, declaredEgress)
                    if (!nested.first) return false to ordinal
                }

                is SkillStep.Repeat -> {
                    repeat(step.times) {
                        val nested = runSteps(step.steps, runtime, values, log, depth + 1, declaredEgress)
                        if (!nested.first) return false to ordinal
                    }
                }

                is SkillStep.WaitMillis -> {
                    if (step.millis > 0) runtime.waitMillis(step.millis)
                    log += "wait ${step.millis}ms"
                }
            }
        }
        return true to ordinal
    }

    private fun evaluate(condition: Condition, values: Map<String, String>): Boolean =
        when (condition) {
            is Condition.Equals -> values[condition.key] == condition.value
            is Condition.NotEquals -> values[condition.key] != condition.value
            is Condition.IsSet -> values[condition.key] != null
            is Condition.IsNotSet -> values[condition.key] == null
        }

    /** Resolve `{name}` placeholders from collected parameter/answer values. */
    internal fun resolve(template: String, values: Map<String, String>): String {
        var out = template
        for ((key, value) in values) {
            out = out.replace("{$key}", value)
        }
        return out
    }
}

/** SK-1 trigger matching: phrase containment or regex hit, case-insensitive. */
object SkillMatcher {
    fun matches(manifest: SkillManifest, utterance: String): Boolean {
        val text = utterance.trim()
        if (text.isEmpty()) return false
        for (phrase in manifest.triggers.phrases) {
            if (phrase.isNotBlank() && text.contains(phrase, ignoreCase = true)) return true
        }
        for (pattern in manifest.triggers.regexes) {
            val regex = try {
                Regex(pattern, RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                continue
            }
            if (regex.containsMatchIn(text)) return true
        }
        return false
    }
}
