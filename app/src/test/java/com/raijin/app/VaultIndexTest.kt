package com.raijin.app

import com.raijin.app.obsidian.VaultIndex
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** OB-2: vault scan builds structure awareness from the real tree only. */
class VaultIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun note(root: File, rel: String, body: String) {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(body)
    }

    @Test
    fun `scan lists folders and note titles with relative paths`() {
        val root = tmp.newFolder("vault")
        note(root, "concepts/ship-of-theseus.md", "# Ship of Theseus")
        note(root, "books/sapiens.md", "# Sapiens")
        note(root, "inbox/2026-11-19.md", "daily")
        File(root, ".obsidian").mkdirs()
        note(root, ".obsidian/workspace.md", "internal")

        val idx = VaultIndex.scan(root.path)
        assertEquals(listOf("books", "concepts", "inbox"), idx.folders)
        assertEquals("concepts/ship-of-theseus.md", idx.notes["ship-of-theseus"])
        assertTrue(idx.resolves("sapiens"))
        assertFalse(idx.resolves("nonexistent"))
        // dot-folders are skipped
        assertFalse(idx.notes.containsKey("workspace"))
    }

    @Test
    fun `vault root is not listed as a folder and root-level notes still index`() {
        val root = tmp.newFolder("vault")
        note(root, "readme.md", "# at the root")
        note(root, "concepts/a.md", "a")

        val idx = VaultIndex.scan(root.path)
        assertEquals(listOf("concepts"), idx.folders) // no "" root entry (regression: relativeTo(root) is "", not ".")
        assertEquals("readme.md", idx.notes["readme"])
        assertTrue(idx.resolves("readme"))
    }

    @Test
    fun `conventions aggregate without retaining content`() {
        val root = tmp.newFolder("vault")
        note(root, "2026-11-18.md", "---\ntags: [journal]\nmood: ok\n---\ntext [[link]] #tag")
        note(root, "2026-11-19.md", "---\ntags: [journal]\nmood: fine\n---\nmore [[other]]")
        note(root, "plain.md", "no frontmatter")

        val c = VaultIndex.scan(root.path).conventions
        assertEquals(3, c.noteCount)
        assertTrue(c.dailyNoteFilenames)
        assertTrue(c.commonFrontmatterKeys.containsAll(listOf("tags", "mood")))
        assertTrue(c.wikilinksUsed)
    }

    @Test
    fun `symlinks escaping the vault root are not followed`() {
        val root = tmp.newFolder("vault")
        val outside = tmp.newFolder("outside")
        note(root, "concepts/a.md", "a")
        note(outside, "secret.md", "secret")
        // symlinked directory and symlinked file pointing outside the root
        java.nio.file.Files.createSymbolicLink(
            File(root, "linked-dir").toPath(), outside.toPath())
        java.nio.file.Files.createSymbolicLink(
            File(root, "concepts/linked-note.md").toPath(), File(outside, "secret.md").toPath())

        val idx = VaultIndex.scan(root.path)
        assertFalse(idx.folders.contains("linked-dir"))
        assertFalse(idx.notes.containsKey("secret"))
        assertFalse(idx.notes.containsKey("linked-note"))
        assertEquals(1, idx.conventions.noteCount)
    }

    @Test
    fun `non-daily vault is not flagged daily`() {
        val root = tmp.newFolder("vault")
        note(root, "concepts/a.md", "a")
        note(root, "concepts/b.md", "b")
        assertFalse(VaultIndex.scan(root.path).conventions.dailyNoteFilenames)
    }
}
