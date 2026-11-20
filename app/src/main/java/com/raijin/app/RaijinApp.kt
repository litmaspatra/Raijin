package com.raijin.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class RaijinApp : Application() {

    companion object {
        const val CHANNEL_ISLAND = "raijin_island"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // ME-3: keep the daily pattern-mining chain alive.
        com.raijin.app.memory.PatternJobReceiver.scheduleNext(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ISLAND,
                "Raijin Island",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description  = "Keeps the Raijin island alive"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
        )
    }
}
