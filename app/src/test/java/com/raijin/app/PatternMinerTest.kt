package com.raijin.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.raijin.app.memory.InteractionLogEntity
import com.raijin.app.memory.PatternMiner
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class PatternMinerTest {

    private lateinit var db: RaijinDatabase
    private val zone = ZoneId.of("Asia/Kolkata")
    // 2026-11-18 12:00 IST
    private val now = java.time.ZonedDateTime.of(2026, 11, 18, 12, 0, 0, 0, zone)
        .toInstant().toEpochMilli()
    private val dayMillis = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RaijinDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun log(signature: String, daysAgo: Int, hour: Int) {
        val at = now - daysAgo * dayMillis + (hour - 12) * 3600_000L
        db.interactionLogDao().insert(InteractionLogEntity(signature = signature, at = at))
    }

    @Test
    fun `five alarms on five mornings become a stored pattern`() = runTest {
        repeat(5) { log("action:SET_ALARM", daysAgo = it + 1, hour = 7) }
        val stored = PatternMiner(db.interactionLogDao(), db.memoryPatternDao(), zone, nowMillis = { now })
            .mineAndPersist()
        assertEquals(1, stored)
        val patterns = db.memoryPatternDao().all()
        assertEquals(1, patterns.size)
        assertEquals("action:set_alarm", patterns[0].signature)
        assertEquals("MORNING", patterns[0].dayPart)
        assertEquals(5, patterns[0].count)
    }

    @Test
    fun `below threshold stores nothing`() = runTest {
        repeat(4) { log("action:SET_ALARM", daysAgo = it + 1, hour = 7) }
        val stored = PatternMiner(db.interactionLogDao(), db.memoryPatternDao(), zone, nowMillis = { now })
            .mineAndPersist()
        assertEquals(0, stored)
        assertTrue(db.memoryPatternDao().all().isEmpty())
    }

    @Test
    fun `re-mining upserts the same pattern row`() = runTest {
        repeat(5) { log("web_search", daysAgo = it + 1, hour = 21) }
        val miner = PatternMiner(db.interactionLogDao(), db.memoryPatternDao(), zone, nowMillis = { now })
        miner.mineAndPersist()
        log("web_search", daysAgo = 1, hour = 20)
        miner.mineAndPersist()
        val patterns = db.memoryPatternDao().all()
        assertEquals(1, patterns.size)
        assertEquals(6, patterns[0].count)
    }

    @Test
    fun `warm facts older than the window are purged when mining runs`() = runTest {
        db.memoryDao().insert(
            com.raijin.app.memory.MemoryFactEntity(
                kind = "FACT", text = "old warm fact", tier = "WARM",
                createdAt = now - 40 * dayMillis,
            )
        )
        db.memoryDao().insert(
            com.raijin.app.memory.MemoryFactEntity(
                kind = "FACT", text = "kept cold fact", tier = "COLD",
                createdAt = now - 40 * dayMillis,
            )
        )
        PatternMiner(db.interactionLogDao(), db.memoryPatternDao(), zone, nowMillis = { now }, memoryDao = db.memoryDao())
            .mineAndPersist()
        val remaining = db.memoryDao().getAll()
        assertEquals(1, remaining.size)
        assertEquals("kept cold fact", remaining[0].text)
    }

    @Test
    fun `forgetting a pattern removes the mining input that would recreate it`() = runTest {
        repeat(5) { log("action:SET_ALARM", daysAgo = it + 1, hour = 7) }
        val miner = PatternMiner(db.interactionLogDao(), db.memoryPatternDao(), zone, nowMillis = { now })
        miner.mineAndPersist()
        assertEquals(1, db.memoryPatternDao().all().size)

        val pattern = db.memoryPatternDao().all().first()
        db.memoryPatternDao().deleteById(pattern.id)
        miner.forgetPatternSources(pattern.signature)

        // Re-mining must NOT recreate the forgotten pattern.
        assertEquals(0, miner.mineAndPersist())
        assertTrue(db.memoryPatternDao().all().isEmpty())
    }

    @Test
    fun `log is pruned to the mining window`() = runTest {
        log("web_search", daysAgo = 40, hour = 10)
        log("web_search", daysAgo = 2, hour = 10)
        PatternMiner(db.interactionLogDao(), db.memoryPatternDao(), zone, nowMillis = { now }).mineAndPersist()
        assertEquals(1, db.interactionLogDao().count())
    }
}
