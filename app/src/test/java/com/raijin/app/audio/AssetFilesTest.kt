package com.raijin.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class AssetFilesTest {
    private fun tmp() = Files.createTempDirectory("assetfiles").toFile()

    @Test fun copiesWithPlainStreamOnly() {
        val f = File(tmp(), "tokens.txt")
        AssetFiles.ensureCopied({ ByteArrayInputStream("abc".toByteArray()) }, f, "1")
        assertEquals("abc", f.readText())
    }

    @Test fun skipsWhenStampMatchesAndRefreshesWhenItChanges() {
        val f = File(tmp(), "a.txt")
        var n = 0
        val open = { n++; ByteArrayInputStream("v$n".toByteArray()) }
        AssetFiles.ensureCopied(open, f, "1")
        AssetFiles.ensureCopied(open, f, "1")
        assertEquals(1, n)
        AssetFiles.ensureCopied(open, f, "2")
        assertEquals("v2", f.readText())
    }

    /** Regression: openFd throws for compressed assets (the .txt model files), which broke Kokoro. */
    @Test fun noAssetLoaderUsesOpenFd() {
        val roots = listOf("src/main/java/com/raijin/app/audio", "src/main/java/com/raijin/app/wake")
        val offenders = roots.flatMap { File(it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            .filter { it.readText().contains("openFd") }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
        assertFalse(roots.none { File(it).exists() })
    }
}
