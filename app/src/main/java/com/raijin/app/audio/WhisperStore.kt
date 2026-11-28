package com.raijin.app.audio

import android.content.Context

/** VO-2 switch for on-device Whisper speech recognition. Off by default; nothing leaves the phone either way. */
class WhisperStore(context: Context) {
    private val prefs = context.getSharedPreferences("raijin_whisper", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** Short technical reason of the last on-device speech failure, kept on the phone for the settings card. */
    var lastProblem: String?
        get() = prefs.getString("last_problem", null)
        set(v) = prefs.edit().putString("last_problem", v).apply()

    /** What the microphone did during the last on-device listen (state, reads, silenced, app importance, mic service). */
    var lastListen: String?
        get() = prefs.getString("last_listen", null)
        set(v) = prefs.edit().putString("last_listen", v).apply()
}
