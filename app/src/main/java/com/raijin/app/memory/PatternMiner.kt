package com.raijin.app.memory

import io.raijin.brain.memory.InteractionPatterns
import java.time.ZoneId

/**
 * ME-3: runs the pure pattern miner over the recorded interaction log and
 * persists the results. Pure scheduling-agnostic core so the daily alarm
 * receiver stays a one-liner; everything here is Robolectric-testable.
 */
class PatternMiner(
    private val interactionLogDao: InteractionLogDao,
    private val memoryPatternDao: MemoryPatternDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val memoryDao: MemoryDao? = null,
) {

    /** @return the number of patterns stored or refreshed this run. */
    suspend fun mineAndPersist(): Int {
        val now = nowMillis()
        val cutoff = now - InteractionPatterns.DEFAULT_WINDOW_MILLIS
        val interactions = interactionLogDao.since(cutoff).map {
            InteractionPatterns.Interaction(atEpochMillis = it.at, signature = it.signature)
        }
        val candidates = InteractionPatterns.findPatterns(
            interactions = interactions,
            nowEpochMillis = now,
            zone = zone,
        )
        var stored = 0
        for (candidate in candidates) {
            val existing = memoryPatternDao.find(candidate.signature, candidate.dayPart.name)
            memoryPatternDao.insert(
                MemoryPatternEntity(
                    id = existing?.id ?: 0,
                    signature = candidate.signature,
                    dayPart = candidate.dayPart.name,
                    count = candidate.count,
                    firstAt = candidate.firstAtEpochMillis,
                    lastAt = candidate.lastAtEpochMillis,
                    createdAt = existing?.createdAt ?: now,
                )
            )
            stored++
        }
        // Warm tier: the raw log lives only inside the mining window.
        interactionLogDao.pruneOlderThan(cutoff)
        // WARM facts expire with the same window; the retention labels in the
        // ME-4 screen describe this enforcement.
        memoryDao?.purgeWarmOlderThan(cutoff)
        return stored
    }

    /**
     * Owner deleted a stored pattern: remove the mining input that would
     * recreate it on the next run, or "forget" would grow it back overnight.
     */
    suspend fun forgetPatternSources(normalizedSignature: String): Int {
        val cutoff = nowMillis() - InteractionPatterns.DEFAULT_WINDOW_MILLIS
        val doomed = interactionLogDao.since(cutoff)
            .filter { InteractionPatterns.normalize(it.signature) == normalizedSignature }
            .map { it.id }
        return if (doomed.isEmpty()) 0 else interactionLogDao.deleteByIds(doomed)
    }
}
