package com.raijin.app

import com.raijin.app.skill.SkillAuthoringService
import io.raijin.brain.skills.SkillRunResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class SkillAuthoringServiceTest {

    private val service = SkillAuthoringService()

    private val goodNightJson = """
        {
          "id": "good-night", "version": 1, "name": "Good night",
          "description": "alarm + greeting",
          "triggers": { "phrases": ["good night"] },
          "steps": [
            { "type": "call_tool", "tool": "SET_ALARM", "args": { "time": "6:30", "label": "wake" } },
            { "type": "speak", "text": "Good night" }
          ],
          "requires": ["alarms"], "egress_class": "l0"
        }
    """.trimIndent()

    @Test
    fun `extractSpec matches prefixes case-insensitively`() {
        assertEquals("when I say x, do y", SkillAuthoringService.extractSpec("make a skill: when I say x, do y"))
        assertEquals("when I say x, do y", SkillAuthoringService.extractSpec("  Make A Skill: when I say x, do y  "))
        assertEquals("when I say x, do y", SkillAuthoringService.extractSpec("create a skill: when I say x, do y"))
    }

    @Test
    fun `extractSpec rejects non-authoring utterances and blank specs`() {
        assertNull(SkillAuthoringService.extractSpec("set an alarm for 6:30"))
        assertNull(SkillAuthoringService.extractSpec("make a skill:"))
        assertNull(SkillAuthoringService.extractSpec("make a skill:   "))
    }

    @Test
    fun `draft signs a valid cloud draft and previews it without executing`() = runTest {
        val outcome = service.draft(service.prepareSpec("when I say good night, set alarm 6:30")) { _, _ ->
            "Here is your skill:\n```json\n$goodNightJson\n```"
        }
        assertTrue(outcome is SkillAuthoringService.DraftOutcome.Ok)
        val ok = outcome as SkillAuthoringService.DraftOutcome.Ok
        assertTrue(ok.payloadJson.contains("\"sha256\""))
        assertEquals("good-night", ok.manifest.id)
        assertTrue(ok.preview is SkillRunResult.Completed)
        val log = (ok.preview as SkillRunResult.Completed).log
        assertEquals(2, log.size)
        assertTrue(log[0].contains("(dry run)"))
    }

    @Test
    fun `draft rejects a response with no json`() = runTest {
        val outcome = service.draft(service.prepareSpec("anything")) { _, _ -> "I cannot help with that." }
        assertTrue(outcome is SkillAuthoringService.DraftOutcome.Error)
    }

    @Test
    fun `draft surfaces validator errors for unknown tools`() = runTest {
        val bad = goodNightJson.replace("SET_ALARM", "FLY_TO_MOON")
        val outcome = service.draft(service.prepareSpec("fly")) { _, _ -> bad }
        assertTrue(outcome is SkillAuthoringService.DraftOutcome.Error)
        assertTrue((outcome as SkillAuthoringService.DraftOutcome.Error).reasons.any { it.contains("FLY_TO_MOON") })
    }

    @Test
    fun `draft reports cloud failure honestly`() = runTest {
        val outcome = service.draft(service.prepareSpec("anything")) { _, _ -> throw IllegalStateException("offline") }
        assertTrue(outcome is SkillAuthoringService.DraftOutcome.Error)
        assertTrue((outcome as SkillAuthoringService.DraftOutcome.Error).reasons[0].contains("offline"))
    }

    @Test
    fun `cloud receives the redacted spec, not the raw one`() = runTest {
        var sent: String? = null
        val outcome = service.draft(service.prepareSpec("remind me to call +91 98765 43210 at 6")) { prompt, _ ->
            sent = prompt
            goodNightJson
        }
        assertTrue(outcome is SkillAuthoringService.DraftOutcome.Ok)
        assertTrue(sent!!.contains("[redacted]"))
        assertTrue(!sent!!.contains("98765"))
        val ok = outcome as SkillAuthoringService.DraftOutcome.Ok
        assertTrue(ok.redactedSpec.contains("[redacted]"))
        assertTrue(ok.originalSpec.contains("98765")) // original preserved locally
    }

    @Test
    fun `signed draft passes the real install plan`() = runTest {
        val ok = service.draft(service.prepareSpec("spec")) { _, _ -> goodNightJson } as SkillAuthoringService.DraftOutcome.Ok
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            RaijinDatabase::class.java,
        ).build()
        try {
            val service = com.raijin.app.skill.SkillInstallService(db)
            val plan = service.planInstall(ok.payloadJson, "voice")
            assertTrue(plan is com.raijin.app.skill.SkillInstallService.PlanOutcome.Ready)
        } finally {
            db.close()
        }
    }
}
