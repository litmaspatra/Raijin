package com.raijin.app.skill

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Update
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    /** Regex or keyword phrase that triggers this skill */
    val pattern: String,

    /** ActionType string e.g. "DELETE_FILES", "MUSIC_CONTROL", "OPEN_APP" */
    @ColumnInfo(name = "action_type")
    val actionType: String,

    /** JSON parameters for the action */
    @ColumnInfo(name = "action_args_json")
    val actionArgsJson: String = "{}",

    val description: String = "",

    @ColumnInfo(name = "usage_count")
    val usageCount: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skill: SkillEntity): Long

    @Update
    suspend fun update(skill: SkillEntity)

    @Query("SELECT * FROM skills ORDER BY usage_count DESC")
    suspend fun getAll(): List<SkillEntity>

    @Query("UPDATE skills SET usage_count = usage_count + 1 WHERE id = :id")
    suspend fun incrementUsage(id: Long)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM skills")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM skills")
    suspend fun count(): Int
}
