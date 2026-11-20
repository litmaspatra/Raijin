package com.raijin.app

import com.raijin.app.skill.SkillAskBridge
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillAskBridgeTest {

    @Test
    fun `spoken answer resumes the asker`() = runTest {
        val bridge = SkillAskBridge()
        val shown = mutableListOf<String>()
        val asking = async { bridge.ask("Who should I call?", shown::add) }
        // Let ask() register before answering.
        while (!bridge.isAwaiting) kotlinx.coroutines.yield()
        assertEquals(listOf("Who should I call?"), shown)
        assertTrue(bridge.tryAnswer("Rahul"))
        assertEquals("Rahul", asking.await())
        assertFalse(bridge.isAwaiting)
    }

    @Test
    fun `confirm tap counts as yes`() = runTest {
        val bridge = SkillAskBridge()
        val asking = async { bridge.ask("Send it?") {} }
        while (!bridge.isAwaiting) kotlinx.coroutines.yield()
        assertTrue(bridge.confirm())
        assertEquals("yes", asking.await())
    }

    @Test
    fun `cancel fails closed`() = runTest {
        val bridge = SkillAskBridge()
        val asking = async {
            runCatching { bridge.ask("Anything?") {} }
        }
        while (!bridge.isAwaiting) kotlinx.coroutines.yield()
        bridge.cancel()
        assertTrue(asking.await().isFailure)
    }

    @Test
    fun `timeout fails closed`() = runTest {
        val bridge = SkillAskBridge(timeoutMs = 1_000)
        val result = runCatching { bridge.ask("Anyone there?") {} }
        assertTrue(result.isFailure)
        assertFalse(bridge.isAwaiting)
    }

    @Test
    fun `an unanswered utterance is not consumed`() = runTest {
        val bridge = SkillAskBridge()
        assertFalse(bridge.tryAnswer("random words"))
    }
}
