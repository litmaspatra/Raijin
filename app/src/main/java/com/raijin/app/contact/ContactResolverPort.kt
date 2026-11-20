package com.raijin.app.contact

// ---------------------------------------------------------------------------
// ResolveResult — what comes back from ContactResolverPort.resolve()
// Kept here (not in legacy/) because ContactResolverPort is a non-quarantined
// port interface; the implementation (ContactResolver) is in legacy/.
// ---------------------------------------------------------------------------

sealed class ResolveResult {
    /** Confident single match */
    data class Single(val entity: ContactEntity, val confidence: Float) : ResolveResult()

    /** Multiple plausible candidates — caller must disambiguate with user */
    data class Multiple(
        val candidates: List<ContactEntity>,
        val query: String,
        val confidenceByPhone: Map<String, Float> = emptyMap(),
    ) : ResolveResult()

    /** Nothing in the contact graph matched */
    object NotFound : ResolveResult()
}

// ---------------------------------------------------------------------------
// ContactResolverPort — narrow interface for FastBrain / CommandProcessor
// Allows test doubles without instantiating ContactStore (a Room database).
// ---------------------------------------------------------------------------

interface ContactResolverPort {
    suspend fun resolve(query: String): ResolveResult
    suspend fun resolveAlternatives(queries: List<String>): ResolveResult =
        resolve(queries.firstOrNull().orEmpty())

    /**
     * CT-7: persist a confirmed spoken alias for the contact at [phoneNumber],
     * so the same spoken form resolves instantly next time. Default no-op for
     * test doubles.
     */
    suspend fun learnAlias(spokenForm: String, phoneNumber: String, displayName: String) {}

    /**
     * CT-7: permanently record that [wrongAlias] resolved to the contact at
     * [phoneNumber] and the owner rejected it; future resolutions of that
     * alias deprioritize that contact. Default no-op for test doubles.
     */
    suspend fun recordCorrection(wrongAlias: String, phoneNumber: String) {}
}
