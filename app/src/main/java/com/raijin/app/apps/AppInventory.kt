package com.raijin.app.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.raijin.brain.AppResolver

/**
 * AP-1: cached inventory of installed launchable apps (label + package),
 * refreshed on package add/remove/replace broadcasts instead of scanning
 * PackageManager on every voice command.
 */
object AppInventory {

    private const val TAG = "AppInventory"

    @Volatile
    private var cache: List<AppResolver.AppEntry>? = null

    /** Current inventory; loads on first access. */
    fun get(context: Context): List<AppResolver.AppEntry> =
        cache ?: refresh(context)

    /** Rescan the launcher inventory. Safe to call from any thread. */
    @Synchronized
    fun refresh(context: Context): List<AppResolver.AppEntry> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try {
            pm.queryIntentActivities(launcher, 0).map { info ->
                AppResolver.AppEntry(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(pm).toString(),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "launcher scan failed: ${e.message}")
            cache ?: emptyList()
        }
        cache = apps
        Log.i(TAG, "App inventory refreshed: ${apps.size} launchable apps")
        return apps
    }

    fun invalidate() {
        cache = null
    }
}

/**
 * AP-1: keeps [AppInventory] fresh when apps are installed, removed, or
 * updated while Raijin is not running.
 */
class AppChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                AppInventory.invalidate()
                AppInventory.refresh(context)
            }
        }
    }
}
