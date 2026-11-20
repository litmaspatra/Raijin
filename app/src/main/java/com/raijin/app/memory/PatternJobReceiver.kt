package com.raijin.app.memory

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.raijin.app.RaijinDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * ME-3: daily pattern job. A self-rescheduling inexact alarm
 * (setAndAllowWhileIdle - no exact-alarm permission needed, the work is not
 * time-critical) fires once a day in the early morning, mines the last 30
 * days of the interaction log into stored patterns, then schedules the next
 * run. Scheduled from RaijinApp.onCreate so an app start or a boot-completed
 * service start both keep the chain alive.
 */
class PatternJobReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = RaijinDatabase.getInstance(context)
                val stored = PatternMiner(db.interactionLogDao(), db.memoryPatternDao(), memoryDao = db.memoryDao()).mineAndPersist()
                Log.d(TAG, "pattern job stored/refreshed $stored patterns")
            } catch (t: Throwable) {
                Log.w(TAG, "pattern job failed: ${t.message}")
            } finally {
                scheduleNext(context)
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PatternJob"
        const val ACTION_RUN = "com.raijin.app.action.PATTERN_JOB"
        private const val REQUEST_CODE = 4107

        /** Next 03:00 local time (tomorrow if 03:00 already passed today). */
        fun nextRunMillis(nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
            val today = LocalDate.now(zone)
            val runTime = LocalTime.of(3, 0)
            var candidate = runTime.atDate(today).atZone(zone).toInstant().toEpochMilli()
            if (candidate <= nowMillis) {
                candidate = runTime.atDate(today.plusDays(1)).atZone(zone).toInstant().toEpochMilli()
            }
            return candidate
        }

        fun scheduleNext(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, PatternJobReceiver::class.java).setAction(ACTION_RUN),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextRunMillis(),
                pendingIntent,
            )
        }
    }
}
