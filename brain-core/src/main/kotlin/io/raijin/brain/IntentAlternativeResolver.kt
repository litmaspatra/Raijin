package io.raijin.brain

/**
 * STT phase 0: decide WHICH recognizer hypothesis the intent layer interprets.
 *
 * The recognizer returns n-best hypotheses ("right a note", "write a note").
 * Today only the primary reaches the intent probes, so one homophone error can
 * misroute the whole utterance. This resolver lets a non-primary hypothesis
 * drive intent detection ONLY under tight conditions:
 *
 *  1. No probe matches the primary (a recognizable primary is never replaced).
 *  2. Every probe that hits any hypothesis converges on the SAME hypothesis;
 *     divergent hits mean genuinely different actions and we must not guess.
 *  3. The candidate is textually close to the primary (a homophone-class
 *     fix), so a recognizer's wildly different guess can never hijack intent.
 *
 * The result always carries the raw primary so callers can mark and log any
 * substitution ("heard 'right', using 'write'"). An ambiguous result is null;
 * the caller then keeps the primary and the normal clarification paths apply.
 *
 * Pure JVM, no Android deps — unit-testable in the narrow route.
 */
class IntentAlternativeResolver(
    private val probes: List<Probe>,
) {

    data class Probe(val id: String, val matches: (String) -> Boolean)

    /**
     * A PROPOSED interpretation only — it carries no execution authority.
     * The caller re-derives every argument from [chosen] and routes through
     * the normal risk/confirmation gates (reversible-action confirms, egress
     * consent, contact confirmation). A substituted selection never skips
     * those gates, and ambiguity (null) preserves the raw transcript and
     * reaches the caller's clarification path before any consequential step.
     */
    data class IntentSelection(
        /** The recognizer's primary (raw) transcript. Never altered. */
        val primary: String,
        /** The hypothesis the intent layer should interpret. */
        val chosen: String,
        /** Probe that matched [chosen], or "none". */
        val probeId: String,
        /** True when [chosen] differs from [primary]. */
        val substituted: Boolean,
    )

    fun select(alternatives: List<String>): IntentSelection? {
        val clean = alternatives.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return null
        val primary = clean.first()

        // 1. A recognizable primary is always interpreted as itself.
        val primaryHit = probes.firstOrNull { it.matches(primary) }
        if (primaryHit != null) {
            return IntentSelection(primary, primary, primaryHit.id, substituted = false)
        }

        // 2. Collect each probe's best (first, highest-recognizer-confidence) hit.
        val hits = probes.mapNotNull { probe ->
            clean.firstOrNull { probe.matches(it) }?.let { probe to it }
        }
        if (hits.isEmpty()) return IntentSelection(primary, primary, "none", substituted = false)

        // 3. Converge-or-refuse: divergent probe hits = different actions. Ambiguous.
        val chosen = hits.map { it.second }.distinct().singleOrNull() ?: return null

        // 4. Homophone-class gate: only a close hypothesis may replace the primary.
        if (!isCloseVariant(primary, chosen)) return null

        // 5. Action-critical guard: never substitute when the differing tokens
        //    change digits or negation. A close alternative that flips "don't"
        //    or a number is a different instruction, not a homophone fix.
        if (diffTouchesDigitsOrNegation(primary, chosen)) return null

        return IntentSelection(primary, chosen, hits.first().first.id, substituted = true)
    }

    companion object {
        /**
         * True when [a] and [b] look like recognizer variants of the same words:
         * small edit distance relative to length. Blocks sentence-level swaps
         * ("play music" vs "write a note") while allowing homophone fixes
         * ("right a note" vs "write a note").
         */
        fun isCloseVariant(a: String, b: String): Boolean {
            val x = a.lowercase().trim()
            val y = b.lowercase().trim()
            if (x == y) return true
            // Calibrated so a homophone fix ("right a note" -> "write a note",
            // edit distance 4) passes while sentence-level swaps ("play some
            // music for me" -> "write a note") are refused.
            val budget = maxOf(2, maxOf(x.length, y.length) / 3)
            return levenshtein(x, y, budget) <= budget
        }

        private val NEGATION_TOKENS = setOf(
            "no", "not", "don't", "dont", "don’t", "never", "won't", "wont", "cancel", "stop",
        )

        /**
         * True when the tokens that differ between [a] and [b] include a digit
         * or a negation marker. Substitution across such a difference is never
         * a recognizer-variant fix; it changes the instruction itself.
         */
        fun diffTouchesDigitsOrNegation(a: String, b: String): Boolean {
            val tokensA = a.lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
            val tokensB = b.lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
            val differ = (tokensA - tokensB.toSet()) + (tokensB - tokensA.toSet())
            return differ.any { t ->
                t.any { it.isDigit() } ||
                    t.trim('\'', '"', '’', '.', ',', '!', '?') in NEGATION_TOKENS
            }
        }

        /** Bounded Levenshtein; returns budget + 1 as soon as the bound is exceeded. */
        internal fun levenshtein(a: String, b: String, budget: Int): Int {
            if (kotlin.math.abs(a.length - b.length) > budget) return budget + 1
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    if (cur[j] < rowMin) rowMin = cur[j]
                }
                if (rowMin > budget) return budget + 1
                prev = cur
            }
            return prev[b.length]
        }
    }
}
