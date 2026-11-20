package com.raijin.app

import com.raijin.app.skill.SkillJsonParser
import io.raijin.brain.skills.Condition
import io.raijin.brain.skills.SkillEgress
import io.raijin.brain.skills.SkillStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillJsonParserTest {

    private val goodNight = """
        {
          "id": "good-night",
          "version": 1,
          "name": "Good night",
          "description": "Alarm plus DND",
          "triggers": { "phrases": ["good night"], "regexes": ["so raha"] },
          "parameters": [
            { "name": "time", "type": "time_of_day", "required": false, "default": "06:30" }
          ],
          "steps": [
            { "type": "call_tool", "tool": "SET_ALARM", "args": { "time": "{time}" } },
            { "type": "speak", "text": "Good night" }
          ],
          "requires": ["exact_alarms"],
          "egress_class": "l0",
          "sha256": "${"a".repeat(64)}"
        }
    """.trimIndent()

    @Test
    fun `full manifest parses`() {
        val result = SkillJsonParser.parse(goodNight)
        assertTrue(result is SkillJsonParser.ParseResult.Ok)
        val m = (result as SkillJsonParser.ParseResult.Ok).manifest
        assertEquals("good-night", m.id)
        assertEquals(1, m.version)
        assertEquals(SkillEgress.L0_LOCAL, m.egress)
        assertEquals(2, m.steps.size)
        assertEquals("SET_ALARM", (m.steps[0] as SkillStep.CallTool).tool)
        assertEquals("{time}", (m.steps[0] as SkillStep.CallTool).args["time"])
        assertEquals(1, m.parameters.size)
        assertEquals("06:30", m.parameters[0].default)
    }

    @Test
    fun `nested if and repeat parse`() {
        val json = """
            {
              "id": "s", "version": 1, "name": "s", "steps": [
                { "type": "repeat", "times": 2, "steps": [
                  { "type": "if", "condition": { "kind": "is_set", "key": "x" },
                    "then": [ { "type": "speak", "text": "yes" } ],
                    "else": [ { "type": "speak", "text": "no" } ] }
                ] }
              ]
            }
        """.trimIndent()
        val result = SkillJsonParser.parse(json)
        assertTrue(result is SkillJsonParser.ParseResult.Ok)
        val repeat = ((result as SkillJsonParser.ParseResult.Ok).manifest.steps[0] as SkillStep.Repeat)
        assertEquals(2, repeat.times)
        val ifelse = repeat.steps[0] as SkillStep.IfElse
        assertEquals(Condition.IsSet("x"), ifelse.condition)
        assertEquals(1, ifelse.then.size)
        assertEquals(1, ifelse.otherwise.size)
    }

    @Test
    fun `broken json is a parse error`() {
        assertTrue(SkillJsonParser.parse("{not json") is SkillJsonParser.ParseResult.Error)
    }

    @Test
    fun `unknown step type is rejected with its position`() {
        val json = """
            { "id": "s", "version": 1, "name": "s", "steps": [
              { "type": "speak", "text": "hi" },
              { "type": "run_shell", "tool": "sh" }
            ] }
        """.trimIndent()
        val result = SkillJsonParser.parse(json)
        assertTrue(result is SkillJsonParser.ParseResult.Error)
        assertTrue((result as SkillJsonParser.ParseResult.Error).reason.contains("step 1"))
        assertTrue(result.reason.contains("run_shell"))
    }

    @Test
    fun `unknown egress class is rejected`() {
        val json = """
            { "id": "s", "version": 1, "name": "s", "egress_class": "l9",
              "steps": [ { "type": "speak", "text": "hi" } ] }
        """.trimIndent()
        assertTrue(SkillJsonParser.parse(json) is SkillJsonParser.ParseResult.Error)
    }

    @Test
    fun `unknown parameter type is rejected`() {
        val json = """
            { "id": "s", "version": 1, "name": "s",
              "parameters": [ { "name": "p", "type": "widget" } ],
              "steps": [ { "type": "speak", "text": "hi" } ] }
        """.trimIndent()
        val result = SkillJsonParser.parse(json)
        assertTrue(result is SkillJsonParser.ParseResult.Error)
        assertTrue((result as SkillJsonParser.ParseResult.Error).reason.contains("widget"))
    }

    @Test
    fun `missing id is rejected`() {
        val json = """{ "version": 1, "name": "s", "steps": [ { "type": "speak", "text": "hi" } ] }"""
        assertTrue(SkillJsonParser.parse(json) is SkillJsonParser.ParseResult.Error)
    }
}
