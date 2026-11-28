package com.raijin.app.audio

/** JNI bridge to whisper.cpp (VO-2). Present only in builds that bundled the native library. */
internal object WhisperNative {
    val available: Boolean = try {
        System.loadLibrary("raijin_whisper"); true
    } catch (_: Throwable) {
        false
    }

    @JvmStatic external fun nativeInit(modelPath: String): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, threads: Int): String
}
