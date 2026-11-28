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

class EnergyEndpointerFloorTest {
    private fun frame(amp: Int) = ShortArray(480) { if (it % 2 == 0) amp.toShort() else (-amp).toShort() }

    @Test fun tapNoiseAtStartDoesNotHideSpeech() {
        val ep = EnergyEndpointer()
        repeat(2) { ep.feed(frame(6000)) } // tap/beep burst in the first frames
        repeat(6) { ep.feed(frame(30)) }
        repeat(15) { ep.feed(frame(2500)) } // normal speech
        assertEquals(EnergyEndpointer.State.SPEAKING, ep.state)
    }

    @Test fun diagnosticsReportPeakFloorAndThreshold() {
        val ep = EnergyEndpointer()
        val quiet = ShortArray(480) { 100 }
        val loud = ShortArray(480) { 3000 }
        ep.feedN(quiet, 8)
        ep.feed(loud)
        assertEquals(9 * 30, ep.heardMs)
        assertEquals(3000.0, ep.peakRms, 1.0)
        assertEquals(100.0, ep.noiseFloor, 1.0)
        assertEquals(350.0, ep.speechThreshold, 1.0)
    }
}
