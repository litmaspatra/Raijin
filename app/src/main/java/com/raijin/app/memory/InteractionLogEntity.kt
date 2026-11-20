package com.raijin.app.memory

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * ME-3: one row per handled interaction, feeding the daily pattern miner.
 * The signature is a STRUCTURED label ("action:set_alarm", "skill_run"),
 * never the raw utterance - the log stays small and groupable, and private
 * words never enter the mining input. Rows older than the 30-day warm window
 * are pruned after each mining run.
 */
@Entity(tableName = "interaction_log")
data class InteractionLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Structured action signature, e.g. "action:set_alarm:6:30". */
    val signature: String,

    @ColumnInfo(name = "at")
    val at: Long = System.currentTimeMillis(),
)

@Dao
interface InteractionLogDao {
    @Insert
    suspend fun insert(entry: InteractionLogEntity): Long

    @Query("SELECT * FROM interaction_log WHERE at >= :cutoff ORDER BY at ASC")
    suspend fun since(cutoff: Long): List<InteractionLogEntity>

    @Query("DELETE FROM interaction_log WHERE at < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM interaction_log")
    suspend fun count(): Int

    @Query("DELETE FROM interaction_log")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM interaction_log WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int
}
