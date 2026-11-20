package io.raijin.brain.skills

/**
 * SK-2: install/upgrade/rollback decision logic (pure Kotlin; the app
 * side supplies storage and the actual approval UI). The owner always
 * approves installs; this class decides WHAT is presented and what may
 * be persisted, and never lets an invalid or untrusted manifest through.
 */
object SkillInstaller {

    /** A persisted skill version. */
    data class StoredSkill(
        val manifest: SkillManifest,
        val installedAtEpochMs: Long,
        val enabled: Boolean,
        /** Where it came from: "file", "https:<url>", "github:<owner/repo>", "voice". */
        val source: String,
    )

    sealed interface InstallPlan {
        /** Must not be shown for approval; [errors] are the reason. */
        data class Reject(val errors: List<String>) : InstallPlan

        /** New skill; [capabilities] and [egress] are what the owner approves. */
        data class FreshInstall(
            val manifest: SkillManifest,
            val capabilities: List<String>,
            val egress: SkillEgress,
        ) : InstallPlan

        /** Upgrade of an installed id; owner sees the version change and any new capabilities. */
        data class Upgrade(
            val manifest: SkillManifest,
            val fromVersion: Int,
            val newCapabilities: List<String>,
            val removedCapabilities: List<String>,
        ) : InstallPlan

        /** Same id+version already installed with the same hash; nothing to do. */
        data class AlreadyInstalled(val manifest: SkillManifest) : InstallPlan
    }

    /**
     * Decide what an install request means. Fail-closed at every step:
     * validation errors, a checksum mismatch, or a downgrade all reject.
     *
     * @param computedSha256 sha256 of the exact payload bytes, computed app-side.
     */
    fun plan(
        manifest: SkillManifest,
        computedSha256: String,
        existing: List<StoredSkill>,
        knownTools: Set<String>,
        knownCapabilities: Set<String>,
    ): InstallPlan {
        val validation = SkillValidator.validate(manifest, knownTools, knownCapabilities)
        if (validation.isNotEmpty()) {
            return InstallPlan.Reject(validation.map { it.message })
        }
        val declared = manifest.sha256
            ?: return InstallPlan.Reject(listOf("unsigned manifest: sha256 missing"))
        if (declared != computedSha256) {
            return InstallPlan.Reject(listOf("checksum mismatch: payload does not match manifest sha256"))
        }

        val sameId = existing.filter { it.manifest.id == manifest.id }
        if (sameId.isEmpty()) {
            return InstallPlan.FreshInstall(manifest, manifest.requires, manifest.egress)
        }
        val latest = sameId.maxBy { it.manifest.version }
        if (manifest.version == latest.manifest.version && manifest.sha256 == latest.manifest.sha256) {
            return InstallPlan.AlreadyInstalled(manifest)
        }
        if (manifest.version <= latest.manifest.version) {
            return InstallPlan.Reject(
                listOf("downgrade rejected: installed v${latest.manifest.version}, got v${manifest.version}"),
            )
        }
        val oldCaps = latest.manifest.requires.toSet()
        val newCaps = manifest.requires.toSet()
        return InstallPlan.Upgrade(
            manifest = manifest,
            fromVersion = latest.manifest.version,
            newCapabilities = (newCaps - oldCaps).sorted(),
            removedCapabilities = (oldCaps - newCaps).sorted(),
        )
    }

    /**
     * Pick the rollback target: the highest version below [currentVersion].
     * Null when no earlier version exists (uninstall is the app side's choice then).
     */
    fun rollbackTarget(
        existing: List<StoredSkill>,
        skillId: String,
        currentVersion: Int,
    ): StoredSkill? =
        existing
            .filter { it.manifest.id == skillId && it.manifest.version < currentVersion }
            .maxByOrNull { it.manifest.version }
}
