package com.raijin.app.obsidian

import java.io.File

/** OB-1/OB-2: owner-typed vault path check. Never creates or guesses a folder. */
object VaultRootCheck {
    sealed interface Result {
        data class Ok(val path: String) : Result
        object Empty : Result
        object NotAbsolute : Result
        object NotFound : Result
    }

    fun check(input: String, isDir: (File) -> Boolean = { it.isDirectory }): Result {
        val p = input.trim().trimEnd('/')
        if (p.isEmpty()) return Result.Empty
        if (!p.startsWith("/")) return Result.NotAbsolute
        if (p.split('/').any { it == ".." }) return Result.NotAbsolute
        return if (isDir(File(p))) Result.Ok(p) else Result.NotFound
    }

    fun message(r: Result): String = when (r) {
        is Result.Ok -> "Vault set: ${r.path}"
        Result.Empty -> "Type the vault folder path."
        Result.NotAbsolute -> "Use the full path starting with /."
        Result.NotFound -> "That folder was not found. If it exists, Raijin may not have file access yet."
    }
}
