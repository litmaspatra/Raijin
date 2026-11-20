package com.raijin.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.raijin.app.apps.AppAliasDao
import com.raijin.app.apps.AppAliasEntity
import com.raijin.app.contact.ContactEntity
import com.raijin.app.contact.ContactEntityDao
import com.raijin.app.contact.CorrectionEntry
import com.raijin.app.contact.CorrectionEntryDao
import com.raijin.app.memory.InteractionLogDao
import com.raijin.app.memory.InteractionLogEntity
import com.raijin.app.memory.MemoryDao
import com.raijin.app.memory.MemoryFactEntity
import com.raijin.app.memory.MemoryPatternDao
import com.raijin.app.memory.MemoryPatternEntity
import com.raijin.app.reminder.ReminderDao
import com.raijin.app.reminder.ReminderEntity
import com.raijin.app.skill.InstalledSkillDao
import com.raijin.app.skill.InstalledSkillEntity
import com.raijin.app.skill.SkillDao
import com.raijin.app.skill.SkillEntity
import com.raijin.app.skill.SkillRunLogDao
import com.raijin.app.skill.SkillRunLogEntity

@Database(
    entities = [
        ReminderEntity::class,
        MemoryFactEntity::class,
        SkillEntity::class,
        ContactEntity::class,
        CorrectionEntry::class,
        AppAliasEntity::class,
        InstalledSkillEntity::class,
        SkillRunLogEntity::class,
        MemoryPatternEntity::class,
        InteractionLogEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class RaijinDatabase : RoomDatabase() {

    abstract fun reminderDao(): ReminderDao
    abstract fun memoryDao(): MemoryDao
    abstract fun skillDao(): SkillDao
    abstract fun contactEntityDao(): ContactEntityDao
    abstract fun correctionEntryDao(): CorrectionEntryDao
    abstract fun appAliasDao(): AppAliasDao
    abstract fun installedSkillDao(): InstalledSkillDao
    abstract fun skillRunLogDao(): SkillRunLogDao
    abstract fun memoryPatternDao(): MemoryPatternDao
    abstract fun interactionLogDao(): InteractionLogDao

    companion object {
        private const val DB_NAME = "raijin_v2.db"

        /**
         * 3 -> 4: register the contacts and corrections tables (PRD CT-1).
         * ContactEntity existed in source but was never listed as a database
         * entity, so Room never created its tables.
         */
        /**
         * 2 -> 3: add the memory_facts and skills tables (commit 72c6710 shipped
         * this without a migration, relying on the old destructive fallback).
         * ReminderEntity was unchanged between v2 and v3, so existing reminders
         * carry over untouched.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_facts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `entity_alias` TEXT,
                        `score` REAL NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `skills` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `pattern` TEXT NOT NULL,
                        `action_type` TEXT NOT NULL,
                        `action_args_json` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `usage_count` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contacts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `canonical_name` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `aliases` TEXT NOT NULL,
                        `phone_number` TEXT NOT NULL,
                        `contact_group` TEXT NOT NULL,
                        `call_count` INTEGER NOT NULL,
                        `last_called_at` INTEGER NOT NULL,
                        `correction_count` INTEGER NOT NULL,
                        `notes` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `corrections` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `contact_id` INTEGER NOT NULL,
                        `wrong_alias` TEXT NOT NULL,
                        `correct_contact_id` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        FOREIGN KEY(`contact_id`) REFERENCES `contacts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_corrections_contact_id` ON `corrections` (`contact_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_corrections_wrong_alias` ON `corrections` (`wrong_alias`)")
            }
        }

        /**
         * 4 -> 5: AP-2 learned app aliases table (explicit owner corrections
         * only; see AppAliasEntity).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_aliases` (
                        `spoken_alias` TEXT NOT NULL,
                        `intended_app` TEXT NOT NULL,
                        `learned_at` INTEGER NOT NULL,
                        PRIMARY KEY(`spoken_alias`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** 5 -> 6: SK-2 versioned installed_skills table. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `installed_skills` (
                        `skill_id` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `manifest_json` TEXT NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `installed_at` INTEGER NOT NULL,
                        PRIMARY KEY(`skill_id`, `version`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** 6 -> 7: SK-2 skill_run_log table. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `skill_run_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `skill_id` TEXT NOT NULL,
                        `skill_version` INTEGER NOT NULL,
                        `utterance` TEXT NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `detail` TEXT NOT NULL,
                        `ran_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * 7 -> 8: ME-3 memory tiers (memory_facts.tier, existing rows default
         * to WARM) + the memory_patterns table for mined interaction patterns.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memory_facts` ADD COLUMN `tier` TEXT NOT NULL DEFAULT 'WARM'"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_patterns` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `signature` TEXT NOT NULL,
                        `day_part` TEXT NOT NULL,
                        `count` INTEGER NOT NULL,
                        `first_at` INTEGER NOT NULL,
                        `last_at` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** 8 -> 9: ME-3 interaction_log feeding the daily pattern miner. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `interaction_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `signature` TEXT NOT NULL,
                        `at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** All migrations, exposed for migration-path tests. */
        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        )

        @Volatile private var INSTANCE: RaijinDatabase? = null

        fun getInstance(context: Context): RaijinDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RaijinDatabase::class.java,
                    DB_NAME,
                )
                    // PRD ME-1: real migrations only. No destructive fallback that
                    // silently wipes memory, reminders and skills on schema change.
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
