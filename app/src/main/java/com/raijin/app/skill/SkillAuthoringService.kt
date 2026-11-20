package com.raijin.app.skill

import io.raijin.brain.skills.SkillDryRun
import io.raijin.brain.skills.SkillInstaller
import io.raijin.brain.skills.SkillManifest
import io.raijin.brain.skills.SkillRunResult

/**
 * SK-3: create-by-voice/text authoring. The owner says "make a skill:
 * when I say good night, set alarm 6:30". The cloud drafts a skill.json
 * from the spec (the spec text is the ONLY thing sent; L1), the draft is
 * parsed and validated fail-closed, and a dry-run preview shows every
 * step before the owner approves the install.
 */
class SkillAuthoringService {

    /**
     * What a voice/text spec becomes before anything leaves the device.
     * [original] is preserved locally unchanged; [redacted] is the ONLY
     * text that may go to the cloud, and the owner approves sending it
     * BEFORE the request is made (pre-send consent, not post-send review).
     */
    data class PreparedSpec(
        val original: String,
        val redacted: String,
        val changed: Boolean,
    )

    sealed interface DraftOutcome {
        /** Signed payload ready for planInstall + approval. [preview] is the dry run. */
        data class Ok(
            val payloadJson: String,
            val manifest: SkillManifest,
            val preview: SkillRunResult,
            /** The owner's words, preserved locally exactly as spoken. */
            val originalSpec: String,
            /** Exactly what was sent to the cloud: the spec after PII redaction. */
            val redactedSpec: String,
        ) : DraftOutcome

        data class Error(val reasons: List<String>) : DraftOutcome
    }

    /**
     * Prepare a spec for cloud drafting: the redacted text the owner must
     * approve sending. Phone numbers and emails are masked; [changed] tells
     * the UI to show both versions so redaction never silently turns the
     * request into a different skill.
     */
    fun prepareSpec(spec: String): PreparedSpec {
        val redacted = io.raijin.brain.skills.ToolEgressPolicy.redactText(spec)
        return PreparedSpec(original = spec, redacted = redacted, changed = redacted != spec)
    }

    /**
     * Draft a skill from a PREPARED spec the owner already approved sending.
     * [complete] is the cloud completion seam (OpenAiCompatibleClient.complete
     * in production, a fake in tests) so the service itself stays
     * offline-testable. Only [prepared].redacted is ever sent.
     */
    suspend fun draft(
        prepared: PreparedSpec,
        complete: suspend (prompt: String, systemPrompt: String?) -> String,
    ): DraftOutcome {
        val redactedSpec = prepared.redacted
        if (redactedSpec.isBlank()) return DraftOutcome.Error(listOf("empty skill spec"))
        val response = runCatching { complete(redactedSpec, SYSTEM_PROMPT) }
            .getOrElse { return DraftOutcome.Error(listOf("cloud drafting failed: ${it.message}")) }
        val json = extractJson(response)
            ?: return DraftOutcome.Error(listOf("cloud returned no skill.json"))

        when (val parsed = SkillJsonParser.parse(json)) {
            is SkillJsonParser.ParseResult.Error -> return DraftOutcome.Error(listOf(parsed.reason))
            is SkillJsonParser.ParseResult.Ok -> Unit
        }

        // Voice-authored skills carry an app-computed integrity checksum
        // (NOT a publisher signature: it proves the payload was not altered
        // after drafting, never who wrote it). The plan runs against the
        // checksummed manifest, exactly like the install path.
        val canonical = SkillJsonParser.canonicalPayload(json)
            ?: return DraftOutcome.Error(listOf("cannot canonicalise draft"))
        val hash = SkillInstallService.sha256Hex(canonical)
        val signed = attachIntegrityChecksum(json, hash)
        val manifest = when (val parsed = SkillJsonParser.parse(signed)) {
            is SkillJsonParser.ParseResult.Error -> return DraftOutcome.Error(listOf(parsed.reason))
            is SkillJsonParser.ParseResult.Ok -> parsed.manifest
        }

        // Run the same fail-closed validation the install path runs.
        return when (val plan = SkillInstaller.plan(manifest, hash, emptyList(), SkillInstallService.KNOWN_TOOLS, SkillInstallService.KNOWN_CAPABILITIES)) {
            is SkillInstaller.InstallPlan.Reject -> DraftOutcome.Error(plan.errors)
            else -> DraftOutcome.Ok(signed, manifest, SkillDryRun.preview(manifest), prepared.original, redactedSpec)
        }
    }

    companion object {
        /** Utterance prefixes that route to voice authoring (SK-3). */
        private val SPEC_PREFIXES = listOf("make a skill:", "create a skill:")

        /** The spec after "make a skill:" / "create a skill:", or null. */
        fun extractSpec(utterance: String): String? {
            val trimmed = utterance.trim()
            val lower = trimmed.lowercase()
            val prefix = SPEC_PREFIXES.firstOrNull { lower.startsWith(it) } ?: return null
            return trimmed.substring(prefix.length).trim().ifBlank { null }
        }

        private val SYSTEM_PROMPT = """
            You draft Raijin skill.json files. Reply with ONLY the JSON object, no prose.
            Schema:
            {
              "id": "<kebab-case id>", "version": 1, "name": "<short name>",
              "description": "<one line>",
              "triggers": { "phrases": ["<spoken trigger>", ...] },
              "parameters": [],
              "steps": [ ... ],
              "requires": ["<capability ids>"],
              "egress_class": "l0"
            }
            Step types: {"type":"call_tool","tool":"<TOOL>","args":{...}},
            {"type":"speak","text":"..."},
            {"type":"ask_user","prompt":"...","store_as":"<param>"},
            {"type":"wait","millis":N},
            {"type":"if","condition":{"kind":"equals","key":"<param>","value":"..."},"then":[...],"else":[...]},
            {"type":"repeat","times":N,"steps":[...]}.
            Only these tools exist: SET_ALARM (args: time "H:mm", label), SET_TIMER (args: seconds or minutes, label), MUSIC_CONTROL (args: command, optional query).
            Capabilities come from: mic, stt, tts, contacts, apps, alarms, accessibility, notif, storage, media, overlay.
            Keep everything on-device: egress_class is always "l0" unless the spec explicitly needs the network.
        """.trimIndent()

        /** First JSON object in the response, tolerating ```json fences and prose around it. */
        internal fun extractJson(response: String): String? {
            val start = response.indexOf('{')
            val end = response.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return response.substring(start, end + 1)
        }

        private fun attachIntegrityChecksum(json: String, hash: String): String {
            val cut = json.lastIndexOf('}')
            return json.substring(0, cut) + ", \"sha256\": \"$hash\" }"
        }
    }
}
