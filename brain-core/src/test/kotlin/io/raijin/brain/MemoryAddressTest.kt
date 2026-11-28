package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryAddressTest {
    // --- address preference ---
    @Test fun parsesPersistentAddressForms() {
        assertEquals("sir", AddressForm.parse("call me sir always"))
        assertEquals("sir", AddressForm.parse("always call me sir"))
        assertEquals("sir", AddressForm.parse("From now on call me sir."))
        assertEquals("Kartik", AddressForm.parse("address me as Kartik"))
        assertEquals("sir", AddressForm.parse("stop calling me boss, call me sir"))
        assertEquals("sir", AddressForm.parse("call me sir", remembered = true))
        assertEquals("sir", AddressForm.parse("call me sir always", remembered = true))
    }

    @Test fun doesNotMistakeCommandsForPreferences() {
        assertNull(AddressForm.parse("call me a cab"))
        assertNull(AddressForm.parse("call me sir")) // no persistence cue, could be a command
        assertNull(AddressForm.parse("call me back later", remembered = true))
        assertNull(AddressForm.parse("call me at 5 pm always"))
        assertNull(AddressForm.parse("call me an uber always"))
    }

    @Test fun rewriteReplacesBossEverywhereKeepingCase() {
        assertEquals("Got it, sir. I'll remember that.", AddressForm.rewrite("Got it, boss. I'll remember that.", "sir"))
        assertEquals("Sir, done.", AddressForm.rewrite("Boss, done.", "sir"))
        assertEquals("Done, boss.", AddressForm.rewrite("Done, boss.", null))
        assertEquals("Done, boss.", AddressForm.rewrite("Done, boss.", "boss"))
        assertEquals("Mic on, Kartik. Bossy stays.", AddressForm.rewrite("Mic on, boss. Bossy stays.", "Kartik"))
    }

    // --- remember / recall ---
    @Test fun extractsFactsToRemember() {
        assertEquals("my name is Kartik", MemoryRecall.extractFact("remember that my name is Kartik"))
        assertEquals("my name is Kartik", MemoryRecall.extractFact("Remember my name is Kartik"))
        assertEquals("call me sir always", MemoryRecall.extractFact("remember call me sir always"))
        assertEquals("my bike is blue", MemoryRecall.extractFact("don't forget that my bike is blue"))
        assertNull(MemoryRecall.extractFact("what is my name"))
    }

    @Test fun extractsRecallQueriesWithoutPunctuation() {
        assertEquals("my name", MemoryRecall.extractQuery("what is my name"))
        assertEquals("my name", MemoryRecall.extractQuery("What's my name?"))
        assertEquals("my name", MemoryRecall.extractQuery("whats my name"))
        assertEquals("name", MemoryRecall.extractQuery("who am I?"))
        assertEquals("my bike", MemoryRecall.extractQuery("do you remember my bike"))
        assertEquals("the bike", MemoryRecall.extractQuery("what did I tell you about the bike"))
        assertNull(MemoryRecall.extractQuery("what is the capital of France"))
    }

    @Test fun nameFlowFindsTheFactAndAnswersInSecondPerson() {
        val facts = listOf("my bike is blue", "my name is Kartik Nama")
        val q = MemoryRecall.extractQuery("What is my name?")!!
        val hits = MemoryRecall.rank(facts, MemoryRecall.keywords(q))
        assertEquals(listOf("my name is Kartik Nama"), hits)
        assertEquals("Your name is Kartik Nama.", MemoryRecall.phrase(hits.first()))
    }

    @Test fun generalFactFlow() {
        val facts = listOf("my bike is blue", "I live in Tonk")
        val hits = MemoryRecall.rank(facts, MemoryRecall.keywords("my bike"))
        assertEquals("Your bike is blue.", MemoryRecall.phrase(hits.first()))
        assertEquals("You live in Tonk.", MemoryRecall.phrase("I live in Tonk"))
    }

    @Test fun addressFactIsAlsoARecallableFact() {
        val facts = listOf("call me sir always")
        assertEquals(facts, MemoryRecall.rank(facts, MemoryRecall.keywords("what to call me")))
    }
}
