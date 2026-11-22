package io.raijin.brain.storage

import java.security.MessageDigest

/**
 * ST-3 duplicate detection (PRD 7.7). Pure Kotlin, JVM-testable (PRD
 * principle 7); the Android side supplies file metadata and bytes, this
 * class owns the grouping decisions.
 *
 * Three-stage funnel from the PRD:
 *   1. Group by exact size — different sizes can never be duplicates.
 *   2. Within a size group, hash the first and last 64 KiB; different edge
 *      hashes can never be duplicates.
 *   3. Only inside an edge-hash group with more than one member, hash the
 *      full content and group by it.
 *
 * Guarantees:
 * - No false positives: a returned group contains only byte-identical files
 *   (stage 3 compares full content hashes; two files in one group always
 *   share size and full SHA-256).
 * - No false negatives above zero bytes: true duplicates share size and
 *   edge bytes, so they survive every stage.
 * - Zero-byte files are excluded: every empty file hashes identically, so
 *   reporting them as "duplicates" would be noise, not reclaimable media.
 *   Empty files/folders are a separate ST-4 junk category.
 *
 * Deletion policy (which copy to keep, protected paths, never the last
 * copy) is ST-7 and deliberately NOT decided here — this only reports
 * groups.
 */

data class StorageFileEntry(val path: String, val size: Long)

/** Supplies file bytes. The Android adapter reads from storage; tests use memory. */
interface StorageChunkSource {
    /** Bytes in [offset, offset + length), clamped to end of file. */
    fun readRange(path: String, offset: Long, length: Int): ByteArray

    /** Whole file. Called at most once per path, only for edge-hash collisions. */
    fun readAll(path: String): ByteArray
}

data class DuplicateGroup(
    val sizeBytes: Long,
    val sha256: String,
    /** All byte-identical paths, sorted for stable output. */
    val paths: List<String>,
) {
    /** Bytes reclaimable by keeping exactly one copy. */
    val reclaimableBytes: Long get() = sizeBytes * (paths.size - 1)
}

object DuplicateDetector {

    const val EDGE_BYTES: Int = 64 * 1024

    fun findDuplicates(
        files: List<StorageFileEntry>,
        source: StorageChunkSource,
    ): List<DuplicateGroup> {
        val bySize = files
            .filter { it.size > 0 }
            .groupBy { it.size }
            .filterValues { it.size > 1 }
        if (bySize.isEmpty()) return emptyList()

        val groups = mutableListOf<DuplicateGroup>()
        for ((size, sameSize) in bySize.entries.sortedBy { it.key }) {
            val byEdge = sameSize.groupBy { edgeKey(it, source) }
                .filterValues { it.size > 1 }
            for (edgeGroup in byEdge.values) {
                val byFull = edgeGroup.groupBy { sha256Hex(source.readAll(it.path)) }
                for ((hash, members) in byFull) {
                    if (members.size > 1) {
                        groups.add(
                            DuplicateGroup(
                                sizeBytes = size,
                                sha256 = hash,
                                paths = members.map { it.path }.sorted(),
                            )
                        )
                    }
                }
            }
        }
        return groups.sortedByDescending { it.reclaimableBytes }
    }

    /**
     * First + last 64 KiB hash. Files smaller than EDGE_BYTES hash their
     * whole content as both edges (the read clamps), so small files are
     * decided correctly at stage 2 and re-confirmed at stage 3.
     */
    private fun edgeKey(entry: StorageFileEntry, source: StorageChunkSource): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val first = source.readRange(entry.path, 0, EDGE_BYTES)
        digest.update(first)
        if (entry.size > EDGE_BYTES) {
            val lastOffset = entry.size - EDGE_BYTES
            digest.update(source.readRange(entry.path, lastOffset, EDGE_BYTES))
        }
        return digest.digest().toHex()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
