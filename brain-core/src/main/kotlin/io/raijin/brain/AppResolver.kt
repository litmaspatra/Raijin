package io.raijin.brain

/**
 * AP-2: resolve a spoken app name to an installed app using the same fuzzy +
 * phonetic pipeline as contacts (CT-4), plus built-in spoken aliases
 * ("insta", "yt", "gpay", "whatsapp business").
 *
 * Pure logic: the app module feeds it launcher entries; ranking is unit-tested
 * in brain-core.
 */
object AppResolver {

    data class AppEntry(val packageName: String, val label: String)

    data class AppMatch(
        val packageName: String,
        val label: String,
        val score: Double,
        /** The query form (original or alias-expanded) that produced the score. */
        val matchedVia: String,
    )

    /**
     * Below this score a match is more likely a hijack of a generic word than
     * the app the user meant ("pay" -> Google Pay at ~0.58 must not resolve).
     */
    const val RESOLVE_THRESHOLD = 0.72

    /** Spoken-alias -> intended app name, compared after normalisation. */
    val BUILT_IN_ALIASES: Map<String, String> = mapOf(
        "insta" to "instagram",
        "ig" to "instagram",
        "yt" to "youtube",
        "wa" to "whatsapp",
        "whatsapp business" to "whatsapp business",
        "wa business" to "whatsapp business",
        "gpay" to "google pay",
        "g pay" to "google pay",
        "maps" to "google maps",
        "gmaps" to "google maps",
        "photos" to "google photos",
        "drive" to "google drive",
        "playstore" to "play store",
    )

    /**
     * Rank every installed app against the query, best first.
     * [learnedAliases] (normalized spoken form -> intended app name) come from
     * explicit owner corrections and outrank [BUILT_IN_ALIASES].
     */
    fun rank(query: String, apps: List<AppEntry>, learnedAliases: Map<String, String> = emptyMap()): List<AppMatch> {
        val q = ContactNameNormalizer.normalize(query)
        if (q.isEmpty() || apps.isEmpty()) return emptyList()
        val aliasTarget = learnedAliases[q] ?: BUILT_IN_ALIASES[q]
        return apps.map { app ->
            var best = ContactPhonetics.similarity(q, app.label)
            var via = q
            if (aliasTarget != null) {
                val aliasScore = ContactPhonetics.similarity(aliasTarget, app.label)
                if (aliasScore > best) {
                    best = aliasScore
                    via = aliasTarget
                }
            }
            AppMatch(app.packageName, app.label, best, via)
        }.sortedByDescending { it.score }
    }

    /** Best app for a spoken query, or null when nothing clears the threshold. */
    fun resolve(query: String, apps: List<AppEntry>, learnedAliases: Map<String, String> = emptyMap()): AppMatch? =
        rank(query, apps, learnedAliases).firstOrNull()?.takeIf { it.score >= RESOLVE_THRESHOLD }
}
