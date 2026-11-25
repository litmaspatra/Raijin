package io.raijin.brain

/**
 * CT-4/CT-6: score a contact candidate against an STT transcript, and rank
 * candidates including every n-best STT alternative.
 */
object ContactScorer {

    data class Match(
        val contactId: String,
        val contactName: String,
        val score: Double,
        val matchedVia: String, // which transcript alternative produced the best score
    )

    /**
     * Best score for one contact across all STT alternatives.
     */
    fun bestMatch(contactId: String, contactName: String, sttAlternatives: List<String>): Match? {
        var best: Match? = null
        for (alt in sttAlternatives) {
            val score = ContactPhonetics.similarity(alt, contactName)
            if (best == null || score > best.score) {
                best = Match(contactId, contactName, score, alt)
            }
        }
        return best
    }

    /**
     * Rank all candidates by their best score across the n-best alternatives,
     * highest first. Empty alternatives list yields an empty ranking.
     */
    fun rank(
        candidates: List<Pair<String, String>>, // (contactId, displayName)
        sttAlternatives: List<String>,
    ): List<Match> {
        if (sttAlternatives.isEmpty()) return emptyList()
        return candidates.mapNotNull { (id, name) -> bestMatch(id, name, sttAlternatives) }
            .sortedByDescending { it.score }
    }

    /**
     * A contact candidate with optional aliases (CT-5): the display name is
     * what is spoken back; aliases are extra spoken forms the same contact
     * answers to ("Rahul bhai", "Rahul work"). Scoring takes the best over
     * the display name and every alias, so an alias query resolves to the
     * canonical contact.
     */
    data class Entry(
        val contactId: String,
        val displayName: String,
        val aliases: List<String> = emptyList(),
        /**
         * CT-10: the address-book number label ("mobile", "work", "home"...).
         * Matched as the extra name form "<displayName> <label>", so
         * "call Rahul work" resolves the work number with no grammar change.
         */
        val numberLabel: String? = null,
    )

    /**
     * Best score for one entry across all STT alternatives and all of the
     * entry's name forms (display name plus aliases). [Match.matchedVia]
     * names the STT alternative that produced the best score; the winning
     * name form is not reported separately because scoring is symmetric in
     * the candidate string.
     */
    fun bestMatchEntry(entry: Entry, sttAlternatives: List<String>): Match? {
        var best: Match? = null
        val labelForm = entry.numberLabel
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf("${entry.displayName} $it") }
            .orEmpty()
        for (nameForm in listOf(entry.displayName) + entry.aliases + labelForm) {
            for (alt in sttAlternatives) {
                val score = ContactPhonetics.similarity(alt, nameForm)
                if (best == null || score > best.score) {
                    best = Match(entry.contactId, entry.displayName, score, alt)
                }
            }
        }
        return best
    }

    /**
     * Rank entries (with aliases) by best score across the n-best
     * alternatives, highest first.
     */
    fun rankEntries(entries: List<Entry>, sttAlternatives: List<String>): List<Match> {
        if (sttAlternatives.isEmpty()) return emptyList()
        return entries.mapNotNull { bestMatchEntry(it, sttAlternatives) }
            .sortedByDescending { it.score }
    }

    /**
     * CT-6 confidence bands: >= 0.85 with margin >= 0.15 proceeds,
     * 0.50-0.85 asks, below 0.50 is a miss.
     */
    enum class Confidence { PROCEED, ASK, MISS }

    fun confidenceOf(ranking: List<Match>): Confidence {
        val top = ranking.firstOrNull() ?: return Confidence.MISS
        if (top.score < 0.50) return Confidence.MISS
        if (top.score >= 0.85) {
            val runnerUp = ranking.getOrNull(1)?.score ?: 0.0
            if (top.score - runnerUp >= 0.15) return Confidence.PROCEED
        }
        return Confidence.ASK
    }

    // ------------------------------------------------------------------
    // CT-5: context boost
    // ------------------------------------------------------------------

    /**
     * CT-5: per-contact interaction context, supplied by the app (call log,
     * recency, time-of-day pattern, recent message senders). All fields
     * default to "no signal" so existing callers are unaffected.
     */
    data class ContextSignals(
        /** Outgoing+incoming calls with this contact in the last 30 days. */
        val callCount30d: Int = 0,
        /** Last call/message timestamp (epoch seconds), null if never. */
        val lastContactEpochSec: Long? = null,
        /** Past interactions in the same daypart as now (morning/evening...). */
        val sameDaypartCount: Int = 0,
        /** This contact sent a message that arrived recently. */
        val isRecentMessageSender: Boolean = false,
    )

    /**
     * Total boost is capped at [MAX_BOOST] and only applies above
     * [BOOST_FLOOR]: context must break ties between plausible phonetic
     * matches, never rescue a name the user probably did not say.
     */
    const val MAX_BOOST = 0.10
    const val BOOST_FLOOR = 0.30

    fun contextBoost(signals: ContextSignals, baseScore: Double, nowEpochSec: Long): Double {
        if (baseScore < BOOST_FLOOR) return 0.0
        var boost = 0.0
        if (signals.isRecentMessageSender) boost += 0.06
        val last = signals.lastContactEpochSec
        if (last != null) {
            val ageSec = nowEpochSec - last
            boost += when {
                ageSec <= 86_400L -> 0.05
                ageSec <= 7 * 86_400L -> 0.03
                ageSec <= 30 * 86_400L -> 0.01
                else -> 0.0
            }
        }
        boost += when {
            signals.callCount30d >= 20 -> 0.04
            signals.callCount30d >= 5 -> 0.02
            else -> 0.0
        }
        if (signals.sameDaypartCount >= 4) boost += 0.02
        return minOf(boost, MAX_BOOST)
    }

    /**
     * Rank candidates with CT-5 context applied on top of CT-4 phonetic
     * scores. Deterministic: same scores, signals and clock give the same
     * order (stable sort keeps phonetic order for exact ties).
     */
    fun rankWithContext(
        candidates: List<Triple<String, String, ContextSignals>>, // (id, name, signals)
        sttAlternatives: List<String>,
        nowEpochSec: Long,
    ): List<Match> {
        if (sttAlternatives.isEmpty()) return emptyList()
        // Sort on the unclamped score: an exact phonetic match saturates at
        // 1.0, so clamping before the sort would erase the context boost and
        // break ties in phonetic order instead of context order.
        return candidates.mapNotNull { (id, name, signals) ->
            bestMatch(id, name, sttAlternatives)?.let { m ->
                m to (m.score + contextBoost(signals, m.score, nowEpochSec))
            }
        }.sortedByDescending { it.second }
            .map { (m, boosted) -> m.copy(score = minOf(1.0, boosted)) }
    }
}
