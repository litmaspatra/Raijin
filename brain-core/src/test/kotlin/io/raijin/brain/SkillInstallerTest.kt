package io.raijin.brain

import io.raijin.brain.skills.SkillEgress
import io.raijin.brain.skills.SkillInstaller
import io.raijin.brain.skills.SkillManifest
import io.raijin.brain.skills.SkillStep
import io.raijin.brain.skills.SkillTriggers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillInstallerTest {

    private val tools = setOf("SET_ALARM")
    private val capabilities = setOf("exact_alarms", "dnd")

    private fun manifest(
        id: String = "s",
        version: Int = 1,
        requires: List<String> = listOf("exact_alarms"),
        hash: String = "a".repeat(64),
    ) = SkillManifest(
        id = id,
        version = version,
        name = "s",
        description = "d",
        triggers = SkillTriggers(phrases = listOf("go")),
        steps = listOf(SkillStep.CallTool("SET_ALARM")),
        requires = requires,
        egress = SkillEgress.L0_LOCAL,
        sha256 = hash,
    )

    private fun stored(m: SkillManifest) =
        SkillInstaller.StoredSkill(m, installedAtEpochMs = 0, enabled = true, source = "file")

    @Test
    fun `valid signed fresh manifest plans a fresh install`() {
        val plan = SkillInstaller.plan(manifest(), "a".repeat(64), emptyList(), tools, capabilities)
        assertIs<SkillInstaller.InstallPlan.FreshInstall>(plan)
        assertEquals(listOf("exact_alarms"), plan.capabilities)
    }

    @Test
    fun `checksum mismatch rejects`() {
        val plan = SkillInstaller.plan(manifest(), "b".repeat(64), emptyList(), tools, capabilities)
        assertIs<SkillInstaller.InstallPlan.Reject>(plan)
        assertTrue(plan.errors.single().contains("checksum mismatch"))
    }

    @Test
    fun `unsigned manifest rejects`() {
        val plan = SkillInstaller.plan(
            manifest().copy(sha256 = null), "a".repeat(64), emptyList(), tools, capabilities,
        )
        assertIs<SkillInstaller.InstallPlan.Reject>(plan)
        assertTrue(plan.errors.single().contains("unsigned"))
    }

    @Test
    fun `invalid manifest rejects with validator messages`() {
        val bad = manifest().copy(steps = listOf(SkillStep.CallTool("HACK")))
        val plan = SkillInstaller.plan(bad, "a".repeat(64), emptyList(), tools, capabilities)
        assertIs<SkillInstaller.InstallPlan.Reject>(plan)
        assertTrue(plan.errors.first().contains("unknown tool"))
    }

    @Test
    fun `same version same hash is already installed`() {
        val m = manifest()
        val plan = SkillInstaller.plan(m, "a".repeat(64), listOf(stored(m)), tools, capabilities)
        assertIs<SkillInstaller.InstallPlan.AlreadyInstalled>(plan)
    }

    @Test
    fun `downgrade rejects`() {
        val plan = SkillInstaller.plan(
            manifest(version = 1, hash = "c".repeat(64)),
            "c".repeat(64),
            listOf(stored(manifest(version = 2, hash = "b".repeat(64)))),
            tools,
            capabilities,
        )
        assertIs<SkillInstaller.InstallPlan.Reject>(plan)
        assertTrue(plan.errors.single().contains("downgrade"))
    }

    @Test
    fun `upgrade reports capability diff`() {
        val plan = SkillInstaller.plan(
            manifest(version = 2, requires = listOf("exact_alarms", "dnd"), hash = "c".repeat(64)),
            "c".repeat(64),
            listOf(stored(manifest(version = 1, hash = "a".repeat(64)))),
            tools,
            capabilities,
        )
        assertIs<SkillInstaller.InstallPlan.Upgrade>(plan)
        assertEquals(1, plan.fromVersion)
        assertEquals(listOf("dnd"), plan.newCapabilities)
        assertEquals(emptyList(), plan.removedCapabilities)
    }

    @Test
    fun `rollback picks highest earlier version`() {
        val stored = listOf(
            stored(manifest(version = 1, hash = "a".repeat(64))),
            stored(manifest(version = 2, hash = "b".repeat(64))),
            stored(manifest(version = 3, hash = "c".repeat(64))),
        )
        val target = SkillInstaller.rollbackTarget(stored, "s", currentVersion = 3)
        assertEquals(2, target?.manifest?.version)
        assertNull(SkillInstaller.rollbackTarget(stored, "s", currentVersion = 1))
        assertNull(SkillInstaller.rollbackTarget(stored, "other", currentVersion = 3))
    }
}
