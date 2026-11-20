package com.raijin.app.contact

import org.junit.Assert.assertEquals
import org.junit.Test

class AliasMergeTest {

    @Test
    fun appendsNewAlias() {
        assertEquals("Rahul,Rahul bhai", mergeAliases("Rahul", "Rahul bhai"))
    }

    @Test
    fun existingAliasIsNotDuplicated() {
        assertEquals("Rahul,maa", mergeAliases("Rahul,maa", "Maa"))
    }

    @Test
    fun blankAliasLeavesListUnchanged() {
        assertEquals("Rahul", mergeAliases("Rahul", "   "))
    }

    @Test
    fun emptyListStartsClean() {
        assertEquals("maa", mergeAliases("", "maa"))
    }

    @Test
    fun aliasListIsCapped() {
        val full = (1..20).joinToString(",") { "alias$it" }
        val merged = mergeAliases(full, "newest")
        val parts = merged.split(",")
        assertEquals(20, parts.size)
        assertEquals("newest", parts.last())
        assertEquals("alias1".let { parts.contains(it) }, false)
    }
}
