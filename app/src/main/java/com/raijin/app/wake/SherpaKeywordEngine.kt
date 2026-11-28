package com.raijin.app.wake

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * sherpa-onnx (Apache-2.0) keyword spotting with a small streaming zipformer. Compiled only when
 * the sherpa-onnx AAR is in app/libs; loaded by name from [WakeWordListener]. No network code.
 */
class SherpaKeywordEngine(dir: File, keywords: File, score: Float) : KeywordEngine {
    private val spotter = KeywordSpotter(
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, WakeAssets.ENCODER).absolutePath,
                    decoder = File(dir, WakeAssets.DECODER).absolutePath,
                    joiner = File(dir, WakeAssets.JOINER).absolutePath,
                ),
                tokens = File(dir, WakeAssets.TOKENS).absolutePath,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
            maxActivePaths = 4,
            keywordsFile = keywords.absolutePath,
            keywordsScore = score,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 1,
        ),
    )
    private var stream = spotter.createStream()

    override fun feed(samples: FloatArray): Boolean {
        stream.acceptWaveform(samples, 16000)
        var hit = false
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotBlank()) {
                spotter.reset(stream)
                hit = true
            }
        }
        return hit
    }

    override fun release() {
        runCatching { stream.release() }
        runCatching { spotter.release() }
    }
}
