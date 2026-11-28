package com.raijin.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * VO-2: on-device speech to text with the bundled Whisper base (q5_1) model.
 * Records one utterance from the microphone, transcribes it on the CPU, returns the text.
 * Audio and text never leave the phone: no network code here.
 */
class WhisperEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "WhisperEngine"
        const val MODEL_ASSET = "models/ggml-base-q5_1.bin"
        private const val SAMPLE_RATE = 16000
        private val lock = Any()
        private var handle = 0L

        /** True when this build has both the native library and the bundled model. */
        fun isAvailable(context: Context): Boolean = WhisperNative.available && modelBundled(context)

        fun modelBundled(context: Context): Boolean =
            runCatching { context.assets.open(MODEL_ASSET).use { true } }.getOrDefault(false)
    }

    private var job: Job? = null

    private fun ensureLoaded(): Long = synchronized(lock) {
        if (handle != 0L) return handle
        val dir = File(context.filesDir, "whisper").apply { mkdirs() }
        val model = File(dir, File(MODEL_ASSET).name)
        val expected = context.assets.openFd(MODEL_ASSET).use { it.length }
        if (!model.exists() || model.length() != expected) {
            val tmp = File(dir, model.name + ".part")
            context.assets.open(MODEL_ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            check(tmp.renameTo(model)) { "model copy failed" }
        }
        handle = WhisperNative.nativeInit(model.absolutePath)
        check(handle != 0L) { "model load failed" }
        handle
    }

    /** [language]: "en", "hi" or "auto". Callbacks arrive on the main thread. */
    @SuppressLint("MissingPermission")
    fun startListening(language: String, onResult: (String) -> Unit, onError: (Int) -> Unit) {
        stop()
        job = scope.launch(Dispatchers.Default) {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val frameSize = SAMPLE_RATE * 30 / 1000
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, frameSize * 8) * 2,
                )
            } catch (e: Exception) {
                Log.w(TAG, "mic init failed: ${e.javaClass.simpleName}")
                withContext(Dispatchers.Main) { onError(SttFailure.MIC_UNAVAILABLE) }
                return@launch
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                withContext(Dispatchers.Main) { onError(SttFailure.MIC_UNAVAILABLE) }
                return@launch
            }
            val pcm = ArrayList<ShortArray>()
            val ep = EnergyEndpointer()
            try {
                rec.startRecording()
                val buf = ShortArray(frameSize)
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val chunk = buf.copyOf(n)
                    pcm += chunk
                    val s = ep.feed(chunk, n)
                    if (s == EnergyEndpointer.State.DONE || s == EnergyEndpointer.State.NO_SPEECH) break
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
            if (!isActive) return@launch
            if (ep.state != EnergyEndpointer.State.DONE) {
                withContext(Dispatchers.Main) { onError(SttFailure.NO_SPEECH) }
                return@launch
            }
            val total = pcm.sumOf { it.size }
            val all = ShortArray(total)
            var o = 0
            for (c in pcm) { c.copyInto(all, o); o += c.size }
            var failure = SttFailure.TRANSCRIBE_FAILED
            var ranOk = false
            val text = try {
                val h = ensureLoaded()
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                WhisperNative.nativeTranscribe(h, EnergyEndpointer.toFloat(all), language, threads).trim().also { ranOk = true }
            } catch (e: Throwable) {
                Log.w(TAG, "transcribe failed: ${e.javaClass.simpleName}")
                failure = SttFailure.TRANSCRIBE_FAILED
                ""
            }
            withContext(Dispatchers.Main) {
                if (text.isNotEmpty()) onResult(text)
                else onError(if (failure == SttFailure.TRANSCRIBE_FAILED && ranOk) SttFailure.EMPTY_TRANSCRIPT else failure)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
