package com.raijin.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.raijin.app.skill.AppSkillRuntime
import com.raijin.app.skill.SkillInstallService
import com.raijin.app.skill.SkillJsonParser
import com.raijin.app.skill.SkillRunner
import io.raijin.brain.skills.SkillRunResult
import io.raijin.contract.ActionArgs
import io.raijin.contract.ActionResult
import io.raijin.contract.ActionType
import io.raijin.contract.ActuatorPort
import io.raijin.contract.DispatchResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkillRunnerTest {

    private lateinit var db: RaijinDatabase
    private lateinit var installService: SkillInstallService
    private lateinit var runner: SkillRunner
    private val dispatched = mutableListOf<DispatchResult>()
    private val spoken = mutableListOf<String>()
    private var actuatorFails = false

    private val fakeActuator = object : ActuatorPort {
        override suspend fun execute(action: DispatchResult): ActionResult {
            dispatched += action
            return if (actuatorFails) ActionResult.FAILED("no clock app") else ActionResult.EXECUTED()
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RaijinDatabase::class.java,
        ).build()
        installService = SkillInstallService(db)
        runner = SkillRunner(installService, db.skillRunLogDao())
        dispatched.clear()
        spoken.clear()
        actuatorFails = false
    }

    @After
    fun tearDown() = db.close()

    private fun runtime(traceId: String = "t1") = AppSkillRuntime(
        actuator = fakeActuator,
        traceId = traceId,
        speakLine = { spoken += it },
    )

    private suspend fun install(payload: String) {
        val ready = installService.planInstall(payload, "file") as SkillInstallService.PlanOutcome.Ready
        check(installService.confirmInstall(ready))
    }

    private fun goodNightPayload(): String {
        val template = """
            {
              "id": "good-night", "version": 1, "name": "Good night",
              "triggers": { "phrases": ["good night"] },
              "steps": [
                { "type": "call_tool", "tool": "SET_ALARM", "args": { "time": "6:30", "label": "wake" } },
                { "type": "speak", "text": "Good night, boss" }
              ],
              "requires": ["alarms"], "egress_class": "l0"
            }
        """.trimIndent()
        val hash = SkillInstallService.sha256Hex(SkillJsonParser.canonicalPayload(template)!!)
        val cut = template.lastIndexOf('}')
        return template.substring(0, cut) + ", \"sha256\": \"$hash\" }"
    }

    @Test
    fun `matching utterance runs the skill against the real actuator port`() = runTest {
        install(goodNightPayload())
        val result = runner.tryRun("okay good night", runtime())
        assertTrue(result is SkillRunResult.Completed)
        assertEquals(1, dispatched.size)
        assertEquals(ActionType.SET_ALARM, dispatched[0].actionType)
        assertEquals(ActionArgs.Alarm(6, 30, label = "wake"), dispatched[0].args)
        assertEquals(listOf("Good night, boss"), spoken)
    }

    @Test
    fun `non-matching utterance returns null so routing continues`() = runTest {
        install(goodNightPayload())
        assertNull(runner.tryRun("what is the weather", runtime()))
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `disabled skill does not match`() = runTest {
        install(goodNightPayload())
        installService.setEnabled("good-night", false)
        assertNull(runner.tryRun("good night", runtime()))
    }

    @Test
    fun `actuator failure ends the run failed, log preserved`() = runTest {
        install(goodNightPayload())
        actuatorFails = true
        val result = runner.tryRun("good night", runtime())
        assertTrue(result is SkillRunResult.FailedAt)
        assertTrue((result as SkillRunResult.FailedAt).reason.contains("no clock app"))
        assertTrue(result.log.isNotEmpty())
    }

    @Test
    fun `runs are written to the persistent run log`() = runTest {
        install(goodNightPayload())
        runner.tryRun("good night", runtime())
        val rows = db.skillRunLogDao().recent()
        assertEquals(1, rows.size)
        assertEquals("good-night", rows[0].skill_id)
        assertEquals("completed", rows[0].outcome)
    }

    @Test
    fun `ask_user skill fails closed instead of fabricating an answer`() = runTest {
        val template = """
            {
              "id": "asky", "version": 1, "name": "asky",
              "triggers": { "phrases": ["ask me"] },
              "steps": [ { "type": "ask_user", "prompt": "Who?", "store_as": "p" } ]
            }
        """.trimIndent()
        val hash = SkillInstallService.sha256Hex(SkillJsonParser.canonicalPayload(template)!!)
        val cut = template.lastIndexOf('}')
        install(template.substring(0, cut) + ", \"sha256\": \"$hash\" }")

        val result = runner.tryRun("ask me", runtime())
        assertTrue(result is SkillRunResult.FailedAt)
        assertTrue((result as SkillRunResult.FailedAt).reason.contains("SK-3"))
    }

    @Test
    fun `ask_user resolves through a wired ask channel`() = runTest {
        val template = """
            {
              "id": "asky2", "version": 1, "name": "asky2",
              "triggers": { "phrases": ["who do I call"] },
              "steps": [
                { "type": "ask_user", "prompt": "Who?", "store_as": "p" },
                { "type": "speak", "text": "calling {p}" }
              ]
            }
        """.trimIndent()
        val hash = SkillInstallService.sha256Hex(SkillJsonParser.canonicalPayload(template)!!)
        val cut = template.lastIndexOf('}')
        install(template.substring(0, cut) + ", \"sha256\": \"$hash\" }")

        val bridged = AppSkillRuntime(
            actuator = fakeActuator,
            traceId = "t1",
            speakLine = { spoken += it },
            askHandler = { "Rahul" },
        )
        val result = runner.tryRun("who do I call", bridged)
        assertTrue(result is SkillRunResult.Completed)
        assertEquals(listOf("calling Rahul"), spoken)
    }
}
