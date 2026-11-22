package io.raijin.brain.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ST-3 acceptance: "Planted duplicates are all found, no false positives in
 * tests." Also pins the efficiency contract: full reads only happen inside
 * edge-hash collision groups.
 */
class DuplicateDetectorTest {

    /** In-memory chunk source that counts full reads. */
    private class MemorySource : StorageChunkSource {
        val blobs = mutableMapOf<String, ByteArray>()
        var fullReads = 0
            private set

        fun put(path: String, content: ByteArray) {
            blobs[path] = content
        }

        fun put(path: String, content: String) = put(path, content.toByteArray())

        override fun readRange(path: String, offset: Long, length: Int): ByteArray {
            val b = blobs.getValue(path)
            val start = offset.toInt().coerceAtMost(b.size)
            val end = (start + length).coerceAtMost(b.size)
            return b.copyOfRange(start, end)
        }

        override fun readAll(path: String): ByteArray {
            fullReads++
            return blobs.getValue(path)
        }
    }

    private fun entriesOf(source: MemorySource): List<StorageFileEntry> =
        source.blobs.map { (path, bytes) -> StorageFileEntry(path, bytes.size.toLong()) }

    @Test
    fun `planted duplicates are all found`() {
        val src = MemorySource()
        src.put("/dcim/a.jpg", "holiday-photo-bytes")
        src.put("/downloads/a-copy.jpg", "holiday-photo-bytes")
        src.put("/whatsapp/sent/a.jpg", "holiday-photo-bytes")
        src.put("/notes/unique.txt", "something else entirely")
        val groups = DuplicateDetector.findDuplicates(entriesOf(src), src)
        assertEquals(1, groups.size)
        assertEquals(
            listOf("/dcim/a.jpg", "/downloads/a-copy.jpg", "/whatsapp/sent/a.jpg"),
            groups[0].paths,
        )
        assertEquals(2 * "holiday-photo-bytes".length.toLong(), groups[0].reclaimableBytes)
    }

    @Test
    fun `same size different content is not a duplicate`() {
        val src = MemorySource()
        src.put("/a.bin", "AAAA")
        src.put("/b.bin", "BBBB")
        src.put("/c.bin", "CCCC")
        assertTrue(DuplicateDetector.findDuplicates(entriesOf(src), src).isEmpty())
        assertEquals(0, src.fullReads, "no full reads without edge collisions")
    }

    @Test
    fun `same first and last 64KiB but different middle is not a duplicate`() {
        val edge = ByteArray(DuplicateDetector.EDGE_BYTES) { 'x'.code.toByte() }
        val middleA = ByteArray(100) { 'a'.code.toByte() }
        val middleB = ByteArray(100) { 'b'.code.toByte() }
        val src = MemorySource()
        src.put("/big-a.bin", edge + middleA + edge)
        src.put("/big-b.bin", edge + middleB + edge)
        val groups = DuplicateDetector.findDuplicates(entriesOf(src), src)
        assertTrue(groups.isEmpty())
        assertEquals(2, src.fullReads, "edge collision must trigger exactly the full reads")
    }

    @Test
    fun `large files identical beyond the edges are duplicates`() {
        val edge = ByteArray(DuplicateDetector.EDGE_BYTES) { 'q'.code.toByte() }
        val middle = ByteArray(5000) { (it % 251).toByte() }
        val src = MemorySource()
        src.put("/movies/rip.mkv", edge + middle + edge)
        src.put("/backup/rip.mkv", edge + middle + edge)
        src.put("/movies/other.mkv", edge + middle + edge.copyOf().also { it[10] = 0 })
        val groups = DuplicateDetector.findDuplicates(entriesOf(src), src)
        assertEquals(1, groups.size)
        assertEquals(listOf("/backup/rip.mkv", "/movies/rip.mkv"), groups[0].paths)
    }

    @Test
    fun `zero byte files are excluded`() {
        val src = MemorySource()
        src.put("/e1.tmp", ByteArray(0))
        src.put("/e2.tmp", ByteArray(0))
        src.put("/real.bin", "data")
        assertTrue(DuplicateDetector.findDuplicates(entriesOf(src), src).isEmpty())
    }

    @Test
    fun `distinct sizes never group`() {
        val src = MemorySource()
        src.put("/short", "ab")
        src.put("/longer", "ab".padEnd(64, 'z'))
        assertTrue(DuplicateDetector.findDuplicates(entriesOf(src), src).isEmpty())
        assertEquals(0, src.fullReads)
    }

    @Test
    fun `multiple duplicate groups sort by reclaimable bytes`() {
        val src = MemorySource()
        val big = ByteArray(2048) { (it % 97).toByte() }
        src.put("/big1", big)
        src.put("/big2", big)
        src.put("/small1", "tiny")
        src.put("/small2", "tiny")
        val groups = DuplicateDetector.findDuplicates(entriesOf(src), src)
        assertEquals(2, groups.size)
        assertEquals(2048L, groups[0].sizeBytes)
        assertEquals(4L, groups[1].sizeBytes)
    }
}
