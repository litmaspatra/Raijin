package com.raijin.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ME-1 acceptance: "Migration test in CI."
 *
 * Builds a real on-disk database at an old schema version using the DDL
 * recorded in app/schemas (exportSchema snapshots), seeds rows, then opens it
 * through Room with ALL_MIGRATIONS. Room's open-helper runs the migration
 * chain and validates the final schema against every entity definition; any
 * drift between a migration's SQL and the entity it should produce fails the
 * open. Assertions then prove pre-existing data survives the chain.
 *
 * JVM-level (Robolectric) verification only; on-device migration behaviour is
 * unverified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaijinDatabaseMigrationTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "migration_path_test.db"

    @After
    fun tearDown() {
        context.getDatabasePath(dbName)?.delete()
    }

    // -- v2 baseline: only the reminders table existed (2->3 added the rest) --

    private fun createV2Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reminders` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`text` TEXT NOT NULL, `trigger_time` INTEGER, `context` TEXT NOT NULL, " +
                    "`contact_id` INTEGER, `recurrence_rule` TEXT, `condition` TEXT, " +
                    "`condition_watch_mode` TEXT NOT NULL, `fired` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO `reminders` (`text`, `trigger_time`, `context`, `condition_watch_mode`, `fired`, `created_at`) " +
                    "VALUES ('call the electrician', 1730000000, 'home', 'NONE', 0, 1729000000)"
            )
            db.execSQL(
                "INSERT INTO `reminders` (`text`, `trigger_time`, `context`, `condition_watch_mode`, `fired`, `created_at`) " +
                    "VALUES ('standup', 1730003600, 'work', 'NONE', 1, 1729000100)"
            )
            db.version = 2
        }
    }

    @Test
    fun `v2 to v9 migrates and preserves reminders`() {
        createV2Database()
        val db = Room.databaseBuilder(context, RaijinDatabase::class.java, dbName)
            .addMigrations(*RaijinDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        // Triggers the 2->9 upgrade chain + full entity validation.
        db.openHelper.writableDatabase

        val tables = tableNames(db)
        val expected = setOf(
            "reminders", "memory_facts", "skills", "contacts", "corrections",
            "app_aliases", "installed_skills", "skill_run_log", "memory_patterns",
            "interaction_log",
        )
        assertTrue("missing tables: ${expected - tables}", tables.containsAll(expected))

        val rows = db.openHelper.readableDatabase
            .query("SELECT `text`, `fired` FROM `reminders` ORDER BY `id`")
        val texts = mutableListOf<Pair<String, Long>>()
        while (rows.moveToNext()) texts.add(rows.getString(0) to rows.getLong(1))
        rows.close()
        assertEquals(
            listOf("call the electrician" to 0L, "standup" to 1L),
            texts,
        )
        db.close()
    }

    // -- v4 baseline: reminders + memory_facts + skills + contacts + corrections --

    private fun createV4Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reminders` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`text` TEXT NOT NULL, `trigger_time` INTEGER, `context` TEXT NOT NULL, " +
                    "`contact_id` INTEGER, `recurrence_rule` TEXT, `condition` TEXT, " +
                    "`condition_watch_mode` TEXT NOT NULL, `fired` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_facts` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`text` TEXT NOT NULL, `entity_alias` TEXT, `score` REAL NOT NULL, " +
                    "`created_at` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `skills` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`pattern` TEXT NOT NULL, `action_type` TEXT NOT NULL, " +
                    "`action_args_json` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                    "`usage_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `contacts` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`canonical_name` TEXT NOT NULL, `display_name` TEXT NOT NULL, " +
                    "`aliases` TEXT NOT NULL, `phone_number` TEXT NOT NULL, " +
                    "`contact_group` TEXT NOT NULL, `call_count` INTEGER NOT NULL, " +
                    "`last_called_at` INTEGER NOT NULL, `correction_count` INTEGER NOT NULL, " +
                    "`notes` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `corrections` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`contact_id` INTEGER NOT NULL, `wrong_alias` TEXT NOT NULL, " +
                    "`correct_contact_id` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`contact_id`) REFERENCES `contacts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_corrections_contact_id` ON `corrections` (`contact_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_corrections_wrong_alias` ON `corrections` (`wrong_alias`)")

            db.execSQL(
                "INSERT INTO `memory_facts` (`kind`, `text`, `entity_alias`, `score`, `created_at`) " +
                    "VALUES ('preference', 'chai over coffee', NULL, 0.9, 1729000000)"
            )
            db.execSQL(
                "INSERT INTO `skills` (`name`, `pattern`, `action_type`, `action_args_json`, `description`, `usage_count`, `created_at`) " +
                    "VALUES ('good night', '^good night$', 'SET_ALARM', '{}', 'night routine', 3, 1729000000)"
            )
            db.execSQL(
                "INSERT INTO `contacts` (`canonical_name`, `display_name`, `aliases`, `phone_number`, `contact_group`, `call_count`, `last_called_at`, `correction_count`, `notes`, `created_at`, `updated_at`) " +
                    "VALUES ('maa', 'Maa', 'mom,mummy', '+919999999999', 'family', 42, 1729500000, 1, '', 1729000000, 1729500000)"
            )
            db.execSQL(
                "INSERT INTO `corrections` (`contact_id`, `wrong_alias`, `correct_contact_id`, `timestamp`) " +
                    "VALUES (1, 'ma', 1, 1729600000)"
            )
            db.version = 4
        }
    }

    @Test
    fun `v4 to v9 migrates and preserves memory skills and contacts`() {
        createV4Database()
        val db = Room.databaseBuilder(context, RaijinDatabase::class.java, dbName)
            .addMigrations(*RaijinDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase

        // 7->8 adds memory_facts.tier; pre-existing rows must default to WARM.
        val tierCursor = db.openHelper.readableDatabase
            .query("SELECT `text`, `tier` FROM `memory_facts`")
        assertTrue(tierCursor.moveToFirst())
        assertEquals("chai over coffee", tierCursor.getString(0))
        assertEquals("WARM", tierCursor.getString(1))
        tierCursor.close()

        val contactCursor = db.openHelper.readableDatabase
            .query("SELECT `canonical_name`, `call_count` FROM `contacts`")
        assertTrue(contactCursor.moveToFirst())
        assertEquals("maa", contactCursor.getString(0))
        assertEquals(42L, contactCursor.getLong(1))
        contactCursor.close()

        val correctionCursor = db.openHelper.readableDatabase
            .query("SELECT `wrong_alias` FROM `corrections`")
        assertTrue(correctionCursor.moveToFirst())
        assertEquals("ma", correctionCursor.getString(0))
        correctionCursor.close()

        val skillCursor = db.openHelper.readableDatabase
            .query("SELECT `name`, `usage_count` FROM `skills`")
        assertTrue(skillCursor.moveToFirst())
        assertEquals("good night", skillCursor.getString(0))
        assertEquals(3L, skillCursor.getLong(1))
        skillCursor.close()

        assertNotNull(
            db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='interaction_log'"
            ).apply { assertTrue(moveToFirst()); close() },
        )
        db.close()
    }

    private fun tableNames(db: RaijinDatabase): Set<String> {
        val c = db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table'")
        val out = mutableSetOf<String>()
        while (c.moveToNext()) out.add(c.getString(0))
        c.close()
        return out
    }
}
