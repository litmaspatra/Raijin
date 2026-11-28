package com.raijin.app.audio

import kotlin.math.sqrt

/**
 * VO-2: decides when an utterance has started and ended from raw 16 kHz PCM16 frames.
 * Pure logic (no Android), so it is unit-tested. Energy based: the first frames set
 * the noise floor, speech is a clear step above it, the end is a stretch of quiet.
 */
class EnergyEndpointer(
    private val frameMs: Int = 30,
    private val noiseFrames: Int = 8,
    private val endSilenceMs: Int = 1200,
    private val noSpeechTimeoutMs: Int = 8000,
    private val maxTotalMs: Int = 20000,
    private val minRms: Double = 350.0,
) {
    enum class State { WAITING, SPEAKING, DONE, NO_SPEECH }

    private var frames = 0
    private var floorSum = 0.0
    private var floor = 0.0
    private var spoke = false
    private var quietMs = 0
    var state = State.WAITING
        private set

    fun feed(frame: ShortArray, len: Int = frame.size): State {
        if (state == State.DONE || state == State.NO_SPEECH) return state
        frames++
        val rms = rms(frame, len)
        val elapsed = frames * frameMs
        if (frames <= noiseFrames) {
            // Average (not max) so the tap/beep at the start does not raise the bar,
            // and cap it so a noisy room cannot hide normal speech.
            floorSum += rms
            floor = minOf(floorSum / frames, MAX_FLOOR)
            return state
        }
        val threshold = maxOf(floor * 3.0, minRms)
        if (rms > threshold) {
            spoke = true
            quietMs = 0
            state = State.SPEAKING
        } else if (spoke) {
            quietMs += frameMs
            if (quietMs >= endSilenceMs) state = State.DONE
        }
        if (state == State.WAITING && elapsed >= noSpeechTimeoutMs) state = State.NO_SPEECH
        if (state == State.SPEAKING && elapsed >= maxTotalMs) state = State.DONE
        return state
    }

    companion object {
        private const val MAX_FLOOR = 450.0

        fun rms(frame: ShortArray, len: Int = frame.size): Double {
            if (len == 0) return 0.0
            var sum = 0.0
            for (i in 0 until len) sum += frame[i].toDouble() * frame[i]
            return sqrt(sum / len)
        }

        fun toFloat(pcm: ShortArray, len: Int = pcm.size): FloatArray =
            FloatArray(len) { pcm[it] / 32768f }
    }
}
