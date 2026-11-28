package com.raijin.app.obsidian

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Storage behind the owner-selected Obsidian vault. Paths are always vault-relative
 * with '/' separators. Two implementations: plain files (tests) and the Android
 * folder picker grant (SAF tree URI, no storage permission needed).
 */
interface VaultFs {
    data class Entry(val rel: String, val isDir: Boolean)

    /** All entries below the root, skipping dot-folders. Root itself is not listed. */
    fun walk(): List<Entry>
    fun read(rel: String): String?
    fun exists(rel: String): Boolean

    /** Writes inside the vault only; creates parent folders. */
    fun write(rel: String, content: String, append: Boolean)

    companion object {
        /** True when the relative path stays inside the vault (no absolute, no "..", no empty parts). */
        fun safeRel(rel: String): Boolean {
            if (rel.isBlank() || rel.startsWith("/") || rel.contains('\\')) return false
            return rel.split('/').none { it.isEmpty() || it == ".." || it == "." }
        }
    }
}

class FileVaultFs(private val root: File) : VaultFs {
    private val rootCanonical = root.canonicalPath

    private fun inside(f: File): Boolean {
        val c = runCatching { f.canonicalPath }.getOrNull() ?: return false
        return c == rootCanonical || c.startsWith(rootCanonical + File.separator)
    }

    override fun walk(): List<VaultFs.Entry> {
        val out = mutableListOf<VaultFs.Entry>()
        root.walkTopDown()
            .onEnter { d -> !d.name.startsWith(".") && inside(d) }
            .forEach { f ->
                if (f == root || !inside(f)) return@forEach
                out += VaultFs.Entry(f.relativeTo(root).path.replace(File.separatorChar, '/'), f.isDirectory)
            }
        return out
    }

    override fun read(rel: String): String? =
        if (!VaultFs.safeRel(rel)) null else runCatching { File(root, rel).takeIf { inside(it) }?.readText(Charsets.UTF_8) }.getOrNull()

    override fun exists(rel: String) = VaultFs.safeRel(rel) && File(root, rel).let { inside(it) && it.exists() }

    override fun write(rel: String, content: String, append: Boolean) {
        require(VaultFs.safeRel(rel)) { "Note path escapes the vault root" }
        val t = File(root, rel)
        require(inside(t)) { "Note path escapes the vault root" }
        t.parentFile?.mkdirs()
        if (append) t.appendText(content, Charsets.UTF_8) else t.writeText(content, Charsets.UTF_8)
    }
}

/** Vault reached through a persisted folder-picker grant. Nothing outside the picked tree is reachable. */
class SafVaultFs(context: Context, private val tree: Uri) : VaultFs {
    private val cr: ContentResolver = context.contentResolver
    private val rootDocId = DocumentsContract.getTreeDocumentId(tree)

    private fun docUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private data class Child(val id: String, val name: String, val isDir: Boolean)

    private fun children(parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = mutableListOf<Child>()
        cr.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += Child(c.getString(0), c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return out
    }

    private fun find(rel: String): Child? {
        var id = rootDocId
        var cur: Child? = null
        for (part in rel.split('/')) {
            cur = children(id).firstOrNull { it.name == part } ?: return null
            id = cur.id
        }
        return cur
    }

    override fun walk(): List<VaultFs.Entry> {
        val out = mutableListOf<VaultFs.Entry>()
        fun go(id: String, prefix: String) {
            for (c in children(id)) {
                if (c.name.startsWith(".") || c.name.isEmpty()) continue
                val rel = prefix + c.name
                out += VaultFs.Entry(rel, c.isDir)
                if (c.isDir) go(c.id, "$rel/")
            }
        }
        go(rootDocId, "")
        return out
    }

    override fun read(rel: String): String? {
        if (!VaultFs.safeRel(rel)) return null
        val c = find(rel)?.takeIf { !it.isDir } ?: return null
        return runCatching { cr.openInputStream(docUri(c.id))?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    }

    override fun exists(rel: String) = VaultFs.safeRel(rel) && find(rel) != null

    override fun write(rel: String, content: String, append: Boolean) {
        require(VaultFs.safeRel(rel)) { "Note path escapes the vault root" }
        val parts = rel.split('/')
        var parentId = rootDocId
        for (dir in parts.dropLast(1)) {
            val existing = children(parentId).firstOrNull { it.name == dir }
            parentId = if (existing != null) {
                require(existing.isDir) { "A file blocks the folder path" }
                existing.id
            } else {
                val u = DocumentsContract.createDocument(cr, docUri(parentId), DocumentsContract.Document.MIME_TYPE_DIR, dir)
                    ?: error("Could not create folder")
                DocumentsContract.getDocumentId(u)
            }
        }
        val name = parts.last()
        val existing = children(parentId).firstOrNull { it.name == name }
        val target = if (existing != null) {
            require(!existing.isDir) { "A folder blocks the note path" }
            docUri(existing.id)
        } else {
            DocumentsContract.createDocument(cr, docUri(parentId), "application/octet-stream", name)
                ?: error("Could not create note")
        }
        val mode = if (append && existing != null) "wa" else "wt"
        cr.openOutputStream(target, mode)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("Could not open note")
    }
}
