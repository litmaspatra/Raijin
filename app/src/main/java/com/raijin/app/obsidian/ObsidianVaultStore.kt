package com.raijin.app.obsidian

import android.content.Context

/**
 * OB-1: owner configuration for the dedicated Obsidian note-maker.
 *
 * Only the vault ROOT lives here. The root is set explicitly by the owner
 * (voice answer or settings field) — never guessed, never defaulted to a
 * folder we happened to find. Writes stay blocked while it is unset.
 */
class ObsidianVaultStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Absolute path of the owner-configured vault root, or null when unset. */
    var vaultRoot: String?
        get() = prefs.getString(KEY_VAULT_ROOT, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_VAULT_ROOT, value?.trim()?.trimEnd('/')).apply()

    private companion object {
        const val PREFS_NAME = "raijin_obsidian_vault"
        const val KEY_VAULT_ROOT = "vault_root"
    }
}
