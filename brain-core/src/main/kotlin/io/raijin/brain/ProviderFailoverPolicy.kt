package io.raijin.brain

/**
 * Provider failover policy (PRD BR-3, BR-4). Pure Kotlin, JVM-testable
 * (PRD principle 7); the Android side supplies provider state and executes
 * the HTTP calls, this class owns the decisions.
 *
 * Rules (PRD 7.2):
 * - Fail over on 429, 5xx, timeout or network error; honour Retry-After.
 * - Circuit breaker: 3 consecutive failures cool a provider down for 60 s,
 *   doubling on repeated trips, capped at 10 min.
 * - 401/403 marks the provider "needs attention" and it is skipped until the
 *   owner fixes the key; the owner is told once (the caller surfaces it).
 * - Per-request budget: at most 3 provider attempts (BR-4).
 */

enum class ProviderFailureKind {
    RATE_LIMITED,   // HTTP 429
    SERVER,         // HTTP 5xx
    TIMEOUT,
    NETWORK,
    UNAUTHORIZED,   // HTTP 401/403
}

data class ProviderState(
    val id: String,
    /** Lower runs first. */
    val priority: Int,
    val enabled: Boolean = true,
    val consecutiveFailures: Int = 0,
    /** Epoch millis before which this provider must not be attempted. */
    val cooldownUntilEpochMs: Long = 0L,
    /** True after a 401/403 until the owner updates the key. */
    val needsAttention: Boolean = false,
)

data class FailoverPlan(
    /** Providers to attempt, in order, at most [ProviderFailoverPolicy.maxAttempts]. */
    val attempts: List<ProviderState>,
    /** Providers skipped because they are cooling down or need attention. */
    val skipped: List<ProviderState>,
)

class ProviderFailoverPolicy(
    val maxAttempts: Int = 3,
    val circuitThreshold: Int = 3,
    val initialCooldownMs: Long = 60_000L,
    val maxCooldownMs: Long = 600_000L,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(circuitThreshold > 0) { "circuitThreshold must be positive" }
        require(initialCooldownMs > 0) { "initialCooldownMs must be positive" }
        require(maxCooldownMs >= initialCooldownMs) { "maxCooldownMs must be >= initialCooldownMs" }
    }

    /**
     * Picks the attempt order for one request: enabled providers that are not
     * cooling down and not flagged needs-attention, lowest priority number
     * first, capped at [maxAttempts].
     */
    fun plan(providers: List<ProviderState>, nowEpochMs: Long): FailoverPlan {
        val (skipped, usable) = providers.partition {
            !it.enabled || it.needsAttention || it.cooldownUntilEpochMs > nowEpochMs
        }
        return FailoverPlan(
            attempts = usable.sortedBy { it.priority }.take(maxAttempts),
            skipped = skipped.sortedBy { it.priority },
        )
    }

    /**
     * Records a failure. Returns the updated state.
     *
     * UNAUTHORIZED sets needsAttention immediately (no retry, no circuit math).
     * Other kinds increment the streak; at [circuitThreshold] the provider
     * cools down. The cooldown doubles each time the circuit re-trips without
     * an intervening success, capped at [maxCooldownMs]. A provider told
     * Retry-After waits at least that long before its next attempt.
     */
    fun onFailure(
        provider: ProviderState,
        kind: ProviderFailureKind,
        retryAfterMs: Long? = null,
        nowEpochMs: Long,
    ): ProviderState {
        if (kind == ProviderFailureKind.UNAUTHORIZED) {
            return provider.copy(needsAttention = true)
        }
        val failures = provider.consecutiveFailures + 1
        var cooldownUntil = provider.cooldownUntilEpochMs
        if (failures >= circuitThreshold) {
            // Trips at 3, 4, 5... consecutive failures: 60s, 120s, 240s... capped.
            val trips = failures - circuitThreshold
            val cooldown = (initialCooldownMs shl trips.coerceAtMost(10)).coerceAtMost(maxCooldownMs)
            cooldownUntil = maxOf(cooldownUntil, nowEpochMs + cooldown)
        }
        if (retryAfterMs != null && retryAfterMs > 0) {
            cooldownUntil = maxOf(cooldownUntil, nowEpochMs + retryAfterMs)
        }
        return provider.copy(consecutiveFailures = failures, cooldownUntilEpochMs = cooldownUntil)
    }

    /** Records a success: resets the failure streak, cooldown and attention flag. */
    fun onSuccess(provider: ProviderState): ProviderState =
        provider.copy(consecutiveFailures = 0, cooldownUntilEpochMs = 0L, needsAttention = false)

    /** The owner saved a new key/config for the provider: clear the attention flag. */
    fun onOwnerUpdated(provider: ProviderState): ProviderState =
        provider.copy(needsAttention = false, consecutiveFailures = 0, cooldownUntilEpochMs = 0L)
}
