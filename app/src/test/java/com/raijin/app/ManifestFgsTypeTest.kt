package com.raijin.app

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the "island listens exactly once per app open" bug.
 *
 * Android 12+ cuts mic access for foreground services that lack the
 * `microphone` foregroundServiceType as soon as the app leaves the
 * foreground (after a short grace window). RaijinService declared only
 * specialUse|dataSync, so only the first island listen session after each
 * app open captured speech. The manifest must keep the microphone type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestFgsTypeTest {

    @Test
    fun raijinServiceDeclaresMicrophoneForegroundServiceType() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, RaijinService::class.java),
            0,
        )
        assertTrue(
            "RaijinService must declare foregroundServiceType microphone, otherwise " +
                "background island voice capture dies after one session",
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0,
        )
    }

    @Test
    fun manifestDeclaresForegroundServiceMicrophonePermission() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val pkg = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        assertTrue(
            "FOREGROUND_SERVICE_MICROPHONE permission is required for mic-type FGS",
            pkg.requestedPermissions?.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE") == true,
        )
    }
}
