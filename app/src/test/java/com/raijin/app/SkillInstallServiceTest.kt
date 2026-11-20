package com.raijin.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.raijin.app.skill.SkillInstallService
import com.raijin.app.skill.SkillJsonParser
import io.raijin.brain.skills.SkillInstaller
import io.raijin.brain.skills.SkillStep
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkillInstallServiceTest {

    private lateinit var db: RaijinDatabase
    private lateinit var service: SkillInstallService

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RaijinDatabase::class.java,
        ).build()
        service = SkillInstallService(db)
    }

    @After
    fun tearDown() = db.close()

    private fun payload(id: String = "good-night", version: Int = 1, requires: String = "\"alarms\""): String {
        val template = """
            {
              "id": "$id",
              "version": $version,
              "name": "Good night",
              "triggers": { "phrases": ["good night"] },
              "steps": [ { "type": "call_tool", "tool": "SET_ALARM" } ],
              "requires": [ $requires ],
              "egress_class": "l0"
            }
        """.trimIndent()
        val canonical = SkillJsonParser.canonicalPayload(template)!!
        val hash = SkillInstallService.sha256Hex(canonical)
        val cut = template.lastIndexOf('}')
        return template.substring(0, cut) + ", \"sha256\": \"$hash\" }"
    }

    @Test
    fun `plan then confirm persists an enabled skill`() = runTest {
        val outcome = service.planInstall(payload(), "file")
        assertTrue(outcome is SkillInstallService.PlanOutcome.Ready)
        val ready = outcome as SkillInstallService.PlanOutcome.Ready
        assertTrue(ready.plan is SkillInstaller.InstallPlan.FreshInstall)
        assertTrue(service.confirmInstall(ready))

        val enabled = service.enabledManifests()
        assertEquals(1, enabled.size)
        assertEquals("good-night", enabled[0].id)
        assertEquals("SET_ALARM", (enabled[0].steps[0] as SkillStep.CallTool).tool)
    }

    @Test
    fun `tampered payload is rejected by checksum`() = runTest {
        val signed = payload()
        val tampered = signed.replace("good night", "delete everything")
        val outcome = service.planInstall(tampered, "file")
        assertTrue(outcome is SkillInstallService.PlanOutcome.Rejected)
    }

    @Test
    fun `unknown capability is rejected`() = runTest {
        val outcome = service.planInstall(payload(requires = "\"root_access\""), "file")
        assertTrue(outcome is SkillInstallService.PlanOutcome.Rejected)
    }

    @Test
    fun `upgrade keeps old version for rollback and rollback restores it`() = runTest {
        val v1 = service.planInstall(payload(version = 1), "file") as SkillInstallService.PlanOutcome.Ready
        service.confirmInstall(v1)
        val v2 = service.planInstall(payload(version = 2), "file") as SkillInstallService.PlanOutcome.Ready
        assertTrue(v2.plan is SkillInstaller.InstallPlan.Upgrade)
        service.confirmInstall(v2)

        assertEquals(2, service.enabledManifests().single().version)
        assertEquals(2, db.installedSkillDao().versionsOf("good-night").size)

        val restored = service.rollback("good-night")
        assertEquals(1, restored)
        assertEquals(1, service.enabledManifests().single().version)
    }

    @Test
    fun `disable stops matching without deleting`() = runTest {
        val ready = service.planInstall(payload(), "file") as SkillInstallService.PlanOutcome.Ready
        service.confirmInstall(ready)
        service.setEnabled("good-night", false)
        assertEquals(0, service.enabledManifests().size)
        assertEquals(1, db.installedSkillDao().count())
    }

    @Test
    fun `downgrade is rejected`() = runTest {
        val v2 = service.planInstall(payload(version = 2), "file") as SkillInstallService.PlanOutcome.Ready
        service.confirmInstall(v2)
        val outcome = service.planInstall(payload(version = 1), "file")
        assertTrue(outcome is SkillInstallService.PlanOutcome.Rejected)
    }
}
