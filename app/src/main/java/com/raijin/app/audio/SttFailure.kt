package com.raijin.app.audio

import android.speech.SpeechRecognizer

/**
 * Turns every speech-recognition failure into an honest reason the user can see and hear.
 * Codes below 1000 are Android's SpeechRecognizer codes; 1000+ are Raijin's own
 * (Whisper path and "no recognizer installed"). Pure mapping, unit-tested.
 */
object SttFailure {
    const val NO_RECOGNIZER = 1001
    const val WHISPER_NO_NATIVE = 1002
    const val WHISPER_NO_MODEL = 1003
    const val MIC_UNAVAILABLE = 1004
    const val TRANSCRIBE_FAILED = 1005
    const val NO_SPEECH = 1006
    const val EMPTY_TRANSCRIPT = 1007

    data class Reason(val text: String, val needsMainActivity: Boolean = false)

    fun describe(code: Int): Reason = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            Reason("The microphone permission is off for Raijin. Allow Microphone in Android settings for Raijin.", true)
        SpeechRecognizer.ERROR_AUDIO, MIC_UNAVAILABLE ->
            Reason("I could not open the microphone. Another app may be using it, or Android blocked it while Raijin was in the background. Open Raijin once and try again.", true)
        SpeechRecognizer.ERROR_CLIENT ->
            Reason("The speech recognizer refused to start. Open Raijin once and try again.")
        NO_RECOGNIZER ->
            Reason("This phone has no speech recognition service available. Enable Google speech services, or turn on On-device speech in Raijin settings.")
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            Reason("The system speech recognizer needs the internet and could not reach it. Check your connection, or turn on On-device speech in Raijin settings.")
        SpeechRecognizer.ERROR_SERVER ->
            Reason("The system speech recognizer's server failed. Try again, or turn on On-device speech in Raijin settings.")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            Reason("The speech recognizer is busy with another app. Try again in a moment.")
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            Reason("The speech recognizer does not have this language installed.")
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT, NO_SPEECH ->
            Reason("I did not hear anything. Tap and speak right after it says listening.")
        SpeechRecognizer.ERROR_NO_MATCH, EMPTY_TRANSCRIPT ->
            Reason("I heard sound but could not make out words. Try again a bit closer or slower.")
        WHISPER_NO_NATIVE ->
            Reason("On-device speech could not load its engine in this build.")
        WHISPER_NO_MODEL ->
            Reason("On-device speech has no model in this build.")
        TRANSCRIBE_FAILED ->
            Reason("On-device speech failed while turning your voice into text.")
        else -> Reason("Speech recognition failed (code $code).")
    }
}
