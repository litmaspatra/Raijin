package com.raijin.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.raijin.app.skill.SkillRunLogEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkillRunLogDaoTest {

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

    private fun entry(id: String, ranAt: Long, outcome: String = "completed") = SkillRunLogEntity(
        skill_id = id,
        skill_version = 1,
        utterance = "good night",
        outcome = outcome,
        detail = "ok",
        ran_at = ranAt,
    )

    @Test
    fun `recent returns newest first`() = runTest {
        val dao = db.skillRunLogDao()
        dao.insert(entry("a", 1_000))
        dao.insert(entry("b", 3_000))
        dao.insert(entry("c", 2_000))
        assertEquals(listOf("b", "c", "a"), dao.recent().map { it.skill_id })
    }

    @Test
    fun `prune keeps only the newest rows`() = runTest {
        val dao = db.skillRunLogDao()
        repeat(10) { dao.insert(entry("s$it", it.toLong())) }
        dao.prune(keep = 3)
        assertEquals(3, dao.count())
        assertEquals(listOf("s9", "s8", "s7"), dao.recent().map { it.skill_id })
    }

    @Test
    fun `failures are recorded with their reason`() = runTest {
        val dao = db.skillRunLogDao()
        dao.insert(entry("x", 5, outcome = "failed_at_0"))
        val row = dao.recent().single()
        assertTrue(row.outcome.startsWith("failed_at_"))
        assertEquals("ok", row.detail)
    }
}
