package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContactNameNormalizerTest {

    @Test fun `lowercases and trims`() {
        assertEquals("kartik", ContactNameNormalizer.normalize("  Kartik  "))
    }

    @Test fun `strips latin diacritics`() {
        assertEquals("rene", ContactNameNormalizer.normalize("René"))
    }

    @Test fun `collapses repeated vowels`() {
        assertEquals(ContactNameNormalizer.normalize("Rahul"), ContactNameNormalizer.normalize("Raahul"))
    }

    @Test fun `devanagari transliterates mechanically`() {
        // Inherent vowels are kept at this level; the phonetic key aligns scripts.
        assertEquals("kartika", ContactNameNormalizer.normalize("कर्तिक"))
    }

    @Test fun `devanagari independent vowel start`() {
        assertEquals("amita", ContactNameNormalizer.normalize("अमित"))
    }

    @Test fun `anusvara becomes nasal`() {
        assertTrue(ContactNameNormalizer.normalize("अनंद").startsWith("ann"))
    }

    @Test fun `punctuation is removed`() {
        assertEquals("rahul sharma", ContactNameNormalizer.normalize("Rahul-Sharma!"))
    }
}

class ContactPhoneticsTest {

    private fun key(raw: String) = ContactPhonetics.phoneticKey(ContactNameNormalizer.normalize(raw))

    @Test fun `v and w confusion folds`() {
        assertEquals(key("Vishal"), key("Wishal"))
    }

    @Test fun `sh and s confusion folds`() {
        assertEquals(key("Sharma"), key("Sarma"))
    }

    @Test fun `th and t confusion folds`() {
        assertEquals(key("Karthik"), key("Kartik"))
    }

    @Test fun `aa and a fold via vowel collapse`() {
        assertEquals(key("Kartik"), key("Kaartik"))
    }

    @Test fun `dh and d fold`() {
        assertEquals(key("Dhruv"), key("Druv"))
    }

    @Test fun `ph and f fold`() {
        assertEquals(key("Phani"), key("Fani"))
    }

    @Test fun `z and j fold`() {
        assertEquals(key("Zoya"), key("Joya"))
    }

    @Test fun `ri and ru fold`() {
        assertEquals(key("Ritu"), key("Ritu".replace("i", "u")))
    }

    @Test fun `nasal endings fold`() {
        assertEquals(key("Raman"), key("Ramam"))
    }

    @Test fun `double consonants collapse`() {
        assertEquals(key("Ankit"), key("Ankkit"))
    }

    @Test fun `distinct names have distinct keys`() {
        assertTrue(key("Rahul") != key("Rohit"))
    }

    @Test fun `edit distance basics`() {
        assertEquals(0, ContactPhonetics.editDistance("abc", "abc"))
        assertEquals(1, ContactPhonetics.editDistance("abc", "abd"))
        assertEquals(3, ContactPhonetics.editDistance("abc", ""))
    }

    @Test fun `jaro winkler identical is one`() {
        assertEquals(1.0, ContactPhonetics.jaroWinkler("kartik", "kartik"))
    }

    @Test fun `jaro winkler similar beats dissimilar`() {
        val similar = ContactPhonetics.jaroWinkler("kartik", "karthik")
        val dissimilar = ContactPhonetics.jaroWinkler("kartik", "rohit")
        assertTrue(similar > dissimilar)
    }
}

class ContactSimilarityTest {

    @Test fun `exact match scores one`() {
        assertEquals(1.0, ContactPhonetics.similarity("Kartik Nama", "Kartik Nama"))
    }

    @Test fun `phonetic confusion still scores high`() {
        assertTrue(ContactPhonetics.similarity("Karthik", "Kartik") >= 0.92)
    }

    @Test fun `devanagari transcript matches latin contact`() {
        assertTrue(ContactPhonetics.similarity("कर्तिक", "Kartik") >= 0.92)
    }

    @Test fun `devanagari sharma matches latin at key level`() {
        val k1 = ContactPhonetics.phoneticKey(ContactNameNormalizer.normalize("Sharma"))
        val k2 = ContactPhonetics.phoneticKey(ContactNameNormalizer.normalize("शर्मा"))
        assertEquals(k1, k2)
    }

    @Test fun `latin transcript matches devanagari contact`() {
        assertTrue(ContactPhonetics.similarity("kartik", "कर्तिक") >= 0.92)
    }

    @Test fun `unrelated names score low`() {
        assertTrue(ContactPhonetics.similarity("Kartik", "Rohit") < 0.50)
    }
}

class ContactScorerTest {

    private val contacts = listOf(
        "1" to "Kartik Nama",
        "2" to "Rahul Sharma",
        "3" to "Priya Verma",
        "4" to "Rohit Gupta",
    )

    @Test fun `ranks best candidate first`() {
        val ranking = ContactScorer.rank(contacts, listOf("rahul sharma"))
        assertEquals("2", ranking.first().contactId)
    }

    @Test fun `n-best alternatives are all scored`() {
        // STT mishears first alternative; the correct contact should still win via the second.
        val ranking = ContactScorer.rank(contacts, listOf("roger federer", "rohit"))
        assertEquals("4", ranking.first().contactId)
        assertEquals("rohit", ranking.first().matchedVia)
    }

    @Test fun `phonetic variant of name wins`() {
        val ranking = ContactScorer.rank(contacts, listOf("karthik"))
        assertEquals("1", ranking.first().contactId)
    }

    @Test fun `devanagari transcript resolves contact`() {
        val ranking = ContactScorer.rank(contacts, listOf("प्रिया"))
        assertEquals("3", ranking.first().contactId)
    }

    @Test fun `clear exact match proceeds`() {
        val ranking = ContactScorer.rank(contacts, listOf("priya verma"))
        assertEquals(ContactScorer.Confidence.PROCEED, ContactScorer.confidenceOf(ranking))
    }

    @Test fun `ambiguous close names ask`() {
        val close = listOf("1" to "Rahul Sharma", "2" to "Rahul Verma")
        val ranking = ContactScorer.rank(close, listOf("rahul"))
        assertEquals(ContactScorer.Confidence.ASK, ContactScorer.confidenceOf(ranking))
    }

    @Test fun `unrelated query misses`() {
        val ranking = ContactScorer.rank(contacts, listOf("xylophone zebra"))
        assertEquals(ContactScorer.Confidence.MISS, ContactScorer.confidenceOf(ranking))
    }

    @Test fun `empty alternatives yield no ranking`() {
        assertTrue(ContactScorer.rank(contacts, emptyList()).isEmpty())
        assertEquals(ContactScorer.Confidence.MISS, ContactScorer.confidenceOf(emptyList()))
    }
}

class ContactScorerEntryTest {

    @Test fun `alias query resolves to canonical contact`() {
        val entry = ContactScorer.Entry("c1", "Rahul Sharma", listOf("Rahul bhai"))
        val m = ContactScorer.bestMatchEntry(entry, listOf("rahul bhai"))
        kotlin.test.assertNotNull(m)
        assertEquals("c1", m.contactId)
        assertEquals("Rahul Sharma", m.contactName)
        assertTrue(m.score >= 0.85, "alias match should reach PROCEED band, got ${m.score}")
    }

    @Test fun `alias competes with other contacts display names`() {
        val entries = listOf(
            ContactScorer.Entry("c1", "Rahul Sharma", listOf("Rahul bhai")),
            ContactScorer.Entry("c2", "Rahul Dravid"),
        )
        val ranking = ContactScorer.rankEntries(entries, listOf("rahul bhai"))
        assertEquals("c1", ranking.first().contactId)
    }

    @Test fun `phonetic variant of alias also matches`() {
        val entries = listOf(
            ContactScorer.Entry("c1", "Kartik Nama", listOf("Kartik work")),
            ContactScorer.Entry("c2", "Kartik Aaryan"),
        )
        val ranking = ContactScorer.rankEntries(entries, listOf("karthik work"))
        assertEquals("c1", ranking.first().contactId)
    }

    @Test fun `empty alternatives rank nothing`() {
        val entries = listOf(ContactScorer.Entry("c1", "Rahul Sharma"))
        assertTrue(ContactScorer.rankEntries(entries, emptyList()).isEmpty())
    }

    @Test fun `unrelated query stays in MISS band`() {
        val entries = listOf(
            ContactScorer.Entry("c1", "Rahul Sharma"),
            ContactScorer.Entry("c2", "Priya Singh"),
        )
        val ranking = ContactScorer.rankEntries(entries, listOf("xylophone zebra"))
        assertEquals(ContactScorer.Confidence.MISS, ContactScorer.confidenceOf(ranking))
    }
}
