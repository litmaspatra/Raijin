package com.raijin.app.memory

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * ME-3: a stored pattern mined from repeated interactions (5+ in the same
 * day-part band over 30 days). One row per (signature, day part); re-mining
 * upserts the fresh counts. Patterns are cold-tier knowledge: they persist
 * until the owner deletes them from the "What I know about you" screen (ME-4).
 */
@Entity(tableName = "memory_patterns")
data class MemoryPatternEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Normalized action signature, e.g. "set_alarm:#:#". */
    val signature: String,

    /** MORNING, AFTERNOON, EVENING or NIGHT. */
    @ColumnInfo(name = "day_part")
    val dayPart: String,

    val count: Int,

    @ColumnInfo(name = "first_at")
    val firstAt: Long,

    @ColumnInfo(name = "last_at")
    val lastAt: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface MemoryPatternDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pattern: MemoryPatternEntity): Long

    @Query("SELECT * FROM memory_patterns WHERE signature = :signature AND day_part = :dayPart LIMIT 1")
    suspend fun find(signature: String, dayPart: String): MemoryPatternEntity?

    @Query("SELECT * FROM memory_patterns ORDER BY count DESC")
    suspend fun all(): List<MemoryPatternEntity>

    @Query("DELETE FROM memory_patterns WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM memory_patterns")
    suspend fun deleteAll(): Int
}
