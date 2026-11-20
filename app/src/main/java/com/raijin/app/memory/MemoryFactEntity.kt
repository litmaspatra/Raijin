package com.raijin.app.memory

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Update
import androidx.room.Query

@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** One of: ENTITY, FACT, EPISODE, PREFERENCE */
    val kind: String,

    val text: String,

    /** Optional alias for entity lookup (e.g. "Maa", "Anita", "Brother") */
    @ColumnInfo(name = "entity_alias")
    val entityAlias: String? = null,

    /**
     * ME-3 memory tier: HOT (session), WARM (30 days, the default),
     * COLD (permanent). Rows pre-dating tiers default to WARM.
     */
    @ColumnInfo(name = "tier", defaultValue = "WARM")
    val tier: String = "WARM",

    val score: Float = 1.0f,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: MemoryFactEntity): Long

    @Update
    suspend fun update(fact: MemoryFactEntity)

    @Query("SELECT * FROM memory_facts ORDER BY kind ASC, created_at DESC")
    suspend fun getAll(): List<MemoryFactEntity>

    @Query("DELETE FROM memory_facts")
    suspend fun deleteAll(): Int

    /** ME-3 enforcement: warm facts expire after the warm window (30 days). */
    @Query("DELETE FROM memory_facts WHERE tier = 'WARM' AND created_at < :cutoff")
    suspend fun purgeWarmOlderThan(cutoff: Long): Int

    /** ME-3 enforcement: hot facts live for one session only. */
    @Query("DELETE FROM memory_facts WHERE tier = 'HOT'")
    suspend fun purgeHot(): Int

    @Query("SELECT * FROM memory_facts WHERE text LIKE '%' || :query || '%' OR entity_alias LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE (kind = 'ENTITY' OR entity_alias IS NOT NULL) AND (entity_alias LIKE '%' || :name || '%' OR text LIKE '%' || :name || '%') LIMIT :limit")
    suspend fun findEntities(name: String, limit: Int): List<MemoryFactEntity>

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM memory_facts ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<MemoryFactEntity>
}
