package com.raijin.app.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Kokoro Neural Voice Synthesis Client.
 *
 * Uses the Kokoro-82M text-to-speech specification (OpenAI /v1/audio/speech format)
 * to deliver ultra-natural sci-fi companion voices (Karen: af_heart, F.R.I.D.A.Y.: bf_emma,
 * Jarvis: bm_george, etc.).
 *
 * Supports hosted Kokoro endpoints (DeepInfra, HuggingFace, Fal.ai, or custom local/Docker).
 */
data class KokoroVoiceConfig(
    val endpointUrl: String = "https://api.deepinfra.com/v1/openai/audio/speech",
    val apiKey: String = "",
    val voice: String = DEFAULT_VOICE,
    val speed: Float = 1.0f,
    val enabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_VOICE = "af_heart" // Karen style: warm expressive female

        val AVAILABLE_VOICES = listOf(
            KokoroVoiceOption("af_heart", "Karen // Warm American Female (Default)"),
            KokoroVoiceOption("bf_emma", "F.R.I.D.A.Y. // British Female"),
            KokoroVoiceOption("af_bella", "Bella // Upbeat Female"),
            KokoroVoiceOption("af_sarah", "Sarah // Crisp Female"),
            KokoroVoiceOption("am_adam", "Adam // Direct American Male"),
            KokoroVoiceOption("bm_george", "Jarvis // British Male"),
        )
    }
}

data class KokoroVoiceOption(val id: String, val label: String)

class KokoroAudioClient(
    private val context: Context,
    private val configProvider: () -> KokoroVoiceConfig,
) {
    companion object {
        private const val TAG = "KokoroAudioClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activePlayer: MediaPlayer? = null
    private var activeTempFile: File? = null

    val isAvailable: Boolean
        get() {
            val cfg = configProvider()
            return cfg.enabled && cfg.endpointUrl.isNotBlank()
        }

    /**
     * Synthesizes audio using Kokoro voices and plays it through MediaPlayer.
     * [onDone] is invoked on the main thread with true if the audio was synthesized and
     * played to completion, false if anything failed.
     */
    fun speak(text: String, onDone: ((Boolean) -> Unit)? = null) {
        val config = configProvider()
        if (!config.enabled || config.endpointUrl.isBlank()) {
            onDone?.invoke(false)
            return
        }

        clientScope.launch {
            val tempAudioFile = File(context.cacheDir, "kokoro_cache_${System.currentTimeMillis()}.mp3")
            try {
                val payload = JSONObject().apply {
                    put("model", "kokoro")
                    put("input", text)
                    put("voice", config.voice)
                    put("speed", config.speed.toDouble())
                    put("response_format", "mp3")
                }

                val reqBuilder = Request.Builder()
                    .url(config.endpointUrl)
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Content-Type", "application/json")

                if (config.apiKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
                }

                Log.d(TAG, "Requesting Kokoro speech [voice: ${config.voice}] for: '${text.take(40)}...'")

                val downloaded = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Kokoro synthesis returned HTTP ${resp.code}: ${resp.body?.string()?.take(100)}")
                        false
                    } else {
                        val inputStream = resp.body?.byteStream()
                        if (inputStream == null) {
                            false
                        } else {
                            FileOutputStream(tempAudioFile).use { out -> inputStream.copyTo(out) }
                            true
                        }
                    }
                }

                if (!downloaded) {
                    tempAudioFile.delete()
                    withContext(Dispatchers.Main) { onDone?.invoke(false) }
                    return@launch
                }

                withContext(Dispatchers.Main) { playAudioFile(tempAudioFile, onDone) }
            } catch (e: Exception) {
                Log.w(TAG, "Kokoro audio generation failed: ${e.message}")
                tempAudioFile.delete()
                withContext(Dispatchers.Main) { onDone?.invoke(false) }
            }
        }
    }

    /** Must be called on the main thread. */
    private fun playAudioFile(audioFile: File, onDone: ((Boolean) -> Unit)?) {
        var player: MediaPlayer? = null
        try {
            stop()
            activeTempFile = audioFile
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )
                setDataSource(audioFile.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { mp ->
                    mp.release()
                    if (activePlayer === mp) activePlayer = null
                    audioFile.delete()
                    if (activeTempFile == audioFile) activeTempFile = null
                    onDone?.invoke(true)
                }
                setOnErrorListener { mp, what, extra ->
                    Log.w(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    mp.release()
                    if (activePlayer === mp) activePlayer = null
                    audioFile.delete()
                    if (activeTempFile == audioFile) activeTempFile = null
                    onDone?.invoke(false)
                    true
                }
                prepareAsync()
            }
            activePlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing synthesized audio: ${e.message}", e)
            try { player?.release() } catch (_: Exception) {}
            activePlayer = null
            audioFile.delete()
            if (activeTempFile == audioFile) activeTempFile = null
            onDone?.invoke(false)
        }
    }

    fun stop() {
        try {
            activePlayer?.stop()
        } catch (_: Exception) {}
        try {
            activePlayer?.release()
        } catch (_: Exception) {}
        activePlayer = null
        try {
            activeTempFile?.delete()
        } catch (_: Exception) {}
        activeTempFile = null
    }

    fun shutdown() {
        stop()
        clientScope.cancel()
    }
}

/**
 * Storage for Kokoro Voice persona configuration. Non-secret fields stay in
 * SharedPreferences; the API key is Keystore-encrypted (PRD section 6), with a
 * one-time migration from the legacy plaintext value.
 */
class KokoroConfigStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("raijin_kokoro_voice", Context.MODE_PRIVATE)
    private val secrets = com.raijin.app.cloud.KeystoreSecretStore()

    fun load(): KokoroVoiceConfig {
        return KokoroVoiceConfig(
            endpointUrl = prefs.getString("endpoint_url", "https://api.deepinfra.com/v1/openai/audio/speech") ?: "https://api.deepinfra.com/v1/openai/audio/speech",
            apiKey = loadApiKey(),
            voice = prefs.getString("voice", KokoroVoiceConfig.DEFAULT_VOICE) ?: KokoroVoiceConfig.DEFAULT_VOICE,
            speed = prefs.getFloat("speed", 1.0f),
            enabled = prefs.getBoolean("enabled", true),
        )
    }

    private fun loadApiKey(): String {
        secrets.decrypt(prefs.getString("api_key_enc", null))?.let { return it }
        val legacy = prefs.getString("api_key", "") ?: ""
        if (legacy.isNotBlank()) {
            secrets.encrypt(legacy)?.let { enc ->
                prefs.edit().putString("api_key_enc", enc).remove("api_key").apply()
            }
            return legacy
        }
        return ""
    }

    fun save(config: KokoroVoiceConfig) {
        val encryptedKey = secrets.encrypt(config.apiKey.trim())
        prefs.edit()
            .putString("endpoint_url", config.endpointUrl.trim())
            .putString("voice", config.voice.trim())
            .putFloat("speed", config.speed)
            .putBoolean("enabled", config.enabled)
            .apply {
                if (encryptedKey != null) putString("api_key_enc", encryptedKey) else remove("api_key_enc")
                remove("api_key") // never keep a plaintext copy
            }
            .apply()
    }
}
