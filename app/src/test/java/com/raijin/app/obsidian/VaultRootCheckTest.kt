package com.raijin.app.obsidian

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultRootCheckTest {
    @Test fun existingFolderIsAcceptedAndTrimmed() =
        assertEquals(VaultRootCheck.Result.Ok("/sdcard/ObsidianVault"), VaultRootCheck.check(" /sdcard/ObsidianVault/ ") { true })

    @Test fun missingFolderIsRefused() =
        assertEquals(VaultRootCheck.Result.NotFound, VaultRootCheck.check("/sdcard/Nope") { false })

    @Test fun relativeOrEscapingPathIsRefused() {
        assertEquals(VaultRootCheck.Result.NotAbsolute, VaultRootCheck.check("Vault") { true })
        assertEquals(VaultRootCheck.Result.NotAbsolute, VaultRootCheck.check("/sdcard/../data") { true })
    }

    @Test fun blankIsEmpty() = assertEquals(VaultRootCheck.Result.Empty, VaultRootCheck.check("  ") { true })
}
