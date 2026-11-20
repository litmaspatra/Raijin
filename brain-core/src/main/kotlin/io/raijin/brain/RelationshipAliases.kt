package io.raijin.brain

/**
 * CT-2: relationship aliases for Indian families.
 *
 * Terms inside one group are treated as equivalent when matching a learned
 * contact alias, so "call Maa", "call my mother" and "मम्मी को फोन करो" all
 * reach a contact the owner once taught as "Maa". Expansion happens on the
 * query side only; nothing here guesses WHICH contact a relationship names -
 * that binding always comes from an explicit owner confirmation (CT-7).
 */
object RelationshipAliases {

    private val GROUPS: List<Set<String>> = listOf(
        setOf("maa", "ma", "mom", "mommy", "mummy", "mother", "amma", "aai", "मम्मी", "माँ", "मां", "अम्मा"),
        setOf("papa", "dad", "daddy", "father", "pitaji", "pita", "baba", "abba", "पापा", "पिताजी", "बाबा"),
        setOf("bhai", "bhaiya", "brother", "bro", "भाई", "भैया"),
        setOf("didi", "sister", "behen", "बहन", "दीदी", "akka", "didi ji"),
        setOf("dadi", "grandmother", "nani", "दादी", "नानी"),
        setOf("dada", "grandfather", "nana", "दादा", "नाना"),
        setOf("chacha", "mama", "uncle", "चाचा", "मामा"),
        setOf("chachi", "mami", "bua", "aunt", "aunty", "चाची", "मामी", "बुआ"),
        setOf("beta", "son", "बेटा"),
        setOf("beti", "daughter", "बेटी"),
    )

    private val LOOKUP: Map<String, Set<String>> = buildMap {
        for (group in GROUPS) {
            for (term in group) {
                put(term, group - term)
            }
        }
    }

    /** Filler words that never affect matching. */
    private val FILLERS = setOf("my", "meri", "mere", "मेरे", "मेरी", "ko", "को", "ke", "के", "ki", "की")

    private val TOKEN_SPLIT = Regex("\\s+")

    /**
     * Returns the query plus one variant per relationship token replaced by its
     * synonyms, capped at [MAX_EXPANSIONS]. The original query is always first.
     * Queries without any relationship term return only themselves.
     */
    fun expand(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val tokens = trimmed.lowercase().split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        val relIndex = tokens.indexOfFirst { LOOKUP.containsKey(it) }
        if (relIndex < 0) return listOf(trimmed)

        val synonyms = LOOKUP.getValue(tokens[relIndex])
        val head = tokens.subList(0, relIndex).joinToString(" ")
        val tail = tokens.subList(relIndex + 1, tokens.size).joinToString(" ")
        val expansions = mutableListOf(trimmed)
        for (syn in synonyms) {
            val variant = listOf(head, syn, tail).filter { it.isNotEmpty() }.joinToString(" ")
            if (variant.isNotEmpty() && variant !in expansions) expansions += variant
            if (expansions.size >= MAX_EXPANSIONS) break
        }
        return expansions
    }

    /** True when every token is a filler or relationship word (a pure role query). */
    fun isRelationshipQuery(query: String): Boolean {
        val tokens = query.trim().lowercase().split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        return tokens.all { it in FILLERS || LOOKUP.containsKey(it) } &&
            tokens.any { LOOKUP.containsKey(it) }
    }

    private const val MAX_EXPANSIONS = 14
}
