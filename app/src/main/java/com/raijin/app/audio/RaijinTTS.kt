package com.raijin.app.audio

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Tactical TTS Engine with Kokoro Neural Voice Synthesis + Native Offline Fallback.
 *
 * - Primary: Kokoro neural voices (af_heart, bf_emma, am_adam, bm_george, etc.)
 *   for lifelike Karen / F.R.I.D.A.Y. persona voicing.
 * - Fallback: Android system TextToSpeech (for offline operation or Hindi utterances).
 * - Skips speaking if ringer is silent.
 * - Calls [onDone] when the utterance finishes (or fails).
 */
class RaijinTTS(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "RaijinTTS"

        /** Devanagari Unicode block: U+0900–U+097F */
        private fun isHindi(text: String): Boolean =
            text.any { it.code in 0x0900..0x097F }
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val kokoroConfigStore = KokoroConfigStore(context)
    val kokoroClient = KokoroAudioClient(context, configProvider = { kokoroConfigStore.load() })

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                Log.d(TAG, "Native Android TTS engine ready")
            } else {
                Log.e(TAG, "Native Android TTS init failed: $status")
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                // ConcurrentHashMap rejects null keys, so guard the nullable id.
                utteranceId?.let { utteranceCallbacks.remove(it) }?.invoke(true)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { utteranceCallbacks.remove(it) }?.invoke(false)
            }
        })
    }

    private val utteranceCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()

    fun speak(text: String, onDone: ((Boolean) -> Unit)? = null) {
        // Skip if silent/vibrate
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            Log.d(TAG, "Ringer silent — skipping speech synthesis")
            onDone?.invoke(true)
            return
        }

        // If English and Kokoro is configured, try Kokoro neural synthesis first
        if (kokoroClient.isAvailable && !isHindi(text)) {
            Log.d(TAG, "Attempting Kokoro voice synthesis...")
            kokoroClient.speak(text) { success ->
                if (success) {
                    onDone?.invoke(true)
                } else {
                    Log.d(TAG, "Kokoro synthesis unavailable, falling back to native TTS")
                    speakWithNativeTts(text, onDone)
                }
            }
            return
        }

        speakWithNativeTts(text, onDone)
    }

    private fun speakWithNativeTts(text: String, onDone: ((Boolean) -> Unit)?) {
        if (!ready) {
            Log.w(TAG, "Native TTS not ready")
            onDone?.invoke(false)
            return
        }

        val locale = if (isHindi(text)) Locale("hi", "IN") else Locale.ENGLISH
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language not supported: $locale — falling back to English")
            tts?.setLanguage(Locale.ENGLISH)
        }

        val id = UUID.randomUUID().toString()
        if (onDone != null) utteranceCallbacks[id] = onDone

        applyOwnerVoice(locale)

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Owner's speed, pitch and voice (or the best installed voice) for this utterance. */
    private fun applyOwnerVoice(locale: Locale) {
        val engine = tts ?: return
        try {
            val prefs = com.raijin.app.prefs.UserPreferencesStore(appContext)
            engine.setSpeechRate(prefs.ttsSpeed)
            engine.setPitch(prefs.ttsPitch)
            val installed = engine.voices.orEmpty()
            val options = installed.map {
                VoiceOption(
                    name = it.name,
                    languageTag = it.locale.toLanguageTag(),
                    quality = it.quality,
                    needsNetwork = it.isNetworkConnectionRequired,
                    installed = !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
                )
            }
            val pick = VoiceChooser.resolve(options, locale.language, prefs.systemVoice)
            installed.firstOrNull { it.name == pick?.name }?.let { engine.voice = it }
        } catch (e: Exception) {
            Log.w(TAG, "Could not apply owner voice settings: ${e.message}")
        }
    }

    /** True while the native engine is mid-utterance. */
    val isSpeaking: Boolean get() = tts?.isSpeaking == true

    fun stop() {
        utteranceCallbacks.clear()
        kokoroClient.stop()
        tts?.stop()
    }

    fun shutdown() {
        utteranceCallbacks.clear()
        kokoroClient.shutdown()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
