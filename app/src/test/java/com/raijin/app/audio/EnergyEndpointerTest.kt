package com.raijin.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyEndpointerTest {
    private fun frame(amp: Int) = ShortArray(480) { if (it % 2 == 0) amp.toShort() else (-amp).toShort() }
    private val quiet = frame(30)
    private val loud = frame(3000)

    private fun EnergyEndpointer.feedN(f: ShortArray, n: Int) { repeat(n) { feed(f) } }

    @Test fun speechThenSilenceEnds() {
        val ep = EnergyEndpointer()
        ep.feedN(quiet, 10)
        ep.feedN(loud, 20)
        assertEquals(EnergyEndpointer.State.SPEAKING, ep.state)
        ep.feedN(quiet, 50)
        assertEquals(EnergyEndpointer.State.DONE, ep.state)
    }

    @Test fun silenceOnlyTimesOutWithNoSpeech() {
        val ep = EnergyEndpointer()
        ep.feedN(quiet, 400)
        assertEquals(EnergyEndpointer.State.NO_SPEECH, ep.state)
    }

    @Test fun shortPauseDoesNotEndUtterance() {
        val ep = EnergyEndpointer()
        ep.feedN(quiet, 10)
        ep.feedN(loud, 10)
        ep.feedN(quiet, 20)
        ep.feedN(loud, 10)
        assertEquals(EnergyEndpointer.State.SPEAKING, ep.state)
    }

    @Test fun nonStopSpeechIsCappedAtMax() {
        val ep = EnergyEndpointer()
        ep.feedN(quiet, 10)
        ep.feedN(loud, 800)
        assertEquals(EnergyEndpointer.State.DONE, ep.state)
    }

    @Test fun pcmConversionScales() {
        val f = EnergyEndpointer.toFloat(shortArrayOf(16384, -32768))
        assertTrue(f[0] == 0.5f && f[1] == -1.0f)
    }
}
