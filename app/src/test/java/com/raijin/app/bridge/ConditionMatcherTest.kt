package com.raijin.app.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TM-9: notification-title ↔ watched-contact matching. */
class ConditionMatcherTest {

    @Test
    fun `exact name matches`() {
        assertTrue(ConditionMatcher.matches("Rahul", "Rahul"))
    }

    @Test
    fun `full name title matches first-name contact`() {
        assertTrue(ConditionMatcher.matches("Rahul Sharma", "Rahul"))
    }

    @Test
    fun `first-name title matches full-name contact`() {
        assertTrue(ConditionMatcher.matches("Rahul", "Rahul Sharma"))
    }

    @Test
    fun `case and extra whitespace are ignored`() {
        assertTrue(ConditionMatcher.matches("  RAHUL   sharma ", "rahul sharma"))
    }

    @Test
    fun `different contact does not match`() {
        assertFalse(ConditionMatcher.matches("Priya", "Rahul"))
    }

    @Test
    fun `blank inputs never match`() {
        assertFalse(ConditionMatcher.matches("", "Rahul"))
        assertFalse(ConditionMatcher.matches("Rahul", " "))
    }

    @Test
    fun `normalization collapses whitespace and case`() {
        assertTrue(ConditionMatcher.normalize("  Hello   WORLD ") == "hello world")
    }
}
