package com.raijin.app.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.raijin.app.crash.CrashCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashCaptureTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun uncaughtExceptionIsWrittenWithLastStep() {
        CrashCapture.clear(context)
        CrashCapture.breadcrumb(context, "speech model: loading native")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
            CrashCapture.install(context)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        val r = CrashCapture.read(context)
        assertNotNull(r)
        assertTrue(r!!.contains("IllegalStateException: boom"))
        assertTrue(r.contains("last step: speech model: loading native"))
    }

    @Test fun clearRemovesReport() {
        CrashCapture.writeReport(context, "x")
        assertEquals("x", CrashCapture.read(context))
        CrashCapture.clear(context)
        assertNull(CrashCapture.read(context))
    }

    @Test fun reportIsCapped() {
        CrashCapture.writeReport(context, "a".repeat(20000))
        assertTrue(CrashCapture.read(context)!!.length <= 6000)
    }
}
