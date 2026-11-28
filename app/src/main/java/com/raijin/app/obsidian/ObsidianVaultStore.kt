package com.raijin.app.obsidian

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * OB-1: owner configuration for the dedicated Obsidian note-maker.
 *
 * The vault is the folder the owner picks with Android's folder picker. Only the
 * picker's grant (a tree URI) is kept, in app-private storage on this phone. It is
 * never guessed, never defaulted, never sent anywhere. Writes stay blocked while
 * it is unset or the grant was revoked.
 */
class ObsidianVaultStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Picker grant for the vault folder, or null when unset. */
    var treeUri: Uri?
        get() = prefs.getString(KEY_TREE_URI, null)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        private set(value) = prefs.edit().putString(KEY_TREE_URI, value?.toString()).apply()

    /** Remember a folder the owner just picked and keep read+write access across restarts. */
    fun select(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        treeUri = uri
    }

    fun clear() { treeUri = null }

    /** Storage for the selected vault, or null when unset or the grant is gone. */
    fun openFs(): VaultFs? {
        val u = treeUri ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == u && it.isReadPermission && it.isWritePermission
        }
        return if (granted) SafVaultFs(context, u) else null
    }

    /** Short folder label for the UI (last path segment of the picked folder). */
    fun label(): String? = treeUri?.let { runCatching { displayName(DocumentsContract.getTreeDocumentId(it)) }.getOrNull() }

    private companion object {
        const val PREFS_NAME = "raijin_obsidian_vault"
        const val KEY_TREE_URI = "vault_tree_uri"

        fun displayName(docId: String) = docId.substringAfter(':').substringAfterLast('/').ifBlank { "vault" }
    }
}
