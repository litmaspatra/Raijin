package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderFailoverPolicyTest {
    private val policy = ProviderFailoverPolicy()
    private val now = 1_000_000L

    private fun provider(
        id: String,
        priority: Int,
        enabled: Boolean = true,
        consecutiveFailures: Int = 0,
        cooldownUntilEpochMs: Long = 0L,
        needsAttention: Boolean = false,
    ) = ProviderState(id, priority, enabled, consecutiveFailures, cooldownUntilEpochMs, needsAttention)

    @Test
    fun planOrdersByPriorityAndCapsAtMaxAttempts() {
        val plan = policy.plan(
            listOf(provider("c", 3), provider("a", 1), provider("b", 2), provider("d", 4)),
            now,
        )
        assertEquals(listOf("a", "b", "c"), plan.attempts.map { it.id })
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun planSkipsDisabledCoolingDownAndNeedsAttention() {
        val plan = policy.plan(
            listOf(
                provider("disabled", 1, enabled = false),
                provider("cooling", 2, cooldownUntilEpochMs = now + 5_000),
                provider("badkey", 3, needsAttention = true),
                provider("good", 4),
            ),
            now,
        )
        assertEquals(listOf("good"), plan.attempts.map { it.id })
        assertEquals(listOf("disabled", "cooling", "badkey"), plan.skipped.map { it.id })
    }

    @Test
    fun expiredCooldownAllowsAttemptAgain() {
        val plan = policy.plan(
            listOf(provider("back", 1, cooldownUntilEpochMs = now - 1)),
            now,
        )
        assertEquals(listOf("back"), plan.attempts.map { it.id })
    }

    @Test
    fun unauthorizedMarksNeedsAttentionWithoutRetry() {
        val updated = policy.onFailure(provider("p", 1), ProviderFailureKind.UNAUTHORIZED, nowEpochMs = now)
        assertTrue(updated.needsAttention)
        assertEquals(0, updated.consecutiveFailures)
        val plan = policy.plan(listOf(updated), now)
        assertTrue(plan.attempts.isEmpty())
    }

    @Test
    fun circuitTripsAtThresholdWithInitialCooldown() {
        var p = provider("p", 1)
        p = policy.onFailure(p, ProviderFailureKind.NETWORK, nowEpochMs = now)
        p = policy.onFailure(p, ProviderFailureKind.NETWORK, nowEpochMs = now)
        assertEquals(0L, p.cooldownUntilEpochMs)
        p = policy.onFailure(p, ProviderFailureKind.TIMEOUT, nowEpochMs = now)
        assertEquals(3, p.consecutiveFailures)
        assertEquals(now + 60_000L, p.cooldownUntilEpochMs)
    }

    @Test
    fun cooldownDoublesOnRepeatedTripsAndCapsAtTenMinutes() {
        var p = provider("p", 1)
        repeat(3) { p = policy.onFailure(p, ProviderFailureKind.SERVER, nowEpochMs = now) }
        assertEquals(now + 60_000L, p.cooldownUntilEpochMs)
        p = policy.onFailure(p, ProviderFailureKind.SERVER, nowEpochMs = now + 60_000L)
        assertEquals(now + 60_000L + 120_000L, p.cooldownUntilEpochMs)
        p = policy.onFailure(p, ProviderFailureKind.SERVER, nowEpochMs = now + 180_000L)
        assertEquals(now + 180_000L + 240_000L, p.cooldownUntilEpochMs)
        // Drive it to the cap.
        var t = now + 180_000L + 240_000L
        repeat(10) {
            p = policy.onFailure(p, ProviderFailureKind.SERVER, nowEpochMs = t)
            t = p.cooldownUntilEpochMs
        }
        assertTrue(p.cooldownUntilEpochMs - (t - 600_000L) <= 600_000L)
        assertTrue(p.cooldownUntilEpochMs <= t)
    }

    @Test
    fun retryAfterIsHonouredEvenBelowCircuitThreshold() {
        val p = policy.onFailure(
            provider("p", 1),
            ProviderFailureKind.RATE_LIMITED,
            retryAfterMs = 30_000L,
            nowEpochMs = now,
        )
        assertEquals(now + 30_000L, p.cooldownUntilEpochMs)
        assertEquals(1, p.consecutiveFailures)
    }

    @Test
    fun successResetsStreakCooldownAndAttention() {
        val failed = provider("p", 1, consecutiveFailures = 5, cooldownUntilEpochMs = now + 999, needsAttention = true)
        val ok = policy.onSuccess(failed)
        assertEquals(0, ok.consecutiveFailures)
        assertEquals(0L, ok.cooldownUntilEpochMs)
        assertFalse(ok.needsAttention)
    }

    @Test
    fun ownerUpdateClearsAttentionFlag() {
        val flagged = provider("p", 1, needsAttention = true)
        val cleared = policy.onOwnerUpdated(flagged)
        assertFalse(cleared.needsAttention)
        val plan = policy.plan(listOf(cleared), now)
        assertEquals(listOf("p"), plan.attempts.map { it.id })
    }

    @Test
    fun singleProviderRequestNeverExceedsBudget() {
        val plan = policy.plan(listOf(provider("only", 1)), now)
        assertEquals(1, plan.attempts.size)
    }
}
