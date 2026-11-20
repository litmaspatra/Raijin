package io.raijin.brain

import io.raijin.brain.skills.SkillToolMapper
import io.raijin.contract.ActionArgs
import io.raijin.contract.ActionType
import io.raijin.contract.MusicCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillToolMapperTest {

    @Test
    fun `alarm with combined time maps`() {
        val result = SkillToolMapper.map("SET_ALARM", mapOf("time" to "6:30", "label" to "wake"))
        assertIs<SkillToolMapper.MapResult.Ok>(result)
        val alarm = result.call.args as ActionArgs.Alarm
        assertEquals(6, alarm.hour)
        assertEquals(30, alarm.minute)
        assertEquals("wake", alarm.label)
    }

    @Test
    fun `alarm with split hour minute maps`() {
        val result = SkillToolMapper.map("SET_ALARM", mapOf("hour" to "18", "minute" to "5"))
        assertIs<SkillToolMapper.MapResult.Ok>(result)
        assertEquals(ActionArgs.Alarm(18, 5), result.call.args)
    }

    @Test
    fun `alarm rejects malformed and out of range`() {
        assertIs<SkillToolMapper.MapResult.Error>(SkillToolMapper.map("SET_ALARM", mapOf("time" to "soon")))
        assertIs<SkillToolMapper.MapResult.Error>(SkillToolMapper.map("SET_ALARM", mapOf("time" to "25:00")))
        assertIs<SkillToolMapper.MapResult.Error>(SkillToolMapper.map("SET_ALARM", emptyMap()))
    }

    @Test
    fun `timer maps seconds and minutes`() {
        val s = SkillToolMapper.map("SET_TIMER", mapOf("seconds" to "90"))
        assertEquals(ActionArgs.Timer(90), (s as SkillToolMapper.MapResult.Ok).call.args)
        val m = SkillToolMapper.map("SET_TIMER", mapOf("minutes" to "5"))
        assertEquals(ActionArgs.Timer(300), (m as SkillToolMapper.MapResult.Ok).call.args)
    }

    @Test
    fun `timer rejects junk`() {
        assertIs<SkillToolMapper.MapResult.Error>(SkillToolMapper.map("SET_TIMER", mapOf("seconds" to "lots")))
        assertIs<SkillToolMapper.MapResult.Error>(SkillToolMapper.map("SET_TIMER", mapOf("seconds" to "0")))
    }

    @Test
    fun `music transport maps by command name`() {
        val result = SkillToolMapper.map("MUSIC_CONTROL", mapOf("command" to "pause"))
        assertIs<SkillToolMapper.MapResult.Ok>(result)
        assertEquals(ActionArgs.Music(MusicCommand.PAUSE), result.call.args)
        assertEquals(ActionType.MUSIC_CONTROL, result.call.actionType)
    }

    @Test
    fun `play query requires the query`() {
        assertIs<SkillToolMapper.MapResult.Error>(
            SkillToolMapper.map("MUSIC_CONTROL", mapOf("command" to "PLAY_QUERY")),
        )
        val ok = SkillToolMapper.map("MUSIC_CONTROL", mapOf("command" to "PLAY_QUERY", "query" to "arijit singh"))
        assertIs<SkillToolMapper.MapResult.Ok>(ok)
    }

    @Test
    fun `unknown music command rejected`() {
        assertIs<SkillToolMapper.MapResult.Error>(
            SkillToolMapper.map("MUSIC_CONTROL", mapOf("command" to "SHUFFLE_ALL")),
        )
    }

    @Test
    fun `registry tools outside the supported subset are rejected, never guessed`() {
        val result = SkillToolMapper.map("DELETE_FILES", emptyMap())
        assertIs<SkillToolMapper.MapResult.Error>(result)
        assertTrue(result.reason.contains("not supported"))
        assertIs<SkillToolMapper.MapResult.Error>(SkillToolMapper.map("CALL_CONTACT", mapOf("name" to "x")))
    }
}
