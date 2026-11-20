package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelationshipAliasesTest {

    @Test
    fun maaExpandsToEnglishAndDevanagariSynonyms() {
        val variants = RelationshipAliases.expand("maa")
        assertEquals("maa", variants.first())
        assertTrue("mom" in variants)
        assertTrue("mother" in variants)
        assertTrue("मम्मी" in variants)
    }

    @Test
    fun expansionKeepsSurroundingWords() {
        val variants = RelationshipAliases.expand("meri maa")
        assertTrue("meri mom" in variants)
        assertTrue(variants.size <= 14)
    }

    @Test
    fun queryWithoutRelationshipTermIsUnchanged() {
        assertEquals(listOf("Rahul"), RelationshipAliases.expand("Rahul"))
    }

    @Test
    fun devanagariQueryExpandsToLatin() {
        val variants = RelationshipAliases.expand("मम्मी")
        assertTrue("maa" in variants)
        assertTrue("mother" in variants)
    }

    @Test
    fun expansionIsCapped() {
        assertTrue(RelationshipAliases.expand("mother").size <= 14)
    }

    @Test
    fun pureRoleQueriesAreDetected() {
        assertTrue(RelationshipAliases.isRelationshipQuery("my mother"))
        assertTrue(RelationshipAliases.isRelationshipQuery("मम्मी"))
        assertFalse(RelationshipAliases.isRelationshipQuery("Rahul Sharma"))
        assertFalse(RelationshipAliases.isRelationshipQuery(""))
    }
}
