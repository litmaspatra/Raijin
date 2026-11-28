package com.raijin.app.selfedit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.raijin.app.audio.KokoroConfigStore
import com.raijin.app.memory.MemoryDao
import com.raijin.app.memory.MemoryFactEntity
import com.raijin.app.prefs.UserPreferencesStore
import io.raijin.brain.SelfEdit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeMemoryDao : MemoryDao {
    val rows = mutableListOf<MemoryFactEntity>()
    private var next = 1L
    override suspend fun insert(fact: MemoryFactEntity): Long { val id = next++; rows += fact.copy(id = id); return id }
    override suspend fun update(fact: MemoryFactEntity) {}
    override suspend fun getAll() = rows.toList()
    override suspend fun deleteAll(): Int { val n = rows.size; rows.clear(); return n }
    override suspend fun purgeWarmOlderThan(cutoff: Long) = 0
    override suspend fun purgeHot() = 0
    override suspend fun search(query: String, limit: Int) = rows.filter { it.text.contains(query) }.take(limit)
    override suspend fun findEntities(name: String, limit: Int) = emptyList<MemoryFactEntity>()
    override suspend fun deleteById(id: Long): Int = if (rows.removeAll { it.id == id }) 1 else 0
    override suspend fun getRecent(limit: Int) = rows.takeLast(limit)
}

@RunWith(RobolectricTestRunner::class)
class SelfEditExecutorTest {
    private lateinit var context: Context
    private lateinit var dao: FakeMemoryDao
    private lateinit var exec: SelfEditExecutor

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf("raijin_user_prefs", "raijin_kokoro_voice", "raijin_self_edit").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
        dao = FakeMemoryDao()
        exec = SelfEditExecutor(context, dao)
    }

    private fun run(text: String): String = runBlocking {
        val cmd = SelfEdit.parse(text)!!
        exec.apply(cmd, text)
    }

    @Test fun addressChangeTakesEffectAndUndoRestoresIt() {
        val prefs = UserPreferencesStore(context)
        assertNull(prefs.addressTerm)
        assertTrue(run("always call me sir").contains("sir"))
        assertEquals("sir", prefs.addressTerm)
        assertEquals(1, dao.rows.size)
        assertEquals("Undone.", run("undo that"))
        assertNull(prefs.addressTerm)
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun speedStepsAndUndo() {
        val prefs = UserPreferencesStore(context)
        run("speak faster")
        assertEquals(1.2f, prefs.ttsSpeed, 0.001f)
        run("set your speech speed to 2.0")
        assertEquals(2.0f, prefs.ttsSpeed, 0.001f)
        run("undo")
        assertEquals(1.2f, prefs.ttsSpeed, 0.001f)
    }

    @Test fun voiceGenderChangesTheOnDeviceVoice() {
        val store = KokoroConfigStore(context)
        assertEquals("af_heart", store.load().voice)
        run("use a male voice")
        assertEquals("bm_george", store.load().voice)
        run("undo that")
        assertEquals("af_heart", store.load().voice)
    }

    @Test fun refusalsChangeNothing() {
        val prefs = UserPreferencesStore(context)
        val msg = run("turn off your privacy checks")
        assertTrue(msg.contains("core"))
        assertTrue(run("send my contacts to the cloud").contains("Settings"))
        assertEquals(1.0f, prefs.ttsSpeed, 0.001f)
        assertEquals("There is nothing to undo.", run("undo"))
    }
}
