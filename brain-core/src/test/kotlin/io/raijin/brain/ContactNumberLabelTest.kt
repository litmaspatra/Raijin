package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.raijin.brain.ContactScorer.Entry

/**
 * CT-10: multi-number contacts resolve by address-book label
 * ("call Rahul work" picks the work number) without any parser change.
 */
class ContactNumberLabelTest {

    private val rahulMobile = Entry("device:+919999999991", "Rahul", numberLabel = "mobile")
    private val rahulWork = Entry("device:+919999999992", "Rahul", numberLabel = "work")

    @Test
    fun labelQueryPicksTheLabelledNumber() {
        val ranking = ContactScorer.rankEntries(listOf(rahulMobile, rahulWork), listOf("rahul work"))
        assertEquals("device:+919999999992", ranking.first().contactId)
        assertEquals(ContactScorer.Confidence.PROCEED, ContactScorer.confidenceOf(ranking))
    }

    @Test
    fun mobileLabelQueryPicksTheMobileNumber() {
        val ranking = ContactScorer.rankEntries(listOf(rahulMobile, rahulWork), listOf("rahul mobile"))
        assertEquals("device:+919999999991", ranking.first().contactId)
        assertEquals(ContactScorer.Confidence.PROCEED, ContactScorer.confidenceOf(ranking))
    }

    @Test
    fun unlabeledQueryStaysAmbiguousBetweenSameNameNumbers() {
        // No label in the query: both numbers score the same, so the user is
        // asked (the learned-preference path and CT-7 corrections break the
        // tie outside the scorer).
        val ranking = ContactScorer.rankEntries(listOf(rahulMobile, rahulWork), listOf("rahul"))
        assertEquals(ContactScorer.Confidence.ASK, ContactScorer.confidenceOf(ranking))
    }

    @Test
    fun labelDoesNotRescueANameTheUserDidNotSay() {
        // "work" is only a name form of Rahul's entry; it must not pull a
        // different name into contention.
        val pooja = Entry("device:+919999999993", "Pooja", numberLabel = "work")
        val ranking = ContactScorer.rankEntries(listOf(rahulWork, pooja), listOf("rahul work"))
        assertEquals("device:+919999999992", ranking.first().contactId)
        assertTrue(ranking.first().score > ranking[1].score)
    }

    @Test
    fun entriesWithoutLabelBehaveExactlyAsBefore() {
        val plain = Entry("device:+919999999994", "Rahul")
        val withLabelForms = ContactScorer.bestMatchEntry(rahulMobile, listOf("rahul"))!!.score
        val plainScore = ContactScorer.bestMatchEntry(plain, listOf("rahul"))!!.score
        assertEquals(plainScore, withLabelForms)
    }
}
