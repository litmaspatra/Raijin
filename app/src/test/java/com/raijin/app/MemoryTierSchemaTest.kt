package com.raijin.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.raijin.app.memory.MemoryPatternEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ME-3: v8 schema behavior - tier column with WARM default and the
 * memory_patterns table round-trip. The full 7 -> 8 migration-path test
 * (hand-built v7 file -> Room open) is pending: it needs either
 * androidx.room:room-testing (not in the offline cache) or the complete v7
 * schema replayed from schemas/7.json; tracked in STATUS.md.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryTierSchemaTest {

    private lateinit var db: RaijinDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RaijinDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `facts default to WARM tier and tiers round-trip`() = runTest {
        val dao = db.memoryDao()
        dao.insert(
            com.raijin.app.memory.MemoryFactEntity(kind = "FACT", text = "The sky appears blue from scattered sunlight")
        )
        val facts = dao.getRecent(10)
        assertEquals(1, facts.size)
        assertEquals("WARM", facts[0].tier)

        dao.insert(
            com.raijin.app.memory.MemoryFactEntity(kind = "PREFERENCE", text = "likes chai", tier = "COLD")
        )
        assertTrue(dao.getRecent(10).any { it.tier == "COLD" })
    }

    @Test
    fun `patterns persist and delete`() = runTest {
        val dao = db.memoryPatternDao()
        dao.insert(
            MemoryPatternEntity(
                signature = "set_alarm:#:#",
                dayPart = "MORNING",
                count = 5,
                firstAt = 1L,
                lastAt = 2L,
            )
        )
        val all = dao.all()
        assertEquals(1, all.size)
        assertEquals("set_alarm:#:#", all[0].signature)
        assertEquals("MORNING", all[0].dayPart)

        assertEquals(1, dao.deleteById(all[0].id))
        assertTrue(dao.all().isEmpty())
    }
}
