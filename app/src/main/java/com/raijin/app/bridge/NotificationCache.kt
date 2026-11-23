package com.raijin.app.bridge

import java.util.concurrent.ConcurrentHashMap

/**
 * NT-1: bounded, expiring store for active-notification digests.
 *
 * Pure JVM (no Android types) so retention and eviction are unit-testable;
 * the listener service delegates to one instance.
 *
 * Discipline:
 * - Ring buffer: at most [maxEntries] entries; overflow evicts OLDEST first
 *   by posted time.
 * - Retention: entries older than [retentionMs] are pruned on every write.
 * - Same-key writes replace, so a self-updating notification never
 *   double-counts.
 */
class NotificationCache<V>(
    private val postedAtMs: (V) -> Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val maxEntries: Int = MAX_ENTRIES,
    private val retentionMs: Long = RETENTION_MS,
) {
    private val entries = ConcurrentHashMap<String, V>()

    companion object {
        const val MAX_ENTRIES = 60

        /** NT-1: PRD retention for cached notification digests. */
        const val RETENTION_MS = 7L * 24 * 3600_000L
    }

    fun put(key: String, value: V) {
        entries[key] = value
        pruneExpired()
        evictOverflow()
    }

    fun remove(key: String) {
        entries.remove(key)
    }

    fun clear() = entries.clear()

    fun size(): Int = entries.size

    fun values(): List<V> = entries.values.toList()

    /** Drop entries whose posted time is older than the retention window. */
    private fun pruneExpired() {
        val cutoff = nowMs() - retentionMs
        val stale = entries.entries.filter { postedAtMs(it.value) < cutoff }
        stale.forEach { entries.remove(it.key, it.value) }
    }

    /** Ring-buffer overflow: evict oldest-posted entries first. */
    private fun evictOverflow() {
        if (entries.size <= maxEntries) return
        val overflow = entries.size - maxEntries
        entries.entries
            .sortedBy { postedAtMs(it.value) }
            .take(overflow)
            .forEach { entries.remove(it.key, it.value) }
    }
}
