package com.raijin.app.bridge

import java.util.UUID

/**
 * NT-3/NT-4: low-importance or low-confidence notification events wait here
 * as island suggestion chips instead of becoming reminders immediately
 * (owner decision 2026-11-23). The owner accepts (creates the reminder) or
 * dismisses from the island chip; voice acceptance is future work.
 *
 * In-memory on purpose: a suggestion is ephemeral — unanswered chips expire
 * after [ttlMs] and a process restart simply drops them (the notification
 * that produced them is still in the shade). Reminders themselves are never
 * stored here; on accept, the event is handed to ProactiveReminderScheduler.
 */
class PendingSuggestionStore(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 12 * 3600_000L,
) {

    private val lock = Any()
    private val entries = LinkedHashMap<String, Pair<Long, DetectedEvent>>()

    /** Queues a suggestion, returning its id. Oldest-expired entries prune on add. */
    fun add(event: DetectedEvent): String = synchronized(lock) {
        pruneExpired()
        val id = UUID.randomUUID().toString()
        entries[id] = nowMs() to event
        id
    }

    /** Takes the event out for reminder creation. Null when unknown or expired. */
    fun accept(id: String): DetectedEvent? = synchronized(lock) {
        pruneExpired()
        entries.remove(id)?.second
    }

    /** Drops the suggestion without creating anything. */
    fun dismiss(id: String): Unit = synchronized(lock) {
        entries.remove(id)
        Unit
    }

    /** Ids of live suggestions, oldest first (for showing the next chip). */
    fun pendingIds(): List<String> = synchronized(lock) {
        pruneExpired()
        entries.keys.toList()
    }

    fun peek(id: String): DetectedEvent? = synchronized(lock) {
        pruneExpired()
        entries[id]?.second
    }

    private fun pruneExpired() {
        val cutoff = nowMs() - ttlMs
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.first < cutoff) it.remove()
        }
    }
}

/** Process-wide store shared by the listener (producer) and RaijinService (consumer). */
object PendingSuggestions {
    val store = PendingSuggestionStore()
}
