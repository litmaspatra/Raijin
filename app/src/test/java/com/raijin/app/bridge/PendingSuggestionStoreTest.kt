package com.raijin.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NT-3 suggestion queue: accept consumes, dismiss drops, expiry prunes. */
class PendingSuggestionStoreTest {

    private var now = 1_000_000L
    private val store = PendingSuggestionStore(nowMs = { now }, ttlMs = 12 * 3600_000L)

    private fun event(title: String) = DetectedEvent(
        title = title,
        triggerEpochMs = now + 3600_000L,
        category = "REMINDER",
        rawText = title,
        importance = EventImportance.LOW,
        confidence = EventConfidence.LOW,
    )

    @Test
    fun `accept consumes the suggestion exactly once`() {
        val id = store.add(event("vague deadline"))
        assertEquals("vague deadline", store.accept(id)?.title)
        assertNull(store.accept(id), "second accept must find nothing")
    }

    @Test
    fun `dismiss drops without creating`() {
        val id = store.add(event("noise"))
        store.dismiss(id)
        assertNull(store.accept(id))
        assertTrue(store.pendingIds().isEmpty())
    }

    @Test
    fun `pending ids come out oldest first`() {
        val a = store.add(event("first"))
        val b = store.add(event("second"))
        assertEquals(listOf(a, b), store.pendingIds())
    }

    @Test
    fun `expired suggestions prune`() {
        val id = store.add(event("stale"))
        now += 13 * 3600_000L // beyond the 12h TTL
        assertNull(store.accept(id), "expired suggestion must not schedule a reminder")
        assertTrue(store.pendingIds().isEmpty())
    }
}
