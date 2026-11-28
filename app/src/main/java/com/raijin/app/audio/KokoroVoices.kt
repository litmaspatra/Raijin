package com.raijin.app.audio

enum class VoiceGender { FEMALE, MALE }

/** One bundled on-device Kokoro voice. [sid] is its index in the trimmed voices file. */
data class KokoroVoice(val id: String, val sid: Int, val label: String, val gender: VoiceGender)

/** The two voices bundled with the app. A rename can later pick a voice by [gender]. */
object KokoroVoices {
    val FEMALE = KokoroVoice("af_heart", 0, "Female (af_heart)", VoiceGender.FEMALE)
    val MALE = KokoroVoice("bm_george", 1, "Male (bm_george)", VoiceGender.MALE)
    val ALL = listOf(FEMALE, MALE)
    val DEFAULT = FEMALE

    fun byId(id: String?): KokoroVoice = ALL.firstOrNull { it.id == id } ?: DEFAULT
    fun forGender(gender: VoiceGender): KokoroVoice = ALL.first { it.gender == gender }
}
