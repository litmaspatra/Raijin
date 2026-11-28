package com.raijin.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-command STT engine.
 *
 * Captures user voice commands single-shot (one utterance -> one result -> stops).
 * Automatically adapts to the device locale (English / Hindi / multilingual)
 * so commands like "call Maa on speaker" or "open WhatsApp" are transcribed accurately.
 *
 * Must be used on the main thread (Android SpeechRecognizer requirement).
 */
class STTEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "STTEngine"
        private const val MAX_SILENCE_MS = 2500L
    }

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Begin capturing a voice command. Single-shot.
     */
    fun startListening(
        onResult:  (String) -> Unit,
        onPartial: (String) -> Unit = {},
        onError:   (Int)    -> Unit = {},
        onAlternatives: ((List<String>) -> Unit)? = null,
    ) {
        scope.launch(Dispatchers.Main) {
            stopInternal()
            if (WhisperStore(context).enabled && !WhisperNative.available) {
                WhisperStore(context).lastProblem = "engine library not in this build"
                createAndStart(onResult, onPartial, onError, onAlternatives)
            } else if (WhisperStore(context).enabled && !WhisperEngine.modelBundled(context)) {
                WhisperStore(context).lastProblem = "speech model not in this build"
                createAndStart(onResult, onPartial, onError, onAlternatives)
            } else if (WhisperStore(context).enabled) {
                startWhisper(onResult, onPartial, onError, onAlternatives)
            } else {
                createAndStart(onResult, onPartial, onError, onAlternatives)
            }
        }
    }

    /** True when the next listen uses on-device Whisper (it needs more time than the system recognizer). */
    fun usesWhisper(): Boolean = WhisperStore(context).enabled && WhisperEngine.isAvailable(context)

    /** Immediately cancel any ongoing recognition session. */
    fun stop() {
        scope.launch(Dispatchers.Main) {
            stopInternal()
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private val whisper by lazy { WhisperEngine(context, scope) }

    /** VO-2: on-device Whisper path. No partials; the result arrives after the speaker stops. */
    private fun startWhisper(
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit,
        onError: (Int) -> Unit,
        onAlternatives: ((List<String>) -> Unit)?,
    ) {
        val lang = when (com.raijin.app.prefs.UserPreferencesStore(context).explicitLanguage) {
            com.raijin.app.prefs.UserPreferencesStore.Language.HINDI -> "hi"
            com.raijin.app.prefs.UserPreferencesStore.Language.ENGLISH -> "en"
            null -> if (Locale.getDefault().language == "hi") "hi" else "en"
        }
        whisper.startListening(
            language = lang,
            onResult = { text -> if (onAlternatives != null) onAlternatives(listOf(text)) else onResult(text) },
            onError = { code ->
                val engineFailure = code == SttFailure.MIC_UNAVAILABLE || code == SttFailure.TRANSCRIBE_FAILED
                if (engineFailure) {
                    // On-device speech broke (not just silence): say why in the log and settings,
                    // then hand the same listen to the system recognizer instead of stopping.
                    Log.w(TAG, "Whisper failed (${WhisperStore(context).lastProblem}); falling back to system recognizer")
                    scope.launch(Dispatchers.Main) {
                        stopInternal()
                        createAndStart(onResult, onPartial, onError, onAlternatives)
                    }
                } else {
                    onError(code)
                }
            },
        )
    }

    private fun createAndStart(
        onResult:  (String) -> Unit,
        onPartial: (String) -> Unit,
        onError:   (Int)    -> Unit,
        onAlternatives: ((List<String>) -> Unit)?,
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            onError(SttFailure.NO_RECOGNIZER)
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                    isListening = true
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech began")
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "End of speech detected")
                    isListening = false
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "STT error: $error")
                    isListening = false
                    stopInternal()
                    onError(error)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?: emptyList()

                    val best = matches.firstOrNull()?.trim().orEmpty()
                    Log.d(TAG, "STT result: \"$best\" (${matches.size} alternatives)")

                    stopInternal()

                    if (best.isNotEmpty()) {
                        if (onAlternatives != null) onAlternatives(matches)
                        else onResult(best)
                    } else {
                        onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim()
                        .orEmpty()

                    if (partial.isNotEmpty()) {
                        Log.v(TAG, "Partial: $partial")
                        onPartial(partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            sr.startListening(buildIntent())
        }
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

        // ME-5: an explicitly chosen language wins over the device locale.
        val defaultTag = when (com.raijin.app.prefs.UserPreferencesStore(context).explicitLanguage) {
            com.raijin.app.prefs.UserPreferencesStore.Language.HINDI -> "hi-IN"
            com.raijin.app.prefs.UserPreferencesStore.Language.ENGLISH -> "en-IN"
            null -> if (Locale.getDefault().language == "hi") "hi-IN" else "en-IN"
        }
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, defaultTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, defaultTag)
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)

        putExtra(
            RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES,
            arrayListOf(defaultTag, "en-US", "en-IN", "hi-IN"),
        )

        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, MAX_SILENCE_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, MAX_SILENCE_MS)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
    }

    private fun stopInternal() {
        whisper.stop()
        recognizer?.let { sr ->
            try {
                sr.stopListening()
                sr.cancel()
                sr.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying speech recognizer: ${e.message}")
            }
        }
        recognizer = null
        isListening = false
    }
}
