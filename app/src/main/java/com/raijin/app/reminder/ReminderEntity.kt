package com.raijin.app.reminder

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

// ---------------------------------------------------------------------------
// ReminderEntity — one pending or recurring reminder
// ---------------------------------------------------------------------------

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Human-readable text, e.g. "call Rahul". */
    val text: String,

    /** Unix epoch seconds; null for reminders with no fixed time (condition-only). */
    @ColumnInfo(name = "trigger_time")
    val triggerTime: Long? = null,

    /** Optional context string shown when reminder fires, e.g. "after lunch". */
    val context: String = "",

    /** FK to contacts table — not declared as @ForeignKey to keep schema simple. */
    @ColumnInfo(name = "contact_id")
    val contactId: Long? = null,

    /**
     * RRULE-lite:  "DAILY_MORNING", "WEEKLY:1" (Mon=1…Sun=7), or null for one-shot.
     */
    @ColumnInfo(name = "recurrence_rule")
    val recurrenceRule: String? = null,

    /**
     * Natural-language condition, e.g. "if Rahul doesn't reply".
     * Null means no condition — fire at trigger_time unconditionally.
     */
    val condition: String? = null,

    /**
     * NONE  — no condition monitoring.
     * MANUAL — user must dismiss manually.
     * AUTO  — NotificationListenerService cancels on matching notification.
     */
    @ColumnInfo(name = "condition_watch_mode")
    val conditionWatchMode: String = "NONE",

    /** TM-9: contact whose reply satisfies an AUTO condition watch. */
    @ColumnInfo(name = "condition_contact")
    val conditionContact: String? = null,

    /** 0 = pending, 1 = fired (or cancelled). */
    val fired: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

// ---------------------------------------------------------------------------
// ReminderDao
// ---------------------------------------------------------------------------

@Dao
interface ReminderDao {

    /** Insert or replace; returns the row id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity): Long

    /**
     * Reminders due within the next 24 hours that haven't fired yet.
     * Used by RaijinService on boot to re-schedule alarms.
     */
    @Query("SELECT * FROM reminders WHERE fired = 0 AND trigger_time IS NOT NULL AND trigger_time <= :now + 86400")
    suspend fun findPending(now: Long = System.currentTimeMillis() / 1000): List<ReminderEntity>

    /** All unfired reminders, used on reboot to re-schedule all alarms. */
    @Query("SELECT * FROM reminders WHERE fired = 0")
    suspend fun findAllUnfired(): List<ReminderEntity>

    /** Reminders with a condition that haven't fired yet. */
    @Query("SELECT * FROM reminders WHERE condition IS NOT NULL AND fired = 0")
    suspend fun findConditional(): List<ReminderEntity>

    @Query("UPDATE reminders SET fired = 1 WHERE id = :id")
    suspend fun markFired(id: Long)

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ReminderEntity?
}
