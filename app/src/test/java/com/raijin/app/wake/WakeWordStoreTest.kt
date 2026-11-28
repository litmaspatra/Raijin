package com.raijin.app.wake

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WakeWordStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun offByDefaultAndPersists() {
        context.getSharedPreferences("raijin_wake_word", 0).edit().clear().commit()
        val store = WakeWordStore(context)
        assertFalse(store.load().enabled)
        assertTrue(store.load().onlyScreenOnOrCharging)
        store.save(store.load().copy(enabled = true, score = 9f))
        assertTrue(store.load().enabled)
        assertEquals(4.0f, store.load().score, 0.001f)
    }

    @Test fun keywordFileUsesCurrentName() {
        val dir = File(context.cacheDir, "kwtest").apply { mkdirs() }
        val m = "\u2581"
        File(dir, WakeAssets.PIECES).writeText("${m}HE\t-4.0\nY\t-3.0\n${m}EV\t-9.0\nA\t-2.0\n")
        val f = WakeAssets.writeKeywords(dir, "Eva", 1.5f)
        assertNotNull(f)
        assertEquals("${m}HE Y ${m}EV A :1.5 #0.25", f!!.readText().trim())
        assertEquals(null, WakeAssets.writeKeywords(dir, "Zq", 1.5f))
    }

    @Test fun notAvailableWithoutBundledModel() {
        assertFalse(WakeAssets.bundled(context))
    }
}
