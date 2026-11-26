package com.raijin.app.audio

import java.util.Locale

/** Plain description of an installed system TTS voice, so ranking is unit-testable. */
data class VoiceOption(
    val name: String,
    val languageTag: String,
    /** android.speech.tts.Voice quality: 100 very low ... 500 very high. */
    val quality: Int,
    val needsNetwork: Boolean,
    val installed: Boolean = true,
) {
    val language: String get() = Locale.forLanguageTag(languageTag).language
    val region: String get() = Locale.forLanguageTag(languageTag).country

    fun qualityLabel(): String = when {
        quality >= 500 -> "very high"
        quality >= 400 -> "high"
        quality >= 300 -> "normal"
        else -> "low"
    }

    fun label(): String {
        val place = Locale.forLanguageTag(languageTag).getDisplayName(Locale.ENGLISH)
        val net = if (needsNetwork) ", online" else ""
        return "$place (${qualityLabel()}$net) - $name"
    }
}

/** Picks the best installed system voice and honors an explicit owner choice. */
object VoiceChooser {

    /** Best voice for [language] ("en", "hi"): offline and installed first, then quality, then [preferredRegion]. */
    fun best(voices: List<VoiceOption>, language: String, preferredRegion: String = "IN"): VoiceOption? {
        val inLang = voices.filter { it.language == language && it.installed }
        val pool = inLang.filter { !it.needsNetwork }.ifEmpty { inLang }
        return pool.sortedWith(
            compareByDescending<VoiceOption> { it.quality }
                .thenByDescending { it.region.equals(preferredRegion, ignoreCase = true) }
                .thenBy { it.name },
        ).firstOrNull()
    }

    /** Owner's saved choice when it still exists and matches the language, otherwise [best]. */
    fun resolve(
        voices: List<VoiceOption>,
        language: String,
        chosenName: String?,
        preferredRegion: String = "IN",
    ): VoiceOption? {
        val chosen = chosenName?.let { n ->
            voices.firstOrNull { it.name == n && it.installed && it.language == language }
        }
        return chosen ?: best(voices, language, preferredRegion)
    }
}
