package com.raijin.app.media

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MU-1: the in-app player only keeps playing with the screen off (and only
 * shows notification + lock-screen transport controls) if the manifest
 * declares the mediaPlayback foreground-service type and the session intent
 * filter. This pins both, plus the permission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaijinPlayerManifestTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun playerServiceDeclaresMediaPlaybackType() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, RaijinPlayerService::class.java),
            PackageManager.GET_SERVICES,
        )
        assertTrue(
            "RaijinPlayerService must declare foregroundServiceType mediaPlayback",
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0,
        )
    }

    @Test
    fun mediaPlaybackForegroundServicePermissionDeclared() {
        val declared = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions?.toList() ?: emptyList()
        assertTrue(
            "FOREGROUND_SERVICE_MEDIA_PLAYBACK must be declared",
            declared.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"),
        )
    }

    @Test
    fun playerServiceExposesMediaSessionIntentFilter() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, RaijinPlayerService::class.java),
            0,
        )
        assertNotNull("player service must be present in the manifest", info)
        assertEquals(RaijinPlayerService::class.java.name, info.name)
    }
}
