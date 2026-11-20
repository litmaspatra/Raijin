package com.raijin.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Receives BOOT_COMPLETED and prompts the user to re-open Raijin.
 *
 * Android 14+ (API 34) forbids starting a FOREGROUND_SERVICE_MICROPHONE from
 * a BroadcastReceiver that was triggered by a boot event. The safe path is to
 * post a notification so the user can tap to launch MainActivity, which then
 * starts the service from the foreground.
 *
 * On older API levels the service is started directly (unchanged behaviour).
 *
 * F4 note: Funtouch OS — Settings.canDrawOverlays() can return true while the
 * background pop-up toggle is separately off; handle in MainActivity.
 */
class RaijinBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: cannot start mic foreground service from boot receiver.
            // Post a tap-to-launch notification instead.
            Log.d("RaijinBoot", "Boot completed (API 34+) — posting tap-to-launch notification")
            postTapToLaunchNotification(context)
        } else {
            Log.d("RaijinBoot", "Boot completed — starting service")
            val serviceIntent = Intent(context, RaijinService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    private fun postTapToLaunchNotification(context: Context) {
        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, RaijinApp.CHANNEL_ISLAND)
            .setContentTitle("Raijin")
            .setContentText("Tap to start the island")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_BOOT, notification)
    }

    companion object {
        private const val NOTIF_ID_BOOT = 2
    }
}
