package io.raijin.brain.skills

import io.raijin.contract.ActionType

/**
 * Runtime egress gate: the manifest's declared egress class is enforced at
 * execution time, not trusted. Every registry tool has an exhaustively
 * classified actual data-egress behavior; a skill can never move data
 * beyond what its declaration - and, for L2, a per-call owner consent -
 * allows. Fail-closed everywhere: unknown tools were already rejected by
 * the validator, and any gap here blocks the call.
 */
object ToolEgressPolicy {

    /**
     * The data-egress behavior each registry tool actually has when the
     * actuator executes it. Classified by what happens to skill content:
     * does any of it leave the device through this tool's normal operation?
     */
    private val TOOL_EGRESS: Map<ActionType, SkillEgress> = mapOf(
        // Purely local: nothing leaves the device.
        ActionType.NONE to SkillEgress.L0_LOCAL,
        ActionType.OPEN_APP to SkillEgress.L0_LOCAL,
        ActionType.CREATE_REMINDER to SkillEgress.L0_LOCAL,
        ActionType.CANCEL_REMINDER to SkillEgress.L0_LOCAL,
        ActionType.LIST_REMINDERS to SkillEgress.L0_LOCAL,
        ActionType.SET_ALARM to SkillEgress.L0_LOCAL,
        ActionType.SET_TIMER to SkillEgress.L0_LOCAL,
        ActionType.DISMISS_ALARM to SkillEgress.L0_LOCAL,
        ActionType.OPEN_APP_SETTINGS to SkillEgress.L0_LOCAL,
        ActionType.MUSIC_FEEDBACK to SkillEgress.L0_LOCAL,
        ActionType.LOCAL_CODING to SkillEgress.L0_LOCAL,
        ActionType.FIND_FILE to SkillEgress.L0_LOCAL,
        ActionType.ORGANIZE_FILES to SkillEgress.L0_LOCAL,
        // OB-1: local write inside the owner-configured vault root only.
        // (Cloud note-DRAFTING is a separate flow with its own consent.)
        ActionType.WRITE_VAULT_NOTE to SkillEgress.L0_LOCAL,
        ActionType.DELETE_FILES to SkillEgress.L0_LOCAL,
        ActionType.READ_NOTIFICATIONS to SkillEgress.L0_LOCAL,
        ActionType.CHAT_REPLY to SkillEgress.L0_LOCAL,

        // Content leaves only through the owner's own configured service:
        // PLAY_QUERY falls through to the owner's streaming app with the
        // query text when no local-library match exists (verified against
        // AndroidActuator.executeMusicControl). The tool itself opens no
        // network connection.
        ActionType.MUSIC_CONTROL to SkillEgress.L1_REDACTED,

        // Skill content leaves the device as the action's purpose; each
        // call needs explicit owner consent at run time.
        // CREATE_CALENDAR_EVENT has no actuator path yet; calendar sync to
        // the owner's account is likely once it does. Strictest until the
        // implementation exists and is audited.
        ActionType.CREATE_CALENDAR_EVENT to SkillEgress.L2_CONSENT,
        ActionType.CALL_CONTACT to SkillEgress.L2_CONSENT,
        ActionType.SEND_MESSAGE to SkillEgress.L2_CONSENT,
        ActionType.OPEN_DEEP_LINK to SkillEgress.L2_CONSENT,
        ActionType.SHARE_FILE to SkillEgress.L2_CONSENT,
        ActionType.SHARE_CONTACT to SkillEgress.L2_CONSENT,
        ActionType.CLOUD_CODING to SkillEgress.L2_CONSENT,
        ActionType.DRAFT_DOCUMENT to SkillEgress.L2_CONSENT,
        ActionType.BUILD_APP to SkillEgress.L2_CONSENT,
        ActionType.SERVE_WEB_APP to SkillEgress.L2_CONSENT,
    )

    /** Actual egress behavior of [tool] (contract ActionType name), or null when unknown. */
    fun requiredEgress(tool: String): SkillEgress? =
        ActionType.entries.firstOrNull { it.name == tool }?.let { TOOL_EGRESS[it] }

    sealed interface Decision {
        /** Dispatch as-is. */
        data object Allowed : Decision

        /**
         * Dispatch only when redaction would be a no-op; when an arg carries
         * a phone/email-shaped span, silently substituting "[redacted]" would
         * corrupt the intended action, so the call escalates to consent.
         */
        data object RedactThenAllow : Decision

        /** Ask the owner for explicit per-call consent first. */
        data object NeedsConsent : Decision

        /** The call would exceed the declared class; never dispatch. */
        data class Blocked(val reason: String) : Decision
    }

    fun decide(declared: SkillEgress, required: SkillEgress): Decision = when {
        required == SkillEgress.L0_LOCAL -> Decision.Allowed
        declared == SkillEgress.L0_LOCAL -> Decision.Blocked(
            "tool requires ${required.name} but the skill declares L0_LOCAL"
        )
        required == SkillEgress.L1_REDACTED -> Decision.RedactThenAllow
        declared == SkillEgress.L2_CONSENT -> Decision.NeedsConsent
        else -> Decision.Blocked(
            "tool requires L2_CONSENT but the skill declares ${declared.name}"
        )
    }

    private val PHONE = Regex("\\+?\\d[\\d\\s().-]{6,}\\d")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /** Redact phone-number and email-shaped values from outgoing args. */
    fun redactArgs(args: Map<String, String>): Map<String, String> =
        args.mapValues { (_, v) -> redactText(v) }

    /** Redact phone-number and email-shaped spans from free text. */
    fun redactText(text: String): String =
        text.replace(EMAIL, "[redacted]").replace(PHONE, "[redacted]")

    /** Affirmative consent answers accepted at run time. */
    fun isConsent(answer: String): Boolean =
        answer.trim().lowercase() in setOf("yes", "y", "allow", "ok", "approve")
}
