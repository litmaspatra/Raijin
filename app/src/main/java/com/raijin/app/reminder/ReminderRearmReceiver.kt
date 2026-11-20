package com.raijin.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.raijin.app.RaijinDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * TM-5: re-arm every unfired reminder after events that wipe or shift alarms:
 * boot, time-zone change, wall-clock time change, and app update.
 *
 * AlarmManager alarms do not survive reboot, and a clock/time-zone change can
 * leave an RTC_WAKEUP alarm firing at the wrong wall-clock time. Recurring
 * reminders whose occurrence passed while the phone was off are rolled
 * forward to the next future occurrence (never catch-up spam); one-shot
 * reminders that were missed fire immediately on re-arm, which beats losing
 * them silently.
 */
class ReminderRearmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        Log.i(TAG, "Re-arming reminders after ${intent.action}")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = RaijinDatabase.getInstance(context)
                val unfired = db.reminderDao().findAllUnfired()
                val nowSec = System.currentTimeMillis() / 1000
                var rearmed = 0
                for (reminder in unfired) {
                    val trigger = reminder.triggerTime ?: continue
                    val rearmSec = ReminderBroadcastReceiver.rearmTriggerSeconds(
                        trigger,
                        reminder.recurrenceRule,
                        nowSec,
                    )
                    // Persist rolled-forward recurring triggers so the DB stays
                    // the source of truth for the next re-arm.
                    if (rearmSec != trigger) {
                        db.reminderDao().upsert(reminder.copy(triggerTime = rearmSec))
                    }
                    schedule(context, reminder.id, rearmSec * 1000)
                    rearmed++
                }
                Log.i(TAG, "Re-armed $rearmed/${unfired.size} reminder(s)")
            } catch (e: Exception) {
                Log.e(TAG, "Reminder re-arm failed: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun schedule(context: Context, reminderId: Long, triggerEpochMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            setAction(ReminderBroadcastReceiver.ACTION_FIRE)
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ReminderBroadcastReceiver.scheduleAlarmCompat(alarmManager, triggerEpochMs, pendingIntent)
    }

    companion object {
        private const val TAG = "ReminderRearm"
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
