package com.raijin.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NT-1: ring-buffer eviction, 7-day retention, key replacement. */
class NotificationCacheTest {

    private data class Entry(val posted: Long)

    private var now = 10_000_000L
    private fun newCache(max: Int = 3) = NotificationCache<Entry>(
        postedAtMs = { it.posted },
        nowMs = { now },
        maxEntries = max,
    )

    @Test
    fun `overflow evicts oldest posted first`() {
        val cache = newCache(max = 3)
        cache.put("a", Entry(posted = 100))
        cache.put("b", Entry(posted = 200))
        cache.put("c", Entry(posted = 300))
        cache.put("d", Entry(posted = 400)) // evicts "a"
        assertEquals(3, cache.size())
        assertEquals(listOf(200L, 300L, 400L), cache.values().map { it.posted }.sorted())
    }

    @Test
    fun `same key replaces instead of double counting`() {
        val cache = newCache(max = 3)
        cache.put("a", Entry(posted = 100))
        cache.put("a", Entry(posted = 500))
        assertEquals(1, cache.size())
        assertEquals(500L, cache.values().single().posted)
    }

    @Test
    fun `entries older than retention are pruned on write`() {
        val cache = newCache(max = 10)
        cache.put("old", Entry(posted = now - NotificationCache.RETENTION_MS - 1))
        cache.put("fresh", Entry(posted = now))
        assertEquals(1, cache.size())
        assertEquals(listOf(now), cache.values().map { it.posted })
    }

    @Test
    fun `entry exactly at the retention boundary survives`() {
        val cache = newCache(max = 10)
        cache.put("edge", Entry(posted = now - NotificationCache.RETENTION_MS))
        assertEquals(1, cache.size())
    }

    @Test
    fun `advancing the clock expires old entries on the next write`() {
        val cache = newCache(max = 10)
        cache.put("a", Entry(posted = now))
        now += NotificationCache.RETENTION_MS + 1
        cache.put("b", Entry(posted = now))
        assertEquals(listOf(now), cache.values().map { it.posted })
    }

    @Test
    fun `remove and clear behave`() {
        val cache = newCache(max = 10)
        cache.put("a", Entry(posted = now))
        cache.put("b", Entry(posted = now))
        cache.remove("a")
        assertEquals(1, cache.size())
        cache.clear()
        assertTrue(cache.values().isEmpty())
    }
}
