package com.raijin.app.cloud

import android.content.Context
import android.util.Log
import io.raijin.brain.ProviderFailoverPolicy
import io.raijin.brain.ProviderState
import org.json.JSONArray
import org.json.JSONObject

/**
 * One cloud provider entry (PRD BR-2). Non-secret fields persist in
 * SharedPreferences as JSON; the API key lives in [KeystoreSecretStore]
 * under a per-provider slot and is never written here.
 */
data class ProviderConfig(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val priority: Int,
    val enabled: Boolean = true,
    val timeoutSeconds: Long = 60L,
    val supportsTools: Boolean = false,
    val supportsStreaming: Boolean = false,
)

/** Persisted health for the failover circuit breaker (PRD BR-3). */
data class ProviderHealth(
    val consecutiveFailures: Int = 0,
    val cooldownUntilEpochMs: Long = 0L,
    val needsAttention: Boolean = false,
)

/**
 * Registry of OpenAI-compatible providers (PRD BR-2). Storage layout:
 * - "providers_json": JSONArray of ProviderConfig + ProviderHealth (non-secret)
 * - "key_enc_<id>": Keystore-encrypted API key per provider
 *
 * Until the multi-provider settings UI lands, an empty registry transparently
 * falls back to the legacy single [OpenAiConfigStore] entry, so the existing
 * settings screen keeps working unchanged.
 */
class ProviderRegistryStore(context: Context) {
    private val prefs = context.getSharedPreferences("raijin_provider_registry", Context.MODE_PRIVATE)
    private val userPrefs = com.raijin.app.prefs.UserPreferencesStore(context)
    private val secrets = KeystoreSecretStore()
    private val legacyStore = OpenAiConfigStore(context)

    fun listProviders(): List<Pair<ProviderConfig, ProviderHealth>> {
        val json = prefs.getString(KEY_PROVIDERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ProviderConfig(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    baseUrl = o.getString("baseUrl"),
                    model = o.getString("model"),
                    priority = o.getInt("priority"),
                    enabled = o.optBoolean("enabled", true),
                    timeoutSeconds = o.optLong("timeoutSeconds", 60L),
                    supportsTools = o.optBoolean("supportsTools", false),
                    supportsStreaming = o.optBoolean("supportsStreaming", false),
                ) to ProviderHealth(
                    consecutiveFailures = o.optInt("consecutiveFailures", 0),
                    cooldownUntilEpochMs = o.optLong("cooldownUntilEpochMs", 0L),
                    needsAttention = o.optBoolean("needsAttention", false),
                )
            }
        }.onFailure { Log.w(TAG, "provider registry unreadable, ignoring: ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
    }

    fun saveProviders(providers: List<Pair<ProviderConfig, ProviderHealth>>) {
        val array = JSONArray()
        providers.forEach { (c, h) ->
            array.put(JSONObject().apply {
                put("id", c.id)
                put("label", c.label)
                put("baseUrl", c.baseUrl)
                put("model", c.model)
                put("priority", c.priority)
                put("enabled", c.enabled)
                put("timeoutSeconds", c.timeoutSeconds)
                put("supportsTools", c.supportsTools)
                put("supportsStreaming", c.supportsStreaming)
                put("consecutiveFailures", h.consecutiveFailures)
                put("cooldownUntilEpochMs", h.cooldownUntilEpochMs)
                put("needsAttention", h.needsAttention)
            })
        }
        prefs.edit().putString(KEY_PROVIDERS, array.toString()).apply()
    }

    fun upsertProvider(config: ProviderConfig, apiKey: String? = null) {
        val current = listProviders().toMutableList()
        val idx = current.indexOfFirst { it.first.id == config.id }
        val health = if (idx >= 0) current[idx].second else ProviderHealth()
        if (idx >= 0) current[idx] = config to health else current += config to health
        saveProviders(current)
        if (apiKey != null) setApiKey(config.id, apiKey)
    }

    fun removeProvider(id: String) {
        saveProviders(listProviders().filterNot { it.first.id == id })
        prefs.edit().remove("$KEY_ENC_PREFIX$id").apply()
    }

    fun getApiKey(id: String): String = secrets.decrypt(prefs.getString("$KEY_ENC_PREFIX$id", null)) ?: ""

    fun setApiKey(id: String, apiKey: String) {
        val enc = secrets.encrypt(apiKey.trim())
        prefs.edit().apply {
            if (enc != null) putString("$KEY_ENC_PREFIX$id", enc) else remove("$KEY_ENC_PREFIX$id")
        }.apply()
    }

    /** The owner edited a provider's key/config: clear its attention flag (BR-3). */
    fun clearAttention(id: String) {
        saveProviders(listProviders().map { (c, h) -> if (c.id == id) c to h.copy(needsAttention = false, consecutiveFailures = 0, cooldownUntilEpochMs = 0L) else c to h })
    }

    fun recordHealth(id: String, health: ProviderHealth) {
        saveProviders(listProviders().map { (c, h) -> if (c.id == id) c to health else c to h })
    }

    /**
     * Effective provider set for the brain: the registry when populated,
     * otherwise the legacy single config as one implicit provider.
     */
    fun effectiveProviders(): List<Triple<ProviderConfig, ProviderHealth, String>> {
        val registered = listProviders()
        if (registered.isNotEmpty()) {
            return registered.map { (c, h) -> Triple(c, h, getApiKey(c.id)) }
        }
        val legacy = legacyStore.load()
        if (legacy.baseUrl.isBlank()) return emptyList()
        return listOf(
            Triple(
                ProviderConfig(
                    id = LEGACY_PROVIDER_ID,
                    label = "Default",
                    baseUrl = legacy.baseUrl,
                    model = legacy.model,
                    priority = 1,
                    timeoutSeconds = legacy.timeoutSeconds,
                ),
                ProviderHealth(),
                legacy.apiKey,
            ),
        )
    }

    /** Converts to the brain-core policy view. */
    /**
     * ME-5: the owner's preferred provider order overrides stored priorities:
     * preferred ids rank first in that order, the rest keep their relative
     * stored priority after them.
     */
    private fun effectivePriority(config: ProviderConfig): Int {
        val preferred = userPrefs.preferredProviders
        val idx = preferred.indexOf(config.id)
        return if (preferred.isEmpty() || idx < 0) preferred.size + config.priority else idx
    }

    fun toPolicyStates(): Map<String, ProviderState> =
        effectiveProviders().associate { (c, h, _) ->
            c.id to ProviderState(
                id = c.id,
                priority = effectivePriority(c),
                enabled = c.enabled,
                consecutiveFailures = h.consecutiveFailures,
                cooldownUntilEpochMs = h.cooldownUntilEpochMs,
                needsAttention = h.needsAttention,
            )
        }

    companion object {
        private const val TAG = "ProviderRegistry"
        private const val KEY_PROVIDERS = "providers_json"
        private const val KEY_ENC_PREFIX = "key_enc_"
        const val LEGACY_PROVIDER_ID = "legacy-default"
    }
}
