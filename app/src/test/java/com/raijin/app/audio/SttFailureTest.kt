package com.raijin.app.audio

import android.speech.SpeechRecognizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttFailureTest {
    @Test fun everyKnownCodeHasASpecificReason() {
        val codes = listOf(
            SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SttFailure.NO_RECOGNIZER, SttFailure.WHISPER_NO_NATIVE, SttFailure.WHISPER_NO_MODEL, SttFailure.MIC_UNAVAILABLE,
            SttFailure.TRANSCRIBE_FAILED, SttFailure.NO_SPEECH, SttFailure.EMPTY_TRANSCRIPT,
        )
        codes.forEach { assertFalse("code $it", SttFailure.describe(it).text.startsWith("Speech recognition failed")) }
    }

    @Test fun unknownCodeNamesTheCode() {
        assertTrue(SttFailure.describe(4242).text.contains("4242"))
    }

    @Test fun micBlockedReasonsReopenTheApp() {
        assertTrue(SttFailure.describe(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).needsMainActivity)
        assertTrue(SttFailure.describe(SttFailure.MIC_UNAVAILABLE).needsMainActivity)
        assertFalse(SttFailure.describe(SpeechRecognizer.ERROR_NO_MATCH).needsMainActivity)
    }
}
