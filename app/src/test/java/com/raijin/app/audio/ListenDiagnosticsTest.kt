package com.raijin.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class ListenDiagnosticsTest {
    @Test fun describesSilencedBackgroundMic() {
        val s = ListenDiagnostics.describe(3, 40, 40, null, true, 400, false)
        assertTrue(s.contains("recorder recording"))
        assertTrue(s.contains("40 reads (40 all-zero)"))
        assertTrue(s.contains("silenced the mic: YES"))
        assertTrue(s.contains("background (400)"))
        assertTrue(s.contains("mic service type: no"))
    }

    @Test fun describesReadErrorAndUnknowns() {
        val s = ListenDiagnostics.describe(null, 0, 0, -3, null, null, true)
        assertTrue(s.contains("recorder never created"))
        assertTrue(s.contains("read error -3"))
        assertTrue(s.contains("silenced the mic: unknown"))
        assertTrue(s.contains("app is unknown"))
    }

    @Test fun foregroundIsNamed() {
        assertTrue(ListenDiagnostics.describe(3, 5, 0, null, false, 100, true).contains("app is foreground,"))
    }
}
