package com.raijin.app.apps

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * AP-2: learned app aliases. Written ONLY from explicit owner corrections
 * ("wrong app, I meant X") - never inferred from successful opens, so a
 * wrong guess can never teach itself. Keyed by the normalized spoken form.
 */
@Entity(tableName = "app_aliases")
data class AppAliasEntity(
    @PrimaryKey
    @ColumnInfo(name = "spoken_alias") val spokenAlias: String,
    @ColumnInfo(name = "intended_app") val intendedApp: String,
    @ColumnInfo(name = "learned_at") val learnedAt: Long,
)

@Dao
interface AppAliasDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(alias: AppAliasEntity)

    @Query("SELECT * FROM app_aliases")
    fun getAll(): List<AppAliasEntity>

    @Query("DELETE FROM app_aliases WHERE spoken_alias = :spokenAlias")
    fun delete(spokenAlias: String)

    @Query("DELETE FROM app_aliases")
    fun deleteAll()
}
