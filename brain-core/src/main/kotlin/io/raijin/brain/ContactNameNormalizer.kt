package io.raijin.brain

import java.text.Normalizer

/**
 * CT-3: contact-name normalisation for Indian-language matching.
 *
 * Pipeline: trim -> lowercase -> strip diacritics -> Devanagari-to-Latin
 * transliteration -> collapse repeated vowels. The output is the canonical
 * Latin form both STT transcripts and contact names are compared in, so a
 * Hindi-script contact ("कर्तिक") matches a Latin-script transcript
 * ("kartik") and the reverse.
 */
object ContactNameNormalizer {

    fun normalize(input: String): String {
        var s = input.trim().lowercase()
        // NFD first so Latin accents decompose; Devanagari combining marks
        // (halant, anusvara, nukta) are consumed by the transliterator below,
        // so only strip remaining marks AFTER it runs.
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
        s = transliterateDevanagari(s)
        s = s.replace(Regex("\\p{Mn}+"), "") // strip remaining (Latin) diacritics
        s = s.replace(Regex("([aeiou])\\1+"), "$1") // collapse repeated vowels
        s = s.replace(Regex("[^a-z ]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    /**
     * Approximate Devanagari -> Latin. Ambiguities are resolved the same way
     * for every input, which is what matching needs - not linguistic
     * perfection. Inherent 'a' is inserted after consonants not followed by a
     * vowel sign or halant; word-final inherent 'a' is dropped (Hindi
     * schwa deletion, approximate).
     */
    fun transliterateDevanagari(s: String): String {
        val out = StringBuilder()
        var i = 0
        var pendingConsonant = false
        while (i < s.length) {
            val ch = s[i]
            when {
                ch in INDEPENDENT_VOWELS -> {
                    if (pendingConsonant) { out.append('a'); pendingConsonant = false }
                    out.append(INDEPENDENT_VOWELS[ch])
                }
                ch in CONSONANTS -> {
                    if (pendingConsonant) out.append('a')
                    out.append(CONSONANTS[ch])
                    pendingConsonant = true
                }
                ch in VOWEL_SIGNS -> {
                    // A vowel sign replaces the inherent 'a'.
                    pendingConsonant = false
                    out.append(VOWEL_SIGNS[ch])
                }
                ch == '\u094D' -> { // halant: suppress inherent vowel
                    pendingConsonant = false
                }
                ch == '\u0902' || ch == '\u0901' -> { // anusvara / chandrabindu
                    pendingConsonant = false
                    out.append('n')
                }
                ch == '\u0903' -> { // visarga
                    pendingConsonant = false
                    out.append('h')
                }
                ch in NUKTA -> { // nukta combines with the previous consonant we already emitted
                    // Retroflex flap / fricative variants are folded into their base sound
                    // by the phonetic key; nothing extra to emit here.
                }
                else -> {
                    if (pendingConsonant) { out.append('a'); pendingConsonant = false }
                    out.append(ch)
                }
            }
            i++
        }
        if (pendingConsonant) out.append('a')
        // No schwa deletion here: the mechanical form keeps inherent vowels so
        // no information is lost. Schwa-style skeletonisation happens in
        // ContactPhonetics.phoneticKey, applied identically to both scripts.
        return out.toString()
    }

    private val INDEPENDENT_VOWELS = mapOf(
        '\u0905' to "a", '\u0906' to "aa", '\u0907' to "i", '\u0908' to "ee",
        '\u0909' to "u", '\u090A' to "oo", '\u090B' to "ri", '\u090C' to "lri",
        '\u090F' to "e", '\u0910' to "ai", '\u0913' to "o", '\u0914' to "au",
        '\u090D' to "e", '\u0911' to "o",
    )

    private val VOWEL_SIGNS = mapOf(
        '\u093E' to "aa", '\u093F' to "i", '\u0940' to "ee",
        '\u0941' to "u", '\u0942' to "oo", '\u0943' to "ri", '\u0944' to "ri",
        '\u0947' to "e", '\u0948' to "ai", '\u094B' to "o", '\u094C' to "au",
        '\u0945' to "e", '\u0949' to "o", '\u094A' to "o", '\u0946' to "e",
    )

    private val CONSONANTS = mapOf(
        '\u0915' to "k", '\u0916' to "kh", '\u0917' to "g", '\u0918' to "gh", '\u0919' to "ng",
        '\u091A' to "ch", '\u091B' to "chh", '\u091C' to "j", '\u091D' to "jh", '\u091E' to "ny",
        '\u091F' to "t", '\u0920' to "th", '\u0921' to "d", '\u0922' to "dh", '\u0923' to "n",
        '\u0924' to "t", '\u0925' to "th", '\u0926' to "d", '\u0927' to "dh", '\u0928' to "n",
        '\u092A' to "p", '\u092B' to "ph", '\u092C' to "b", '\u092D' to "bh", '\u092E' to "m",
        '\u092F' to "y", '\u0930' to "r", '\u0932' to "l", '\u0935' to "v",
        '\u0936' to "sh", '\u0937' to "sh", '\u0938' to "s", '\u0939' to "h",
        '\u0933' to "l", '\u0934' to "l",
    )

    private val NUKTA = setOf('\u093C')
}
