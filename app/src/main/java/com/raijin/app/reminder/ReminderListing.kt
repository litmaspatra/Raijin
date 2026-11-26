package com.raijin.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure helpers behind the "Reminders" list, shared by the screen and tests. */
object ReminderListing {

    /** Pending, timed reminders, soonest first. */
    fun pending(all: List<ReminderEntity>): List<ReminderEntity> =
        all.filter { it.fired == 0 && it.triggerTime != null }.sortedBy { it.triggerTime }

    fun whenLabel(triggerEpochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)
            .format(Instant.ofEpochSecond(triggerEpochSec).atZone(zone))

    fun recurrenceLabel(rule: String?): String? = when {
        rule == null -> null
        rule.startsWith("DAILY") -> "repeats daily"
        rule.startsWith("WEEKLY") -> "repeats weekly"
        else -> "repeats"
    }

    /** Second line of a row: when it fires, plus repeat and condition notes. */
    fun detail(r: ReminderEntity, zone: ZoneId = ZoneId.systemDefault()): String {
        val parts = mutableListOf<String>()
        r.triggerTime?.let { parts += whenLabel(it, zone) }
        recurrenceLabel(r.recurrenceRule)?.let { parts += it }
        r.condition?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString(" - ")
    }

    /** Cancels the alarm and marks the reminder done, so it leaves the list. */
    suspend fun cancel(context: Context, dao: ReminderDao, r: ReminderEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ReminderBroadcastReceiver.ACTION_FIRE
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_ID, r.id)
        }
        val pi = PendingIntent.getBroadcast(
            context, r.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pi)
        pi.cancel()
        dao.markFired(r.id)
    }
}
