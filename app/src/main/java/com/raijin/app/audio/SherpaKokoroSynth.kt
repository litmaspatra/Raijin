package com.raijin.app.audio

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * sherpa-onnx (Apache-2.0) running the bundled Kokoro int8 model on the CPU.
 * Compiled only when CI (or a developer) put the sherpa-onnx AAR in app/libs. Loaded by
 * name from [KokoroAudioClient] so builds without it still compile and run.
 * No network code: model, voices and word list come from the app's own files.
 */
class SherpaKokoroSynth(dir: File) : KokoroSynth {
    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, KokoroAssets.MODEL).absolutePath,
                    voices = File(dir, KokoroAssets.VOICES).absolutePath,
                    tokens = File(dir, KokoroAssets.TOKENS).absolutePath,
                    lexicon = File(dir, KokoroAssets.LEXICON).absolutePath,
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu",
            ),
        ),
    )

    override fun synthesize(text: String, sid: Int, speed: Float): SynthAudio {
        val audio = tts.generate(text = text, sid = sid, speed = speed)
        return SynthAudio(audio.samples, audio.sampleRate)
    }

    override fun release() {
        runCatching { tts.free() }
    }
}
