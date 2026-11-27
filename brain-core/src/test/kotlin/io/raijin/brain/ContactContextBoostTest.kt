package io.raijin.brain

import io.raijin.brain.ContactScorer.ContextSignals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CT-5: context boost breaks ties between plausible phonetic matches and
 * never rescues a name the user probably did not say.
 */
class ContactContextBoostTest {

    private val now = 1_800_000_000L // fixed epoch for determinism

    @Test
    fun recentMessageSenderBoosts() {
        val boost = ContactScorer.contextBoost(ContextSignals(isRecentMessageSender = true), 0.7, now)
        assertEquals(0.06, boost, 1e-9)
    }

    @Test
    fun recencyTiers() {
        val withinDay = ContactScorer.contextBoost(ContextSignals(lastContactEpochSec = now - 3600), 0.7, now)
        val withinWeek = ContactScorer.contextBoost(ContextSignals(lastContactEpochSec = now - 3 * 86_400), 0.7, now)
        val withinMonth = ContactScorer.contextBoost(ContextSignals(lastContactEpochSec = now - 15 * 86_400), 0.7, now)
        val old = ContactScorer.contextBoost(ContextSignals(lastContactEpochSec = now - 90 * 86_400), 0.7, now)
        assertEquals(0.05, withinDay, 1e-9)
        assertEquals(0.03, withinWeek, 1e-9)
        assertEquals(0.01, withinMonth, 1e-9)
        assertEquals(0.0, old, 1e-9)
    }

    @Test
    fun boostIsCapped() {
        val rich = ContextSignals(
            callCount30d = 50,
            lastContactEpochSec = now - 100,
            sameDaypartCount = 10,
            isRecentMessageSender = true,
        )
        assertEquals(ContactScorer.MAX_BOOST, ContactScorer.contextBoost(rich, 0.7, now), 1e-9)
    }

    @Test
    fun weakPhoneticMatchGetsNoBoost() {
        val rich = ContextSignals(callCount30d = 50, isRecentMessageSender = true)
        assertEquals(0.0, ContactScorer.contextBoost(rich, ContactScorer.BOOST_FLOOR - 0.01, now), 1e-9)
    }

    @Test
    fun tieBreakByContext() {
        // Two equally-named contacts; the recently-messaged one must rank first.
        // The query is deliberately inexact ("rahul sharm") so the base score
        // stays below the 1.0 clamp and the boost difference survives.
        val candidates = listOf(
            Triple("id-rahul-cold", "Rahul Sharma", ContextSignals()),
            Triple("id-rahul-hot", "Rahul Sharma", ContextSignals(isRecentMessageSender = true)),
        )
        val ranked = ContactScorer.rankWithContext(candidates, listOf("rahul sharm"), now)
        assertEquals(2, ranked.size)
        assertEquals("id-rahul-hot", ranked[0].contactId)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun exactMatchTieStillBreaksByContext() {
        // Regression for the saturation bug: an exact phonetic match scores
        // 1.0 for both candidates, so the boosted score clamps to 1.0 for
        // both - ordering must still come from the unclamped context rank.
        val candidates = listOf(
            Triple("id-rahul-cold", "Rahul Sharma", ContextSignals()),
            Triple("id-rahul-hot", "Rahul Sharma", ContextSignals(isRecentMessageSender = true)),
        )
        val ranked = ContactScorer.rankWithContext(candidates, listOf("rahul sharma"), now)
        assertEquals(2, ranked.size)
        assertEquals("id-rahul-hot", ranked[0].contactId)
        // Output scores stay clamped even though the hot contact's raw
        // boosted score exceeded 1.0.
        assertTrue(ranked.all { it.score <= 1.0 })
        assertEquals(1.0, ranked[0].score, 1e-9)
    }

    @Test
    fun scoresClampToOne() {
        val candidates = listOf(
            Triple("id-exact", "Rahul Sharma", ContextSignals(isRecentMessageSender = true, lastContactEpochSec = now - 60)),
        )
        val ranked = ContactScorer.rankWithContext(candidates, listOf("rahul sharma"), now)
        assertTrue(ranked[0].score <= 1.0)
    }

    @Test
    fun entryRankingPrefersTheOftenCalledContactOnATie() {
        val entries = listOf(
            ContactScorer.Entry("a", "Rahul Sharma"),
            ContactScorer.Entry("b", "Rahul Verma"),
        )
        val signals = mapOf(
            "b" to ContextSignals(callCount30d = 25, lastContactEpochSec = now - 3600, sameDaypartCount = 6),
        )
        val ranked = ContactScorer.rankEntriesWithContext(entries, listOf("rahul"), signals, now)
        assertEquals("b", ranked.first().contactId)
    }

    @Test
    fun entryRankingWithoutSignalsMatchesPlainRanking() {
        val entries = listOf(ContactScorer.Entry("a", "Rahul Sharma"), ContactScorer.Entry("b", "Priya"))
        val plain = ContactScorer.rankEntries(entries, listOf("rahul"))
        val ctx = ContactScorer.rankEntriesWithContext(entries, listOf("rahul"), emptyMap(), now)
        assertEquals(plain.map { it.contactId }, ctx.map { it.contactId })
    }

    @Test
    fun contextNeverRescuesAnUnrelatedName() {
        val entries = listOf(ContactScorer.Entry("a", "Zoya Khan"), ContactScorer.Entry("b", "Rahul"))
        val signals = mapOf("a" to ContextSignals(callCount30d = 50, lastContactEpochSec = now - 60, isRecentMessageSender = true))
        val ranked = ContactScorer.rankEntriesWithContext(entries, listOf("rahul"), signals, now)
        assertEquals("b", ranked.first().contactId)
    }
}
