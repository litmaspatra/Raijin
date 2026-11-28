package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudContextTest {
    @Test fun includesFactsAddressToolsAndSkills() {
        val c = CloudContext.build(
            facts = listOf("my name is Kartik Nama", "my bike is blue"),
            addressTerm = "sir",
            skills = listOf(CloudContext.Entry("Morning brief", "Reads weather and reminders")),
        )
        assertTrue(c.contains("Address the owner as \"sir\""))
        assertTrue(c.contains("my name is Kartik Nama"))
        assertTrue(c.contains("my bike is blue"))
        assertTrue(c.contains("- call: Call a contact by name."))
        assertTrue(c.contains("Morning brief: Reads weather and reminders"))
    }

    @Test fun capsSizeAndCount() {
        val many = (1..200).map { "fact number $it " + "x".repeat(500) }
        val c = CloudContext.build(many, null, (1..100).map { CloudContext.Entry("s$it", "d".repeat(500)) })
        assertTrue(c.length <= CloudContext.MAX_TOTAL_CHARS)
        assertFalse(c.contains("fact number 51"))
    }

    @Test fun noAddressLineWhenUnset() {
        val c = CloudContext.build(emptyList(), null, emptyList())
        assertFalse(c.contains("Address the owner"))
        assertFalse(c.contains("Installed skills"))
        assertFalse(c.contains("Facts the owner"))
    }

    @Test fun emptyAndDuplicateFactsAreDropped() {
        val c = CloudContext.build(listOf("  ", "a b", "a  b"), null, emptyList())
        assertTrue(c.split("- a b").size == 2)
    }
}
