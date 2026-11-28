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
        com.raijin.app.crash.CrashCapture.breadcrumb(context, "speech model: copying")
        AssetFiles.ensureCopied(context, MODEL_ASSET, model)
        com.raijin.app.crash.CrashCapture.breadcrumb(context, "speech model: loading native")
        handle = WhisperNative.nativeInit(model.absolutePath)
        com.raijin.app.crash.CrashCapture.breadcrumb(context, "speech model: loaded")
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
                WhisperStore(context).lastProblem = "mic init failed: ${e.javaClass.simpleName}"
                withContext(Dispatchers.Main) { onError(SttFailure.MIC_UNAVAILABLE) }
                return@launch
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                WhisperStore(context).lastProblem = "recorder not initialised (state ${rec.state})"
                rec.release()
                withContext(Dispatchers.Main) { onError(SttFailure.MIC_UNAVAILABLE) }
                return@launch
            }
            val pcm = ArrayList<ShortArray>()
            val ep = EnergyEndpointer()
            var micProblem: String? = null
            var stateAfterStart: Int? = null
            var reads = 0
            var silentReads = 0
            var negativeRead: Int? = null
            var silenced: Boolean? = null
            try {
                com.raijin.app.crash.CrashCapture.breadcrumb(context, "speech: mic starting")
                rec.startRecording()
                stateAfterStart = rec.recordingState
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    // Some phones need a moment, or refuse (mic busy / blocked in background). Retry once.
                    kotlinx.coroutines.delay(200)
                    rec.stop()
                    rec.startRecording()
                }
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    micProblem = "recorder did not start (state ${rec.recordingState})"
                } else {
                    val buf = ShortArray(frameSize)
                    var zeroReads = 0
                    while (isActive) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n < 0) { negativeRead = n; micProblem = "mic read error $n (state ${rec.recordingState})"; break }
                        if (n == 0) { if (++zeroReads > 40) { micProblem = "mic returned no audio"; break }; continue }
                        zeroReads = 0
                        val chunk = buf.copyOf(n)
                        reads++
                        if (chunk.all { it.toInt() == 0 }) silentReads++
                        if (reads == 10 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            silenced = runCatching { rec.activeRecordingConfiguration?.isClientSilenced }.getOrNull()
                        }
                        pcm += chunk
                        val s = ep.feed(chunk, n)
                        if (s == EnergyEndpointer.State.DONE || s == EnergyEndpointer.State.NO_SPEECH) break
                    }
                }
            } catch (e: Exception) {
                micProblem = "mic start failed: ${e.javaClass.simpleName}"
            } finally {
                runCatching { rec.stop() }
                rec.release()
                val importance = runCatching {
                    val st = android.app.ActivityManager.RunningAppProcessInfo()
                    android.app.ActivityManager.getMyMemoryState(st)
                    st.importance
                }.getOrNull()
                WhisperStore(context).lastListen = ListenDiagnostics.describe(
                    stateAfterStart, reads, silentReads, negativeRead, silenced, importance, MicState.serviceHasMicType,
                )
            }
            if (micProblem != null) {
                Log.w(TAG, micProblem)
                WhisperStore(context).lastProblem = micProblem
                withContext(Dispatchers.Main) { onError(SttFailure.MIC_UNAVAILABLE) }
                return@launch
            }
            com.raijin.app.crash.CrashCapture.breadcrumb(context, "speech: mic stopped, endpoint=${ep.state}")
            val heard = "heard ${ep.heardMs} ms, loudest ${ep.peakRms.toInt()}, noise floor ${ep.noiseFloor.toInt()}, needed above ${ep.speechThreshold.toInt()}"
            if (!isActive) {
                WhisperStore(context).lastProblem = "listening was cancelled before it finished ($heard)"
                return@launch
            }
            if (ep.state != EnergyEndpointer.State.DONE) {
                WhisperStore(context).lastProblem = "no speech detected ($heard)"

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
                com.raijin.app.crash.CrashCapture.breadcrumb(context, "speech: transcribing")
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                WhisperNative.nativeTranscribe(h, EnergyEndpointer.toFloat(all), language, threads).trim().also { ranOk = true }
            } catch (e: Throwable) {
                Log.w(TAG, "transcribe failed: ${e.javaClass.simpleName}")
                WhisperStore(context).lastProblem = "transcribe failed: ${e.javaClass.simpleName} ${e.message?.take(80).orEmpty()}"
                failure = SttFailure.TRANSCRIBE_FAILED
                ""
            }
            withContext(Dispatchers.Main) {
                if (text.isNotEmpty()) { WhisperStore(context).lastProblem = null; onResult(text) }
                else {
                    if (failure == SttFailure.TRANSCRIBE_FAILED && ranOk) WhisperStore(context).lastProblem = "speech model returned no words ($heard)"
                    onError(if (failure == SttFailure.TRANSCRIBE_FAILED && ranOk) SttFailure.EMPTY_TRANSCRIPT else failure)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
