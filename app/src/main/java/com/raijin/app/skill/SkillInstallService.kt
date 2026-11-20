package com.raijin.app.skill

import com.raijin.app.RaijinDatabase
import io.raijin.brain.skills.SkillInstaller
import io.raijin.brain.skills.SkillManifest
import io.raijin.contract.ActionType
import java.security.MessageDigest

/**
 * SK-2: app-side install coordinator. Parses the payload, verifies the
 * checksum, asks the brain-core installer for a plan, and only persists
 * what the owner approved. The stored manifest_json is the exact approved
 * payload, so the stored hash always matches the stored bytes.
 */
class SkillInstallService(
    private val db: RaijinDatabase,
) {
    private val dao get() = db.installedSkillDao()

    sealed interface PlanOutcome {
        /** Ready to show the owner for approval. */
        data class Ready(val payloadJson: String, val source: String, val plan: SkillInstaller.InstallPlan) : PlanOutcome

        /** Parse or plan rejection; [reasons] are shown, nothing is stored. */
        data class Rejected(val reasons: List<String>) : PlanOutcome
    }

    /** Parse + validate + checksum + plan. Nothing is persisted here. */
    suspend fun planInstall(payloadJson: String, source: String): PlanOutcome {
        val parsed = when (val result = SkillJsonParser.parse(payloadJson)) {
            is SkillJsonParser.ParseResult.Error -> return PlanOutcome.Rejected(listOf(result.reason))
            is SkillJsonParser.ParseResult.Ok -> result.manifest
        }
        val canonical = SkillJsonParser.canonicalPayload(payloadJson)
            ?: return PlanOutcome.Rejected(listOf("cannot canonicalise payload for checksum"))
        val hash = sha256Hex(canonical)
        val existing = dao.versionsOf(parsed.id).map { it.toStored() }
        return when (val plan = SkillInstaller.plan(parsed, hash, existing, KNOWN_TOOLS, KNOWN_CAPABILITIES)) {
            is SkillInstaller.InstallPlan.Reject -> PlanOutcome.Rejected(plan.errors)
            else -> PlanOutcome.Ready(payloadJson, source, plan)
        }
    }

    /** Persist an approved plan. Returns false for plans that cannot be installed. */
    suspend fun confirmInstall(ready: PlanOutcome.Ready): Boolean {
        val manifest = when (val plan = ready.plan) {
            is SkillInstaller.InstallPlan.FreshInstall -> plan.manifest
            is SkillInstaller.InstallPlan.Upgrade -> plan.manifest
            is SkillInstaller.InstallPlan.AlreadyInstalled -> return true
            is SkillInstaller.InstallPlan.Reject -> return false
        }
        val hash = manifest.sha256 ?: return false
        // A new approved version takes over; older versions stay for rollback but stop matching.
        dao.setEnabledForAllVersions(manifest.id, enabled = false)
        dao.insert(
            InstalledSkillEntity(
                skillId = manifest.id,
                version = manifest.version,
                manifestJson = ready.payloadJson,
                sha256 = hash,
                source = ready.source,
                enabled = true,
                installedAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    suspend fun setEnabled(skillId: String, enabled: Boolean) =
        dao.setEnabledForAllVersions(skillId, enabled)

    /** Roll back to the previous version. Returns the restored version, or null if none. */
    suspend fun rollback(skillId: String): Int? {
        val versions = dao.versionsOf(skillId)
        val current = versions.firstOrNull() ?: return null
        val target = SkillInstaller.rollbackTarget(versions.map { it.toStored() }, skillId, current.version)
            ?: return null
        dao.setEnabledForAllVersions(skillId, enabled = false)
        dao.insert(
            versions.first { it.version == target.manifest.version }.copy(enabled = true),
        )
        return target.manifest.version
    }

    /** Manifests of currently enabled skills, for the runtime's matching pass. */
    /** Every stored version of every skill, for the settings surface. */
    suspend fun installed(): List<InstalledSkillEntity> = dao.all()

    suspend fun enabledManifests(): List<SkillManifest> =
        dao.enabled().mapNotNull { entity ->
            (SkillJsonParser.parse(entity.manifestJson) as? SkillJsonParser.ParseResult.Ok)?.manifest
        }

    private fun InstalledSkillEntity.toStored(): SkillInstaller.StoredSkill {
        val manifest = (SkillJsonParser.parse(manifestJson) as? SkillJsonParser.ParseResult.Ok)
            ?.manifest
            ?: error("stored skill $skillId v$version no longer parses")
        return SkillInstaller.StoredSkill(manifest, installedAt, enabled, source)
    }

    companion object {
        val KNOWN_TOOLS: Set<String> = ActionType.entries.map { it.name }.toSet()

        /** Capability ids, matching the Diagnostics screen. */
        val KNOWN_CAPABILITIES: Set<String> = setOf(
            "mic", "stt", "tts", "contacts", "apps", "alarms",
            "accessibility", "notif", "storage", "media", "overlay",
        )

        fun sha256Hex(payload: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
