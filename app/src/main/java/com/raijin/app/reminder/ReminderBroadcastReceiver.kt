package com.raijin.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.raijin.app.RaijinDatabase
import com.raijin.app.permissions.ActionExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderReceiver"
        const val ACTION_FIRE = "com.raijin.app.action.REMINDER_FIRE"
        /** TM-6: snooze the firing reminder by EXTRA_SNOOZE_MINUTES. */
        const val ACTION_SNOOZE = "com.raijin.app.action.REMINDER_SNOOZE"
        /** TM-6: mark the firing reminder done without rescheduling. */
        const val ACTION_DONE = "com.raijin.app.action.REMINDER_DONE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        private const val CHANNEL_ID = "raijin_reminders"
        private const val WAKELOCK_TIMEOUT_MS = 10_000L

        /**
         * TM-4: smallest occurrence strictly after [nowSeconds], stepping by
         * [intervalSeconds] from [baseTriggerSeconds]. Prevents catch-up spam
         * when a recurring reminder was missed (doze, phone off, reboot).
         */
        fun nextOccurrenceAfter(baseTriggerSeconds: Long, intervalSeconds: Long, nowSeconds: Long): Long {
            val next = baseTriggerSeconds + intervalSeconds
            if (next > nowSeconds) return next
            val missed = (nowSeconds - next) / intervalSeconds + 1
            return next + missed * intervalSeconds
        }

        /**
         * TM-5: trigger second to re-arm after boot/clock change/app update.
         * Recurring reminders whose occurrence passed are rolled forward to
         * the next future occurrence; one-shot reminders keep their trigger
         * (a missed one-shot fires immediately on re-arm rather than being
         * lost silently).
         */
        fun rearmTriggerSeconds(triggerSeconds: Long, recurrenceRule: String?, nowSeconds: Long): Long {
            val intervalSeconds = when (recurrenceRule) {
                "DAILY" -> 86_400L
                "WEEKLY" -> 7 * 86_400L
                else -> null
            }
            return if (intervalSeconds != null && triggerSeconds <= nowSeconds) {
                nextOccurrenceAfter(triggerSeconds, intervalSeconds, nowSeconds)
            } else {
                triggerSeconds
            }
        }

        /**
         * TM-4: exact alarms can be denied on Android 12+ (revocable
         * SCHEDULE_EXACT_ALARM). Fall back to an inexact alarm instead of
         * crashing: a late reminder beats a lost reminder.
         */
        fun scheduleAlarmCompat(
            alarmManager: android.app.AlarmManager,
            triggerEpochMs: Long,
            pendingIntent: android.app.PendingIntent,
        ) {
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
            try {
                when {
                    exactAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerEpochMs, pendingIntent)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                        alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerEpochMs, pendingIntent)
                    else ->
                        alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerEpochMs, pendingIntent)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm denied, falling back to inexact: ${e.message}")
                alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerEpochMs, pendingIntent)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SNOOZE || intent.action == ACTION_DONE) {
            handleAction(context, intent)
            return
        }
        if (intent.action != ACTION_FIRE) return

        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId < 0) {
            Log.w(TAG, "REMINDER_FIRE received with missing reminder_id")
            return
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "raijin:reminder_alert",
        ).apply { acquire(WAKELOCK_TIMEOUT_MS) }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = RaijinDatabase.getInstance(context)
                val reminder = db.reminderDao().findById(reminderId)
                if (reminder != null) {
                    db.reminderDao().markFired(reminderId)
                    showNotification(context, reminder)
                    ActionExecutor.tts?.speak("Reminder: ${reminder.text}")
                    // TM-4: recurring reminders re-arm for the next occurrence.
                    val intervalSeconds = when (reminder.recurrenceRule) {
                        "DAILY" -> 86_400L
                        "WEEKLY" -> 7 * 86_400L
                        else -> null
                    }
                    if (intervalSeconds != null && reminder.triggerTime != null) {
                        // TM-4: after downtime/doze the naive next occurrence can
                        // still be in the past; roll forward so we re-arm the
                        // FUTURE occurrence instead of catch-up spam firing.
                        val nextTrigger = nextOccurrenceAfter(
                            reminder.triggerTime!!,
                            intervalSeconds,
                            System.currentTimeMillis() / 1000,
                        )
                        db.reminderDao().upsert(
                            reminder.copy(triggerTime = nextTrigger, fired = 0),
                        )
                        scheduleAlarm(context, reminderId, nextTrigger * 1000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed handling reminder alarm: ${e.message}", e)
            } finally {
                wakeLock.release()
                pendingResult.finish()
            }
        }
    }

    private fun scheduleAlarm(context: Context, reminderId: Long, triggerEpochMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            setAction(ACTION_FIRE)
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleAlarmCompat(alarmManager, triggerEpochMs, pendingIntent)
    }

    /** TM-6: snooze re-arms as a one-shot N minutes out; done just stays fired. */
    private fun handleAction(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId < 0) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(reminderId.toInt())

        if (intent.action == ACTION_DONE) {
            // markFired already ran when the alarm fired; nothing else to do.
            Log.i(TAG, "Reminder $reminderId marked done")
            return
        }
        val minutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 10).coerceIn(1, 120)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = RaijinDatabase.getInstance(context)
                val reminder = db.reminderDao().findById(reminderId) ?: return@launch
                val newTrigger = System.currentTimeMillis() / 1000 + minutes * 60L
                db.reminderDao().upsert(reminder.copy(triggerTime = newTrigger, fired = 0))
                scheduleAlarm(context, reminderId, newTrigger * 1000)
                Log.i(TAG, "Reminder $reminderId snoozed ${minutes}m")
            } catch (e: Exception) {
                Log.e(TAG, "Snooze failed: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, reminder: ReminderEntity) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Raijin Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Tactical reminders scheduled by Raijin"
            }
            nm.createNotificationChannel(channel)
        }

        fun actionIntent(action: String, snoozeMinutes: Int?): PendingIntent {
            val i = Intent(context, ReminderBroadcastReceiver::class.java).apply {
                setAction(action)
                putExtra(EXTRA_REMINDER_ID, reminder.id)
                if (snoozeMinutes != null) putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            }
            return PendingIntent.getBroadcast(
                context,
                // distinct request codes per action so the intents don't alias
                reminder.id.toInt() * 10 + (snoozeMinutes ?: 0),
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Raijin Tactical Alert")
            .setContentText(reminder.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "Snooze 10m", actionIntent(ACTION_SNOOZE, 10))
            .addAction(0, "Snooze 30m", actionIntent(ACTION_SNOOZE, 30))
            .addAction(0, "Done", actionIntent(ACTION_DONE, null))
            .build()

        nm.notify(reminder.id.toInt(), notification)
    }
}
