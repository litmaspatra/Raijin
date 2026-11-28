package com.raijin.app.obsidian

import java.io.File

/**
 * OB-2: a read-only scan of the owner-configured vault. Structure awareness
 * comes from this scan alone — folders, note titles, filename and
 * frontmatter conventions actually present — never from invented structure.
 *
 * Pure filesystem code (java.io), unit-testable with temp dirs.
 */
class VaultIndex(
    val folders: List<String>,
    /** Note title (filename without .md) -> vault-relative path. */
    val notes: Map<String, String>,
    val conventions: VaultConventions,
) {

    /** True when a [[wikilink]] target resolves to exactly one existing note. */
    fun resolves(title: String): Boolean = notes.containsKey(title)

    companion object {

        fun scan(rootPath: String): VaultIndex {
            val root = File(rootPath)
            require(root.isDirectory) { "vault root is not a directory: $rootPath" }
            return scan(FileVaultFs(root))
        }

        fun scan(fs: VaultFs): VaultIndex {
            val folders = mutableListOf<String>()
            val notes = mutableMapOf<String, String>()
            val frontmatterKeys = mutableMapOf<String, Int>()
            var wikilinkNotes = 0
            var tagNotes = 0
            var dailyNamed = 0
            var mdCount = 0
            val dailyRe = Regex("""\d{4}-\d{2}-\d{2}""")
            val fmKeyRe = Regex("""^([A-Za-z][\w-]*):\s*.*$""")
            val wikiRe = Regex("""\[\[[^\]]+]]""")

            for (e in fs.walk()) {
                val rel = e.rel
                val fileName = rel.substringAfterLast('/')
                when {
                    e.isDir -> folders += rel
                    fileName.endsWith(".md") -> {
                        val title = fileName.removeSuffix(".md")
                        mdCount++
                        notes[title] = rel
                        if (dailyRe.containsMatchIn(title)) dailyNamed++
                        // Sample up to 25 notes for conventions; content stays
                        // on-device, only aggregated conventions leave this class.
                        if (mdCount <= 25) {
                            val text = fs.read(rel) ?: ""
                            if (text.startsWith("---")) {
                                text.lineSequence().drop(1).takeWhile { it.trim() != "---" }
                                    .mapNotNull { fmKeyRe.matchEntire(it.trim())?.groupValues?.get(1) }
                                    .forEach { frontmatterKeys.merge(it, 1, Int::plus) }
                            }
                            if (wikiRe.containsMatchIn(text)) wikilinkNotes++
                            if (text.lineSequence().any { it.trimStart().startsWith("#") && !it.trimStart().startsWith("# ") }) {
                                tagNotes++
                            }
                        }
                    }
                }
            }

            return VaultIndex(
                folders = folders.sorted(),
                notes = notes,
                conventions = VaultConventions(
                    noteCount = mdCount,
                    dailyNoteFilenames = mdCount > 0 && dailyNamed * 2 >= mdCount,
                    commonFrontmatterKeys = frontmatterKeys.entries
                        .filter { it.value >= 2 }
                        .sortedByDescending { it.value }
                        .map { it.key },
                    wikilinksUsed = wikilinkNotes > 0,
                    inlineTagsUsed = tagNotes > 0,
                ),
            )
        }
    }
}

/** Aggregated conventions detected from sampled notes; counts only, no content. */
data class VaultConventions(
    val noteCount: Int,
    val dailyNoteFilenames: Boolean,
    val commonFrontmatterKeys: List<String>,
    val wikilinksUsed: Boolean,
    val inlineTagsUsed: Boolean,
)
