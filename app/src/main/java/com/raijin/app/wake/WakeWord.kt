package com.raijin.app.wake

import android.content.Context
import android.content.SharedPreferences
import io.raijin.brain.UnigramTokenizer
import io.raijin.brain.WakeKeywords
import java.io.File

/** Wake word settings. Off by default: an always-listening microphone costs battery. */
data class WakeWordConfig(
    val enabled: Boolean = false,
    /** Listen only while the screen is on or the phone is charging (saves battery). */
    val onlyScreenOnOrCharging: Boolean = true,
    /** Keyword boost; higher fires more easily (and more falsely). */
    val score: Float = WakeKeywords.DEFAULT_SCORE,
)

class WakeWordStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("raijin_wake_word", Context.MODE_PRIVATE)

    fun load() = WakeWordConfig(
        enabled = prefs.getBoolean("enabled", false),
        onlyScreenOnOrCharging = prefs.getBoolean("only_screen_or_charging", true),
        score = prefs.getFloat("score", WakeKeywords.DEFAULT_SCORE).coerceIn(0.5f, 4.0f),
    )

    fun save(c: WakeWordConfig) {
        prefs.edit().putBoolean("enabled", c.enabled)
            .putBoolean("only_screen_or_charging", c.onlyScreenOnOrCharging)
            .putFloat("score", c.score.coerceIn(0.5f, 4.0f)).apply()
    }
}

/** File names of the bundled keyword-spotting model (assets/models/kws/, fetched by CI, never committed). */
object WakeAssets {
    const val ASSET_DIR = "models/kws"
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val PIECES = "pieces.txt"
    val ALL = listOf(ENCODER, DECODER, JOINER, TOKENS, PIECES)
    const val ENGINE_CLASS = "com.raijin.app.wake.SherpaKeywordEngine"

    fun bundled(context: Context): Boolean =
        ALL.all { n -> runCatching { context.assets.open("$ASSET_DIR/$n").use { true } }.getOrDefault(false) }

    fun engineAvailable(): Boolean = runCatching { Class.forName(ENGINE_CLASS); true }.getOrDefault(false)

    fun available(context: Context) = bundled(context) && engineAvailable()

    /** Copies the model out of the APK once (the engine reads real files) and returns the directory. */
    fun materialize(context: Context): File {
        val dir = File(context.filesDir, "kws").apply { mkdirs() }
        ALL.forEach { n ->
            com.raijin.app.audio.AssetFiles.ensureCopied(context, "$ASSET_DIR/$n", File(dir, n))
        }
        return dir
    }

    /** Writes the keyword file for "hey <name>"; null if the name cannot be tokenised. */
    fun writeKeywords(dir: File, name: String, score: Float): File? {
        val tok = UnigramTokenizer.parse(File(dir, PIECES).readText())
        val line = WakeKeywords.line(name, tok, score) ?: return null
        return File(dir, "keywords.txt").also { it.writeText(line + "\n") }
    }
}

/** Takes 16 kHz mono float audio; true when the wake phrase was heard. */
interface KeywordEngine {
    fun feed(samples: FloatArray): Boolean
    fun release()
}
