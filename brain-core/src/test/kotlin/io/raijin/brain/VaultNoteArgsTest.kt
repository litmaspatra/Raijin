package io.raijin.brain

import io.raijin.contract.ActionArgs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** OB-1: vault note args refuse anything outside a vault-relative .md path. */
class VaultNoteArgsTest {

    @Test
    fun `valid relative markdown path accepted`() {
        val a = ActionArgs.VaultNote("concepts/ship-of-theseus.md", "body")
        assertEquals(false, a.append)
    }

    @Test
    fun `absolute path refused`() {
        assertFailsWith<IllegalArgumentException> {
            ActionArgs.VaultNote("/sdcard/other/x.md", "body")
        }
    }

    @Test
    fun `parent traversal refused`() {
        assertFailsWith<IllegalArgumentException> {
            ActionArgs.VaultNote("concepts/../../escape.md", "body")
        }
    }

    @Test
    fun `non-markdown refused`() {
        assertFailsWith<IllegalArgumentException> {
            ActionArgs.VaultNote("concepts/x.txt", "body")
        }
    }

    @Test
    fun `empty segments refused`() {
        assertFailsWith<IllegalArgumentException> {
            ActionArgs.VaultNote("concepts//x.md", "body")
        }
    }

    @Test
    fun `blank content refused`() {
        assertFailsWith<IllegalArgumentException> {
            ActionArgs.VaultNote("concepts/x.md", "  ")
        }
    }
}
