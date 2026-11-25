package com.raijin.app.contact

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

// ---------------------------------------------------------------------------
// ContactEntity
// ---------------------------------------------------------------------------

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Normalized full name from the address book, e.g. "Rahul Sharma" */
    @ColumnInfo(name = "canonical_name")
    val canonicalName: String,

    /** The name the user actually uses, e.g. "Rahul bhai" */
    @ColumnInfo(name = "display_name")
    val displayName: String,

    /**
     * Comma-separated alias list.
     * e.g. "Rahul,Rahul bhai,Rahul work,Rahul Sharma"
     * Room doesn't provide native JSON — we keep it simple with CSV so no
     * TypeConverter is needed and the LIKE query works directly.
     */
    @ColumnInfo(name = "aliases")
    val aliases: String,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,

    /** Stores ContactGroup.name() — one of FAMILY, WORK, FRIEND, SELF, UNKNOWN */
    @ColumnInfo(name = "contact_group")
    val group: String,

    /** Running total of how many times Raijin has initiated a call to this contact */
    @ColumnInfo(name = "call_count")
    val callCount: Int = 0,

    /** Unix millis of the last Raijin-initiated call */
    @ColumnInfo(name = "last_called_at")
    val lastCalledAt: Long = 0L,

    /** Times the user corrected Raijin's resolution for this contact */
    @ColumnInfo(name = "correction_count")
    val correctionCount: Int = 0,

    /**
     * Free-form context notes as a comma-separated key=value string.
     * e.g. "busy_before=10am,prefers=whatsapp"
     * Keep short; deeper context lives in the brain layer.
     */
    @ColumnInfo(name = "notes")
    val notes: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * CT-10: transient address-book number label ("mobile"/"work"/"home").
     * Read live from the device on each resolve; deliberately NOT a column,
     * so no migration — learned entities leave it null.
     */
    @androidx.room.Ignore
    val numberLabel: String? = null,
)

// ---------------------------------------------------------------------------
// CorrectionEntry — permanent record of every mis-resolution Raijin made
// ---------------------------------------------------------------------------

@Entity(
    tableName = "corrections",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("contact_id"), Index("wrong_alias")],
)
data class CorrectionEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The ContactEntity that Raijin incorrectly resolved to */
    @ColumnInfo(name = "contact_id")
    val contactId: Long,

    /** The alias text the user spoke that Raijin got wrong */
    @ColumnInfo(name = "wrong_alias")
    val wrongAlias: String,

    /** The id of the ContactEntity the user said was actually correct */
    @ColumnInfo(name = "correct_contact_id")
    val correctContactId: Long,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
)

// ---------------------------------------------------------------------------
// DAOs
// ---------------------------------------------------------------------------

@Dao
interface ContactEntityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ContactEntity): Long

    @Update
    suspend fun update(entity: ContactEntity)

    /**
     * Searches the CSV aliases column for a token that matches [alias].
     * The query checks:
     *   - exact full match ("alias")
     *   - alias appears at the start  ("alias,%")
     *   - alias appears in the middle ("%,alias,%")
     *   - alias appears at the end    ("%,alias")
     *
     * All comparisons are case-insensitive via LOWER().
     */
    @Query(
        """
        SELECT * FROM contacts
        WHERE LOWER(aliases) = LOWER(:alias)
           OR LOWER(aliases) LIKE LOWER(:alias) || ',%'
           OR LOWER(aliases) LIKE '%,' || LOWER(:alias) || ',%'
           OR LOWER(aliases) LIKE '%,' || LOWER(:alias)
        """
    )
    suspend fun findByAlias(alias: String): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE contact_group = :group")
    suspend fun findByGroup(group: String): List<ContactEntity>

    @Query("SELECT * FROM contacts ORDER BY canonical_name ASC")
    suspend fun findAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ContactEntity?

    @Query("SELECT * FROM contacts WHERE phone_number = :phone LIMIT 1")
    suspend fun findByPhone(phone: String): ContactEntity?

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CorrectionEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CorrectionEntry): Long

    /**
     * Returns all correction records where the spoken alias matches [alias],
     * ordered newest-first so callers can use the most recent correction.
     */
    @Query("SELECT * FROM corrections ORDER BY timestamp DESC")
    suspend fun all(): List<CorrectionEntry>

    @Query("DELETE FROM corrections WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM corrections")
    suspend fun deleteAll(): Int

    @Query(
        """
        SELECT * FROM corrections
        WHERE LOWER(wrong_alias) = LOWER(:alias)
        ORDER BY timestamp DESC
        """
    )
    suspend fun findByWrongAlias(alias: String): List<CorrectionEntry>
}
