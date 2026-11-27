package com.raijin.app.cloud

import android.util.Log
import io.raijin.brain.ProviderFailureKind
import io.raijin.brain.ProviderFailoverPolicy
import io.raijin.contract.CloudReasoningPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Cloud reasoning with automatic provider failover (PRD BR-3, BR-4).
 *
 * For each request the brain-core [ProviderFailoverPolicy] picks up to
 * [ProviderFailoverPolicy.maxAttempts] providers in priority order; each is
 * tried in turn and outcomes are recorded back into the registry so the
 * circuit breaker, Retry-After and needs-attention state survive restarts.
 */
class FailoverCloudReasoningPort(
    private val registry: ProviderRegistryStore,
    private val policy: ProviderFailoverPolicy = ProviderFailoverPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) : CloudReasoningPort {

    private val sharedHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override val isConfigured: Boolean
        get() = registry.effectiveProviders().any { (config, health, key) ->
            config.enabled && !health.needsAttention &&
                config.baseUrl.isNotBlank() && (key.isNotBlank() || isLocalEndpoint(config.baseUrl))
        }

    override suspend fun complete(
        prompt: String,
        systemPrompt: String?,
        temperature: Float,
    ): String = runWithFailover { client -> client.complete(prompt, systemPrompt, temperature) }

    /**
     * BR-7: streams from the first working provider. Once text has started
     * arriving it is already being shown and spoken, so a later failure
     * returns what arrived instead of restarting on another provider.
     */
    override suspend fun completeStreaming(
        prompt: String,
        systemPrompt: String?,
        temperature: Float,
        onDelta: (String) -> Unit,
    ): String {
        val partial = StringBuilder()
        return try {
            runWithFailover { client ->
                client.completeStreaming(prompt, systemPrompt, temperature) { d ->
                    partial.append(d)
                    onDelta(d)
                }
            }
        } catch (e: Exception) {
            if (partial.isNotBlank()) partial.toString().trim() else throw e
        }
    }

    private suspend fun runWithFailover(
        call: suspend (OpenAiCompatibleClient) -> String,
    ): String = withContext(Dispatchers.IO) {
        val statesById = registry.toPolicyStates()
        val plan = policy.plan(statesById.values.toList(), clock())
        if (plan.attempts.isEmpty()) {
            throw IOException("No cloud provider is available right now. Check provider keys and settings.")
        }

        val configs = registry.effectiveProviders().associate { (c, _, key) -> c.id to (c to key) }
        var lastError: Exception = IOException("No provider attempted")

        for (state in plan.attempts) {
            val (config, apiKey) = configs[state.id] ?: continue
            val client = OpenAiCompatibleClient(
                configProvider = {
                    OpenAiConfig(
                        baseUrl = config.baseUrl,
                        apiKey = apiKey,
                        model = config.model,
                        timeoutSeconds = config.timeoutSeconds,
                    )
                },
                client = sharedHttpClient,
            )
            try {
                val answer = call(client)
                registry.recordHealth(state.id, policy.onSuccess(state).toHealth())
                if (state.id != plan.attempts.first().id) {
                    Log.i(TAG, "failover: '${state.id}' answered after earlier provider(s) failed")
                }
                return@withContext answer
            } catch (e: SocketTimeoutException) {
                lastError = e
                registry.recordHealth(state.id, policy.onFailure(state, ProviderFailureKind.TIMEOUT, nowEpochMs = clock()).toHealth())
            } catch (e: CloudCallException) {
                lastError = e
                val kind = when (e.httpCode) {
                    401, 403 -> ProviderFailureKind.UNAUTHORIZED
                    429 -> ProviderFailureKind.RATE_LIMITED
                    in 500..599 -> ProviderFailureKind.SERVER
                    else -> ProviderFailureKind.NETWORK
                }
                registry.recordHealth(state.id, policy.onFailure(state, kind, retryAfterMs = e.retryAfterMs, nowEpochMs = clock()).toHealth())
            } catch (e: IOException) {
                lastError = e
                registry.recordHealth(state.id, policy.onFailure(state, ProviderFailureKind.NETWORK, nowEpochMs = clock()).toHealth())
            }
            Log.w(TAG, "provider '${state.id}' failed (${lastError.message}); trying next if any")
        }

        throw IOException("All configured cloud providers failed. Last error: ${lastError.message}")
    }

    private fun isLocalEndpoint(baseUrl: String): Boolean =
        baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1") ||
            baseUrl.contains("192.168.") || baseUrl.contains(":11434")

    private fun io.raijin.brain.ProviderState.toHealth() = ProviderHealth(
        consecutiveFailures = consecutiveFailures,
        cooldownUntilEpochMs = cooldownUntilEpochMs,
        needsAttention = needsAttention,
    )

    companion object {
        private const val TAG = "FailoverCloudPort"
    }
}
