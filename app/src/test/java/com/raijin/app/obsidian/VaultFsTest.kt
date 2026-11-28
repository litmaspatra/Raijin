package com.raijin.app.obsidian

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultFsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun safeRelRejectsEscapesAndBlanks() {
        assertTrue(VaultFs.safeRel("Daily/2026-01-01.md"))
        assertFalse(VaultFs.safeRel(""))
        assertFalse(VaultFs.safeRel("/etc/passwd"))
        assertFalse(VaultFs.safeRel("../x.md"))
        assertFalse(VaultFs.safeRel("a/../../x.md"))
        assertFalse(VaultFs.safeRel("a//b.md"))
        assertFalse(VaultFs.safeRel("a\\b.md"))
    }

    @Test fun writeCreatesParentsAndAppendKeepsContent() {
        val fs = FileVaultFs(tmp.newFolder("v"))
        fs.write("Ideas/new.md", "one", append = false)
        fs.write("Ideas/new.md", "\ntwo", append = true)
        assertEquals("one\ntwo", fs.read("Ideas/new.md"))
        assertTrue(fs.exists("Ideas/new.md"))
        assertFalse(fs.exists("Ideas/none.md"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeOutsideVaultIsRefused() {
        FileVaultFs(tmp.newFolder("v2")).write("../escape.md", "x", append = false)
    }

    @Test fun readOutsideVaultReturnsNull() {
        assertNull(FileVaultFs(tmp.newFolder("v3")).read("../x.md"))
    }

    @Test fun walkSkipsDotFoldersAndScanUsesFs() {
        val root = tmp.newFolder("v4")
        File(root, "Notes").mkdirs()
        File(root, ".obsidian").mkdirs()
        File(root, ".obsidian/app.md").writeText("hidden")
        File(root, "Notes/Alpha.md").writeText("---\ntags: a\n---\nsee [[Beta]]")
        val idx = VaultIndex.scan(FileVaultFs(root))
        assertEquals(listOf("Notes"), idx.folders)
        assertEquals(mapOf("Alpha" to "Notes/Alpha.md"), idx.notes)
        assertTrue(idx.conventions.wikilinksUsed)
    }
}
