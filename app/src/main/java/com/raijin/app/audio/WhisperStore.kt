package com.raijin.app.audio

import android.content.Context

/** VO-2 switch for on-device Whisper speech recognition. Off by default; nothing leaves the phone either way. */
class WhisperStore(context: Context) {
    private val prefs = context.getSharedPreferences("raijin_whisper", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()
}
