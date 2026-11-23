package com.raijin.app.bridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.raijin.app.RaijinDatabase
import com.raijin.app.reminder.ReminderBroadcastReceiver
import com.raijin.app.reminder.ReminderEntity

/**
 * Shared scheduling for notification-detected reminders (NT-2/NT-3): used by
 * NotificationListenerService for auto-created (high-importance +
 * high-confidence) events and by RaijinService when the owner accepts a
 * suggestion chip. One path, one dedup record, one alarm shape.
 */
object ProactiveReminderScheduler {

    private const val TAG = "ProactiveReminder"

    suspend fun schedule(context: Context, detected: DetectedEvent, sourcePkg: String): Long {
        val db = RaijinDatabase.getInstance(context.applicationContext)
        val reminder = ReminderEntity(
            text = detected.title,
            triggerTime = detected.triggerEpochMs / 1000,
            context = "Detected from $sourcePkg: ${detected.rawText.take(200)}",
            createdAt = System.currentTimeMillis() / 1000,
        )
        val id = db.reminderDao().upsert(reminder)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            setAction(ReminderBroadcastReceiver.ACTION_FIRE)
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_ID, id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ReminderBroadcastReceiver.scheduleAlarmCompat(alarmManager, detected.triggerEpochMs, pendingIntent)
        Log.i(TAG, "Scheduled reminder '${detected.title}' for epoch ${detected.triggerEpochMs}")
        return id
    }
}
