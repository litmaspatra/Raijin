package com.raijin.app.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Settings for the on-device English Kokoro voice. Nothing here leaves the phone. */
data class KokoroVoiceConfig(
    val voice: String = KokoroVoices.DEFAULT.id,
    val speed: Float = 1.0f,
    val enabled: Boolean = false,
)

/** File names of the bundled Kokoro files (assets/models/kokoro/, fetched by CI, never committed). */
object KokoroAssets {
    const val ASSET_DIR = "models/kokoro"
    const val MODEL = "model.int8.onnx"
    const val VOICES = "voices.bin"
    const val TOKENS = "tokens.txt"
    const val LEXICON = "lexicon-us-en.txt"
    val ALL = listOf(MODEL, VOICES, TOKENS, LEXICON)
    const val ENGINE_CLASS = "com.raijin.app.audio.SherpaKokoroSynth"

    fun bundled(context: Context): Boolean =
        ALL.all { name -> runCatching { context.assets.open("$ASSET_DIR/$name").use { true } }.getOrDefault(false) }

    fun engineAvailable(): Boolean = runCatching { Class.forName(ENGINE_CLASS); true }.getOrDefault(false)
}

/**
 * On-device Kokoro speech (English only). Synthesizes sentence by sentence with sherpa-onnx and
 * streams the audio to the speaker. If the voice is off, not bundled, or a sentence has a word
 * the word list cannot pronounce, [speak] reports failure so the system voice reads it instead.
 * No network code in this class.
 */
class KokoroAudioClient(
    private val context: Context,
    private val configProvider: () -> KokoroVoiceConfig,
) {
    companion object {
        private const val TAG = "KokoroAudioClient"
        private val lock = Any()
        private var synth: KokoroSynth? = null
        private var lexicon: SpokenLexicon? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var track: AudioTrack? = null

    /** Last short reason the voice fell back to the system voice, for the settings card. */
    @Volatile var lastProblem: String? = null
        private set

    val isAvailable: Boolean
        get() = configProvider().enabled && KokoroAssets.engineAvailable() && KokoroAssets.bundled(context)

    /** [onDone] runs on the main thread: true when the speech played to the end, false if the caller should fall back. */
    fun speak(text: String, onDone: ((Boolean) -> Unit)? = null) {
        val cfg = configProvider()
        if (!cfg.enabled) { onDone?.invoke(false); return }
        stop()
        job = scope.launch {
            val ok = try {
                play(text, cfg)
            } catch (e: Throwable) {
                lastProblem = "${e.javaClass.simpleName}: ${e.message?.take(80)}"
                Log.w(TAG, "Kokoro speech failed: ${e.message}")
                false
            }
            withContext(Dispatchers.Main) { onDone?.invoke(ok) }
        }
    }

    private fun play(text: String, cfg: KokoroVoiceConfig): Boolean {
        val (engine, words) = ensureLoaded() ?: return false
        val unknown = words.unknownWords(text)
        if (unknown.isNotEmpty()) {
            lastProblem = "word not in voice list: ${unknown.first().take(20)}"
            return false
        }
        val voice = KokoroVoices.byId(cfg.voice)
        var out: AudioTrack? = null
        var frames = 0L
        try {
            for (sentence in splitSentences(text)) {
                if (!scope.isActive || job?.isActive == false) return false
                com.raijin.app.crash.CrashCapture.breadcrumb(context, "voice: synthesizing")
                val audio = engine.synthesize(sentence, voice.sid, cfg.speed)
                if (audio.samples.isEmpty()) continue
                if (out == null) {
                    out = newTrack(audio.sampleRate)
                    track = out
                    out.play()
                }
                out.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
                frames += audio.samples.size
            }
            val t = out ?: return false
            val deadline = System.currentTimeMillis() + 60_000
            while (t.playbackHeadPosition < frames && System.currentTimeMillis() < deadline && job?.isActive == true) {
                Thread.sleep(40)
            }
            lastProblem = null
            return true
        } finally {
            runCatching { out?.stop() }
            runCatching { out?.release() }
            if (track === out) track = null
        }
    }

    private fun newTrack(sampleRate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, sampleRate * 4))
            .build()
    }

    /** Copies the bundled files to private storage once, loads the engine and the word list. */
    private fun ensureLoaded(): Pair<KokoroSynth, SpokenLexicon>? = synchronized(lock) {
        val s = synth
        val l = lexicon
        if (s != null && l != null) return s to l
        if (!KokoroAssets.engineAvailable() || !KokoroAssets.bundled(context)) {
            lastProblem = "voice not bundled in this build"
            return null
        }
        val dir = File(context.filesDir, "kokoro").apply { mkdirs() }
        for (name in KokoroAssets.ALL) {
            val path = "${KokoroAssets.ASSET_DIR}/$name"
            AssetFiles.ensureCopied(context, path, File(dir, name))
        }
        com.raijin.app.crash.CrashCapture.breadcrumb(context, "voice: loading native")
        val engine = Class.forName(KokoroAssets.ENGINE_CLASS).getConstructor(File::class.java).newInstance(dir) as KokoroSynth
        val words = File(dir, KokoroAssets.LEXICON).useLines { SpokenLexicon.parse(it) }
        synth = engine
        lexicon = words
        com.raijin.app.crash.CrashCapture.breadcrumb(context, "voice: loaded")
        engine to words
    }

    fun stop() {
        job?.cancel()
        job = null
        val t = track
        track = null
        runCatching { t?.pause(); t?.flush(); t?.stop(); t?.release() }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }
}

/** Splits text into sentences so playback can start after the first one is ready. */
internal fun splitSentences(text: String): List<String> =
    text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

/** Stores the on-device voice settings. Off by default; a stored hosted-endpoint key from older builds is erased. */
class KokoroConfigStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("raijin_kokoro_voice", Context.MODE_PRIVATE)

    init {
        if (prefs.contains("api_key_enc") || prefs.contains("api_key") || prefs.contains("endpoint_url")) {
            prefs.edit().remove("api_key_enc").remove("api_key").remove("endpoint_url").remove("enabled").apply()
        }
    }

    fun load(): KokoroVoiceConfig = KokoroVoiceConfig(
        voice = KokoroVoices.byId(prefs.getString("voice", null)).id,
        speed = prefs.getFloat("speed", 1.0f),
        enabled = prefs.getBoolean("local_enabled", false),
    )

    fun save(config: KokoroVoiceConfig) {
        prefs.edit()
            .putString("voice", KokoroVoices.byId(config.voice).id)
            .putFloat("speed", config.speed)
            .putBoolean("local_enabled", config.enabled)
            .apply()
    }
}
