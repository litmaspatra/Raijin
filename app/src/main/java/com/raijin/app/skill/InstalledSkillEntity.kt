package com.raijin.app.skill

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * SK-2: versioned store for installed declarative skills. Every approved
 * install/upgrade keeps its own row so rollback is a pointer change, not
 * a restore. [manifestJson] is the exact payload the owner approved, so
 * the stored sha256 always matches the stored bytes.
 */
@Entity(
    tableName = "installed_skills",
    primaryKeys = ["skill_id", "version"],
)
data class InstalledSkillEntity(
    @ColumnInfo(name = "skill_id")
    val skillId: String,

    val version: Int,

    @ColumnInfo(name = "manifest_json")
    val manifestJson: String,

    val sha256: String,

    /** "file", "https:<url>", "github:<owner/repo>", or "voice". */
    val source: String,

    val enabled: Boolean,

    @ColumnInfo(name = "installed_at")
    val installedAt: Long,
)

@Dao
interface InstalledSkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skill: InstalledSkillEntity)

    @Query("SELECT * FROM installed_skills WHERE skill_id = :skillId ORDER BY version DESC")
    suspend fun versionsOf(skillId: String): List<InstalledSkillEntity>

    @Query("SELECT * FROM installed_skills WHERE enabled = 1")
    suspend fun enabled(): List<InstalledSkillEntity>

    @Query("SELECT * FROM installed_skills")
    suspend fun all(): List<InstalledSkillEntity>

    @Query("UPDATE installed_skills SET enabled = :enabled WHERE skill_id = :skillId")
    suspend fun setEnabledForAllVersions(skillId: String, enabled: Boolean)

    @Query("DELETE FROM installed_skills WHERE skill_id = :skillId AND version = :version")
    suspend fun deleteVersion(skillId: String, version: Int)

    @Query("SELECT COUNT(*) FROM installed_skills")
    suspend fun count(): Int
}
