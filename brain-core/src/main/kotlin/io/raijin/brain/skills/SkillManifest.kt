package io.raijin.brain.skills

/**
 * SK-1: declarative skill manifest (PRD 7.9).
 *
 * A skill is a signed-by-hash, declarative manifest executed by an
 * interpreter inside brain-core. It can only invoke tools that already
 * exist in the capability registry (contract ActionType names). No
 * downloaded code is ever executed, so a skill cannot exceed what the
 * app already permits.
 *
 * This file is the pure-Kotlin schema. JSON (de)serialisation for
 * skill.json files is an app-side adapter concern (SK-2 install flow).
 */

/** Section-6 egress classes, mirrored here so brain-core stays the gatekeeper. */
enum class SkillEgress { L0_LOCAL, L1_REDACTED, L2_CONSENT }

/** A typed parameter the owner fills at install or run time. */
data class SkillParameter(
    val name: String,
    val type: Type,
    val required: Boolean = true,
    val default: String? = null,
) {
    enum class Type { TEXT, NUMBER, TIME_OF_DAY, CONTACT, APP }
}

/** How the skill is triggered by an utterance. */
data class SkillTriggers(
    val phrases: List<String> = emptyList(),
    val regexes: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
)

/** A single interpreter step. Loops and waits are hard-bounded. */
sealed interface SkillStep {
    /** Invoke a registry tool by contract ActionType name. Unknown names are validation errors. */
    data class CallTool(val tool: String, val args: Map<String, String> = emptyMap()) : SkillStep

    /** Ask the owner something; the answer is stored under [storeAs] for later steps. */
    data class AskUser(val prompt: String, val storeAs: String) : SkillStep

    /** Speak a line to the owner. `{param}` placeholders resolve from collected values. */
    data class Speak(val text: String) : SkillStep

    /** Conditional branch on a collected value. */
    data class IfElse(
        val condition: Condition,
        val then: List<SkillStep>,
        val otherwise: List<SkillStep> = emptyList(),
    ) : SkillStep

    /** Bounded loop; [times] is capped by the validator. */
    data class Repeat(val times: Int, val steps: List<SkillStep>) : SkillStep

    /** Bounded wait in milliseconds; capped by the validator. */
    data class WaitMillis(val millis: Long) : SkillStep
}

/** Condition on a value collected from parameters or AskUser answers. */
sealed interface Condition {
    data class Equals(val key: String, val value: String) : Condition
    data class NotEquals(val key: String, val value: String) : Condition
    data class IsSet(val key: String) : Condition
    data class IsNotSet(val key: String) : Condition
}

data class SkillManifest(
    val id: String,
    val version: Int,
    val name: String,
    val description: String,
    val triggers: SkillTriggers,
    val parameters: List<SkillParameter> = emptyList(),
    val steps: List<SkillStep>,
    val requires: List<String> = emptyList(),
    val egress: SkillEgress = SkillEgress.L0_LOCAL,
    /** Lowercase hex sha256 of the canonical manifest payload; null only for unsigned drafts. */
    val sha256: String? = null,
)
