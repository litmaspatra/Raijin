package com.raijin.app.audio

/** Mono PCM audio produced by an on-device synthesizer. */
class SynthAudio(val samples: FloatArray, val sampleRate: Int)

/** Seam between the player and the native engine; the engine class exists only in builds that bundle it. */
interface KokoroSynth {
    fun synthesize(text: String, sid: Int, speed: Float): SynthAudio
    fun release()
}
