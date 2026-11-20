package com.raijin.app.skill

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.raijin.brain.skills.Condition
import io.raijin.brain.skills.SkillEgress
import io.raijin.brain.skills.SkillManifest
import io.raijin.brain.skills.SkillParameter
import io.raijin.brain.skills.SkillStep
import io.raijin.brain.skills.SkillTriggers

/**
 * SK-2: parses a skill.json payload into a brain-core [SkillManifest].
 * Structural problems (bad JSON, unknown step/condition/parameter types,
 * missing required fields) are parse errors; semantic checks
 * (unknown tools, bounds) stay with the brain-core validator, which the
 * install flow runs next. Nothing here silently rewrites anything.
 */
object SkillJsonParser {

    sealed interface ParseResult {
        data class Ok(val manifest: SkillManifest) : ParseResult
        data class Error(val reason: String) : ParseResult
    }

    // --- DTOs (flat, Moshi-friendly; sealed mapping happens below) ---

    internal data class ManifestJson(
        val id: String?,
        val version: Int?,
        val name: String?,
        val description: String?,
        val triggers: TriggersJson?,
        val parameters: List<ParameterJson>?,
        val steps: List<StepJson>?,
        val requires: List<String>?,
        @Json(name = "egress_class") val egressClass: String?,
        val sha256: String?,
    )

    internal data class TriggersJson(
        val phrases: List<String>?,
        val regexes: List<String>?,
        val examples: List<String>?,
    )

    internal data class ParameterJson(
        val name: String?,
        val type: String?,
        val required: Boolean?,
        val default: String?,
    )

    internal data class StepJson(
        val type: String?,
        val tool: String?,
        val args: Map<String, String>?,
        val prompt: String?,
        @Json(name = "store_as") val storeAs: String?,
        val text: String?,
        val condition: ConditionJson?,
        val then: List<StepJson>?,
        @Json(name = "else") val otherwise: List<StepJson>?,
        val steps: List<StepJson>?,
        val times: Int?,
        val millis: Long?,
    )

    internal data class ConditionJson(
        val kind: String?,
        val key: String?,
        val value: String?,
    )

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    /**
     * Canonical payload for signing: the manifest re-serialised with the
     * sha256 field removed, so the hash covers content, not itself, and
     * is stable across whitespace/key-order differences.
     */
    fun canonicalPayload(json: String): String? {
        val dto = try {
            moshi.adapter(ManifestJson::class.java).fromJson(json)
        } catch (e: Exception) {
            return null
        } ?: return null
        return moshi.adapter(ManifestJson::class.java).toJson(dto.copy(sha256 = null))
    }

    fun parse(json: String): ParseResult {
        val dto = try {
            moshi.adapter(ManifestJson::class.java).fromJson(json)
        } catch (e: Exception) {
            return ParseResult.Error("invalid JSON: ${e.message}")
        } ?: return ParseResult.Error("empty manifest")

        val id = dto.id?.takeIf { it.isNotBlank() } ?: return ParseResult.Error("missing id")
        val name = dto.name?.takeIf { it.isNotBlank() } ?: return ParseResult.Error("missing name")
        val version = dto.version ?: return ParseResult.Error("missing version")
        val stepsJson = dto.steps ?: return ParseResult.Error("missing steps")

        val steps = when (val mapped = mapSteps(stepsJson)) {
            is StepsMap.Error -> return ParseResult.Error(mapped.reason)
            is StepsMap.Ok -> mapped.steps
        }

        val egress = when (dto.egressClass?.lowercase()) {
            null, "l0", "l0_local" -> SkillEgress.L0_LOCAL
            "l1", "l1_redacted" -> SkillEgress.L1_REDACTED
            "l2", "l2_consent" -> SkillEgress.L2_CONSENT
            else -> return ParseResult.Error("unknown egress_class: ${dto.egressClass}")
        }

        val parameters = mutableListOf<SkillParameter>()
        for (p in dto.parameters.orEmpty()) {
            val pName = p.name?.takeIf { it.isNotBlank() }
                ?: return ParseResult.Error("parameter missing name")
            val pType = when (p.type?.lowercase()) {
                "text" -> SkillParameter.Type.TEXT
                "number" -> SkillParameter.Type.NUMBER
                "time_of_day" -> SkillParameter.Type.TIME_OF_DAY
                "contact" -> SkillParameter.Type.CONTACT
                "app" -> SkillParameter.Type.APP
                else -> return ParseResult.Error("parameter $pName: unknown type ${p.type}")
            }
            parameters += SkillParameter(pName, pType, p.required ?: true, p.default)
        }

        return ParseResult.Ok(
            SkillManifest(
                id = id,
                version = version,
                name = name,
                description = dto.description.orEmpty(),
                triggers = SkillTriggers(
                    phrases = dto.triggers?.phrases.orEmpty(),
                    regexes = dto.triggers?.regexes.orEmpty(),
                    examples = dto.triggers?.examples.orEmpty(),
                ),
                parameters = parameters,
                steps = steps,
                requires = dto.requires.orEmpty(),
                egress = egress,
                sha256 = dto.sha256,
            ),
        )
    }

    private sealed interface StepsMap {
        data class Ok(val steps: List<SkillStep>) : StepsMap
        data class Error(val reason: String) : StepsMap
    }

    private fun mapSteps(list: List<StepJson>): StepsMap {
        val out = mutableListOf<SkillStep>()
        for ((index, stepJson) in list.withIndex()) {
            when (val mapped = mapStep(stepJson)) {
                is StepsMap.Ok -> out += mapped.steps.single()
                is StepsMap.Error -> return StepsMap.Error("step $index: ${mapped.reason}")
            }
        }
        return StepsMap.Ok(out)
    }

    private fun mapStep(json: StepJson): StepsMap {
        fun one(step: SkillStep) = StepsMap.Ok(listOf(step))
        fun err(reason: String) = StepsMap.Error(reason)

        return when (json.type?.lowercase()) {
            "call_tool" -> {
                val tool = json.tool?.takeIf { it.isNotBlank() }
                    ?: return err("call_tool missing tool")
                one(SkillStep.CallTool(tool, json.args.orEmpty()))
            }

            "ask_user" -> {
                val prompt = json.prompt?.takeIf { it.isNotBlank() }
                    ?: return err("ask_user missing prompt")
                val storeAs = json.storeAs?.takeIf { it.isNotBlank() }
                    ?: return err("ask_user missing store_as")
                one(SkillStep.AskUser(prompt, storeAs))
            }

            "speak" -> {
                val text = json.text?.takeIf { it.isNotBlank() }
                    ?: return err("speak missing text")
                one(SkillStep.Speak(text))
            }

            "if" -> {
                val conditionJson = json.condition ?: return err("if missing condition")
                val key = conditionJson.key?.takeIf { it.isNotBlank() }
                    ?: return err("condition missing key")
                val condition: Condition = when (conditionJson.kind?.lowercase()) {
                    "equals" -> Condition.Equals(key, conditionJson.value.orEmpty())
                    "not_equals" -> Condition.NotEquals(key, conditionJson.value.orEmpty())
                    "is_set" -> Condition.IsSet(key)
                    "is_not_set" -> Condition.IsNotSet(key)
                    else -> return err("unknown condition kind: ${conditionJson.kind}")
                }
                val then = when (val mapped = mapSteps(json.then.orEmpty())) {
                    is StepsMap.Error -> return mapped
                    is StepsMap.Ok -> mapped.steps
                }
                val otherwise = when (val mapped = mapSteps(json.otherwise.orEmpty())) {
                    is StepsMap.Error -> return mapped
                    is StepsMap.Ok -> mapped.steps
                }
                one(SkillStep.IfElse(condition, then, otherwise))
            }

            "repeat" -> {
                val times = json.times ?: return err("repeat missing times")
                val body = when (val mapped = mapSteps(json.steps.orEmpty())) {
                    is StepsMap.Error -> return mapped
                    is StepsMap.Ok -> mapped.steps
                }
                one(SkillStep.Repeat(times, body))
            }

            "wait" -> one(SkillStep.WaitMillis(json.millis ?: 0L))

            else -> err("unknown step type: ${json.type}")
        }
    }
}
