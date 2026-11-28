package com.raijin.app.wake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.raijin.app.prefs.UserPreferencesStore
import java.io.File

/**
 * Listens for "hey <name>" while the assistant service is running. Audio is processed in memory
 * on the phone by [KeywordEngine] and discarded; nothing is stored or sent. Does nothing unless
 * the owner turned it on, the model is bundled, and the microphone permission is granted. When it
 * hears the phrase it releases the microphone and calls [onWake] on the main thread.
 */
class WakeWordListener(
    context: Context,
    private val isBusy: () -> Boolean,
    private val onWake: () -> Unit,
) {
    private val app = context.applicationContext
    private val store = WakeWordStore(app)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "raijin-wake").also { it.isDaemon = true; it.start() }
    }

    @Synchronized fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun eligible(cfg: WakeWordConfig): Boolean {
        if (!cfg.enabled || !WakeAssets.available(app)) return false
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (isBusy()) return false
        if (!cfg.onlyScreenOnOrCharging) return true
        val screenOn = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return screenOn || bm.isCharging
    }

    private fun loop() {
        var engine: KeywordEngine? = null
        var engineKey = ""
        var dir: File? = null
        try {
            while (running) {
                val cfg = store.load()
                if (!eligible(cfg)) { engine?.release(); engine = null; engineKey = ""; Thread.sleep(3000); continue }
                val name = UserPreferencesStore(app).assistantName
                val key = "$name|${cfg.score}"
                if (engine == null || key != engineKey) {
                    engine?.release(); engine = null
                    val d = dir ?: WakeAssets.materialize(app).also { dir = it }
                    val kw = WakeAssets.writeKeywords(d, name, cfg.score)
                    if (kw == null) { Log.w(TAG, "name cannot be tokenised"); Thread.sleep(10000); continue }
                    engine = Class.forName(WakeAssets.ENGINE_CLASS)
                        .getConstructor(File::class.java, File::class.java, Float::class.javaPrimitiveType)
                        .newInstance(d, kw, cfg.score) as KeywordEngine
                    engineKey = key
                }
                if (listenOnce(engine!!, cfg)) {
                    engine.release(); engine = null; engineKey = ""
                    main.post(onWake)
                    Thread.sleep(2000)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            Log.w(TAG, "wake loop stopped: ${e.message}")
        } finally {
            engine?.release()
        }
    }

    /** Records until the phrase is heard (true) or the listening conditions change (false). */
    @Suppress("MissingPermission")
    private fun listenOnce(engine: KeywordEngine, cfg: WakeWordConfig): Boolean {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); Thread.sleep(5000); return false }
        val shorts = ShortArray(1600)
        var checks = 0
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n <= 0) return false
                val f = FloatArray(n) { shorts[it] / 32768f }
                if (engine.feed(f)) return true
                if (++checks % 20 == 0 && (!eligible(store.load()) || store.load() != cfg)) return false
            }
            return false
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private companion object { const val TAG = "WakeWord" }
}
