package io.raijin.brain

import kotlin.math.max
import kotlin.math.min

/**
 * CT-4: phonetic keys tuned for Indian-name confusions, plus string-similarity
 * scorers. Everything operates on [ContactNameNormalizer] output.
 */
object ContactPhonetics {

    /**
     * A coarse key: names that sound alike to an Indian speaker produce the
     * same key. Confusions folded (PRD CT-4): v/w, sh/s, th/t, d/dh, ph/f,
     * z/j, ri/ru, repeated vowels (already collapsed), double consonants,
     * nasal endings (n/m/ng at word end).
     */
    fun phoneticKey(normalized: String): String {
        // Schwa-style skeletonisation, applied identically to both scripts:
        // drop 'a' between two consonants and word-final 'a' after a
        // consonant. This aligns mechanical Devanagari transliterations
        // ("sharama", "kartika") with common Latin spellings ("sharma",
        // "kartik").
        var k = normalized.split(' ').joinToString(" ") { w ->
            val sb = StringBuilder()
            for (i in w.indices) {
                val ch = w[i]
                if (ch == 'a' && i > 0 && w[i - 1] !in "aeiou" &&
                    (i == w.lastIndex || w[i + 1] !in "aeiou")
                ) {
                    continue
                }
                sb.append(ch)
            }
            sb.toString()
        }
        // Digraph folds first, longest patterns before their prefixes.
        k = k.replace("chh", "ch")
        k = k.replace("ksh", "ch")     // क्ष often realised as ch/kch
        k = k.replace("sh", "s")
        k = k.replace("th", "t")
        k = k.replace("dh", "d")
        k = k.replace("ph", "f")
        k = k.replace("gh", "g")
        k = k.replace("bh", "b")
        k = k.replace("jh", "j")
        k = k.replace("kh", "k")
        // Single-letter folds
        k = k.replace('w', 'v')
        k = k.replace('z', 'j')
        k = k.replace('q', 'k')
        k = k.replace("x", "ks")
        // ri/ru confusion
        k = k.replace("ri", "ru")
        // Collapse double consonants
        k = k.replace(Regex("(.)\\1+"), "$1")
        // Nasal endings fold (sharmaN/sharmaM), per word
        k = k.split(' ').joinToString(" ") { w ->
            when {
                w.length > 2 && (w.endsWith("ng")) -> w.dropLast(2)
                w.length > 2 && (w.endsWith("n") || w.endsWith("m")) -> w.dropLast(1)
                else -> w
            }
        }
        return k.replace(" ", "")
    }

    /** Classic Levenshtein edit distance. */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** Jaro-Winkler similarity in [0, 1]. */
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val jaro = jaro(a, b)
        val prefix = a.commonPrefixWith(b).length.coerceAtMost(4)
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    private fun jaro(s1: String, s2: String): Double {
        val matchDist = max(s1.length, s2.length) / 2 - 1
        val m1 = BooleanArray(s1.length)
        val m2 = BooleanArray(s2.length)
        var matches = 0
        for (i in s1.indices) {
            val start = max(0, i - matchDist)
            val end = min(i + matchDist + 1, s2.length)
            for (j in start until end) {
                if (!m2[j] && s1[i] == s2[j]) {
                    m1[i] = true; m2[j] = true; matches++; break
                }
            }
        }
        if (matches == 0) return 0.0
        var t = 0
        var k = 0
        for (i in s1.indices) {
            if (!m1[i]) continue
            while (!m2[k]) k++
            if (s1[i] != s2[k]) t++
            k++
        }
        val transpositions = t / 2
        return (matches.toDouble() / s1.length +
            matches.toDouble() / s2.length +
            (matches - transpositions).toDouble() / matches) / 3.0
    }

    /**
     * Combined similarity in [0, 1] between a transcript fragment and a
     * contact name (both raw - normalisation happens inside).
     * Equal phonetic keys floor the score at 0.92; otherwise Jaro-Winkler on
     * normalised forms blended with normalised edit distance.
     */
    fun similarity(rawQuery: String, rawCandidate: String): Double {
        val q = ContactNameNormalizer.normalize(rawQuery)
        val c = ContactNameNormalizer.normalize(rawCandidate)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0
        val keyEqual = phoneticKey(q) == phoneticKey(c)
        val jw = jaroWinkler(q, c)
        val dist = editDistance(q, c)
        val editSim = 1.0 - dist.toDouble() / max(q.length, c.length)
        val blended = 0.6 * jw + 0.4 * editSim
        return if (keyEqual) maxOf(0.92, blended) else blended
    }
}
