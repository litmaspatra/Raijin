package com.raijin.app.morning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.raijin.app.RaijinService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Proactive morning briefing trigger.
 *
 * Listens for [Intent.ACTION_SCREEN_ON] to deliver a sci-fi tactical HUD brief
 * once per day during the morning window (06:00 - 10:00).
 */
class MorningBriefTrigger : BroadcastReceiver() {

    companion object {
        private const val TAG = "MorningBriefTrigger"
        private const val PREFS_NAME = "raijin_morning_brief"
        private const val KEY_LAST_BRIEF_DATE = "last_brief_date"
        private val DATE_FMT = SimpleDateFormat("yyyyMMdd", Locale.US)

        private const val BRIEF_START_HOUR = 6
        private const val BRIEF_END_HOUR = 9 // 06:00 to 09:59
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SCREEN_ON) return

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (hour < BRIEF_START_HOUR || hour > BRIEF_END_HOUR) return
        // ME-5: quiet hours silence proactive speaking.
        if (com.raijin.app.prefs.UserPreferencesStore(context).isQuietHour(hour)) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = DATE_FMT.format(Date())
        val lastBriefDate = prefs.getString(KEY_LAST_BRIEF_DATE, null)
        if (lastBriefDate == today) {
            return
        }

        // Save guard date immediately
        prefs.edit().putString(KEY_LAST_BRIEF_DATE, today).apply()
        Log.i(TAG, "Triggering proactive morning brief for $today")

        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                RaijinService.triggerMorningBrief()
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering morning brief: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }
}
