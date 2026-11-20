package com.raijin.app.audio

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * Regression tests for the "island listens exactly once" defect class:
 * every voice session must leave the engine able to start a fresh session,
 * whether it ended in a result, an error, an empty transcript, or a cancel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class STTEngineRearmTest {

    private lateinit var engine: STTEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        // SpeechRecognizer.isRecognitionAvailable() queries for a recognition
        // service; Robolectric's package manager has none by default.
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent("android.speech.RecognitionService"),
            ResolveInfo().apply {
                serviceInfo = android.content.pm.ServiceInfo().apply {
                    packageName = context.packageName
                    name = "FakeRecognitionService"
                }
            },
        )
        engine = STTEngine(context, CoroutineScope(Dispatchers.Main))
    }

    private fun resultsBundle(vararg texts: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(texts.toList()))
    }

    private fun latestRecognizer(): SpeechRecognizer =
        ShadowSpeechRecognizer.getLatestSpeechRecognizer()

    private fun latestShadow(): ShadowSpeechRecognizer =
        Shadow.extract(latestRecognizer())

    @Test
    fun resultSessionRearms_freshRecognizerOnNextStart() {
        val results = mutableListOf<String>()
        engine.startListening(onResult = { results.add(it) })
        ShadowLooper.idleMainLooper()

        val first = latestRecognizer()
        latestShadow().triggerOnResults(resultsBundle("open github"))

        assertEquals(listOf("open github"), results)
        assertTrue("first recognizer must be destroyed after its result", Shadow.extract<ShadowSpeechRecognizer>(first).isDestroyed)

        engine.startListening(onResult = {})
        ShadowLooper.idleMainLooper()

        val second = latestRecognizer()
        assertNotSame("a new session must create a fresh recognizer", first, second)
        assertFalse("second recognizer must be live", latestShadow().isDestroyed)
        assertTrue("second session must call startListening", latestShadow().lastRecognizerIntent != null)
    }

    @Test
    fun errorSessionRearms_nextStartCreatesFreshRecognizer() {
        val errors = mutableListOf<Int>()
        engine.startListening(onResult = {}, onError = { errors.add(it) })
        ShadowLooper.idleMainLooper()

        val first = latestRecognizer()
        latestShadow().triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)

        assertEquals(listOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS), errors)

        engine.startListening(onResult = {}, onError = { errors.add(it) })
        ShadowLooper.idleMainLooper()

        assertNotSame(first, latestRecognizer())
        assertFalse(latestShadow().isDestroyed)
        assertEquals("no spurious error from rearm", 1, errors.size)
    }

    @Test
    fun emptyResultMapsToSpeechTimeoutAndRearms() {
        val errors = mutableListOf<Int>()
        engine.startListening(onResult = {}, onError = { errors.add(it) })
        ShadowLooper.idleMainLooper()

        latestShadow().triggerOnResults(resultsBundle())
        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), errors)

        engine.startListening(onResult = {})
        ShadowLooper.idleMainLooper()
        assertFalse(latestShadow().isDestroyed)
    }

    @Test
    fun stopCancelsActiveSession() {
        engine.startListening(onResult = {})
        ShadowLooper.idleMainLooper()

        val first = latestRecognizer()
        engine.stop()
        ShadowLooper.idleMainLooper()

        assertTrue("stop() must destroy the active recognizer", Shadow.extract<ShadowSpeechRecognizer>(first).isDestroyed)
    }

    @Test
    fun partialResultsAreForwarded() {
        val partials = mutableListOf<String>()
        engine.startListening(onResult = {}, onPartial = { partials.add(it) })
        ShadowLooper.idleMainLooper()

        latestShadow().triggerOnPartialResults(resultsBundle("open gi"))
        assertEquals(listOf("open gi"), partials)
    }

    @Test
    fun doubleStartDoesNotLeakPreviousRecognizer() {
        engine.startListening(onResult = {})
        ShadowLooper.idleMainLooper()
        val first = latestRecognizer()

        engine.startListening(onResult = {})
        ShadowLooper.idleMainLooper()

        assertTrue("starting a new session must tear down the previous one",
            Shadow.extract<ShadowSpeechRecognizer>(first).isDestroyed)
        assertNotSame(first, latestRecognizer())
    }
}
