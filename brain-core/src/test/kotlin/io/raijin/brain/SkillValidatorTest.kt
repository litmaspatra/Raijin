package io.raijin.brain

import io.raijin.brain.skills.Condition
import io.raijin.brain.skills.SkillEgress
import io.raijin.brain.skills.SkillManifest
import io.raijin.brain.skills.SkillStep
import io.raijin.brain.skills.SkillTriggers
import io.raijin.brain.skills.SkillValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillValidatorTest {

    private val tools = setOf("SET_ALARM", "CREATE_REMINDER", "MUSIC_CONTROL")
    private val capabilities = setOf("exact_alarms", "notifications")

    private fun goodNightSkill(steps: List<SkillStep> = listOf(
        SkillStep.CallTool("SET_ALARM", mapOf("time" to "06:30")),
        SkillStep.Speak("Good night"),
    )) = SkillManifest(
        id = "good-night",
        version = 1,
        name = "Good night",
        description = "Alarm 6:30 plus a good night line",
        triggers = SkillTriggers(phrases = listOf("good night")),
        steps = steps,
        requires = listOf("exact_alarms"),
        egress = SkillEgress.L0_LOCAL,
        sha256 = "a".repeat(64),
    )

    @Test
    fun `valid manifest passes`() {
        assertEquals(emptyList(), SkillValidator.validate(goodNightSkill(), tools, capabilities))
    }

    @Test
    fun `unknown tool is a validation error, never rewritten`() {
        val skill = goodNightSkill(steps = listOf(SkillStep.CallTool("DELETE_FILES")))
        val errors = SkillValidator.validate(skill, tools, capabilities)
        assertEquals(1, errors.size)
        assertIs<SkillValidator.SkillError.UnknownTool>(errors[0])
        assertEquals("DELETE_FILES", (errors[0] as SkillValidator.SkillError.UnknownTool).tool)
    }

    @Test
    fun `unknown tool nested inside repeat and ifelse is still caught`() {
        val skill = goodNightSkill(
            steps = listOf(
                SkillStep.Repeat(
                    3,
                    listOf(
                        SkillStep.IfElse(
                            Condition.IsSet("x"),
                            then = listOf(SkillStep.CallTool("HACK_EVERYTHING")),
                        ),
                    ),
                ),
            ),
        )
        val errors = SkillValidator.validate(skill, tools, capabilities)
        assertTrue(errors.any { it is SkillValidator.SkillError.UnknownTool })
    }

    @Test
    fun `unknown required capability is rejected`() {
        val skill = goodNightSkill().copy(requires = listOf("root_access"))
        val errors = SkillValidator.validate(skill, tools, capabilities)
        assertTrue(errors.any { it is SkillValidator.SkillError.UnknownCapability })
    }

    @Test
    fun `unbounded loop is rejected`() {
        val skill = goodNightSkill(
            steps = listOf(SkillStep.Repeat(10_000, listOf(SkillStep.Speak("hi")))),
        )
        val errors = SkillValidator.validate(skill, tools, capabilities)
        assertTrue(errors.any { it is SkillValidator.SkillError.RepeatOutOfBounds })
    }

    @Test
    fun `excessive wait is rejected`() {
        val skill = goodNightSkill(steps = listOf(SkillStep.WaitMillis(3_600_000)))
        val errors = SkillValidator.validate(skill, tools, capabilities)
        assertTrue(errors.any { it is SkillValidator.SkillError.WaitOutOfBounds })
    }

    @Test
    fun `malformed hash is rejected`() {
        val skill = goodNightSkill().copy(sha256 = "XYZ")
        val errors = SkillValidator.validate(skill, tools, capabilities)
        assertTrue(errors.any { it is SkillValidator.SkillError.BadSha256 })
    }

    @Test
    fun `unsigned draft is allowed at schema level`() {
        val skill = goodNightSkill().copy(sha256 = null)
        assertEquals(emptyList(), SkillValidator.validate(skill, tools, capabilities))
    }

    @Test
    fun `triggerless skill is rejected`() {
        val skill = goodNightSkill().copy(triggers = SkillTriggers())
        val errors = SkillValidator.validate(skill, tools, capabilities)
        assertTrue(errors.any { it is SkillValidator.SkillError.EmptyTriggers })
    }

    @Test
    fun `bad trigger regex is rejected`() {
        val skill = goodNightSkill().copy(triggers = SkillTriggers(regexes = listOf("([")))
        val errors = SkillValidator.validate(skill, tools, capabilities)
        assertTrue(errors.any { it is SkillValidator.SkillError.BadRegex })
    }
}
