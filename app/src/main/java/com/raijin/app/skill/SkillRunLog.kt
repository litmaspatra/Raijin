package com.raijin.app.skill

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * SK-2 run log: one row per declarative-skill execution so the "What I've
 * learned" surface (SK-4) can show what ran, when, and how it ended.
 */
@Entity(tableName = "skill_run_log")
data class SkillRunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skill_id: String,
    val skill_version: Int,
    val utterance: String,
    val outcome: String,
    val detail: String,
    val ran_at: Long,
)

@Dao
interface SkillRunLogDao {

    @Insert
    suspend fun insert(entry: SkillRunLogEntity): Long

    @Query("SELECT * FROM skill_run_log ORDER BY ran_at DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<SkillRunLogEntity>

    @Query("SELECT COUNT(*) FROM skill_run_log")
    suspend fun count(): Int

    /** Keep the log bounded; drops the oldest rows beyond [keep]. */
    @Query("DELETE FROM skill_run_log WHERE id NOT IN (SELECT id FROM skill_run_log ORDER BY ran_at DESC LIMIT :keep)")
    suspend fun prune(keep: Int = 500)

    @Query("DELETE FROM skill_run_log")
    suspend fun deleteAll(): Int
}
