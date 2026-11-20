package com.raijin.app.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.raijin.contract.CloudReasoningPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Provider-Neutral OpenAI-Compatible Cloud LLM Client.
 *
 * Can connect to ANY endpoint conforming to the OpenAI Chat Completions specification:
 * - NVIDIA NIM: https://integrate.api.nvidia.com/v1 (e.g. meta/llama-3.1-70b-instruct)
 * - OpenAI: https://api.openai.com/v1 (e.g. gpt-4o, gpt-4o-mini)
 * - Groq: https://api.groq.com/openai/v1 (e.g. llama-3.3-70b-versatile)
 * - OpenRouter: https://openrouter.ai/api/v1 (e.g. anthropic/claude-3.5-sonnet)
 * - DeepSeek: https://api.deepseek.com/v1 (e.g. deepseek-chat)
 * - Together AI: https://api.together.xyz/v1
 * - Local / Self-hosted (Ollama, vLLM, LM Studio, LocalAI): http://<ip>:11434/v1
 */
/**
 * HTTP/transport failure with enough structure for the failover policy (BR-3):
 * the status code and the provider's Retry-After hint, when present.
 */
class CloudCallException(
    message: String,
    val httpCode: Int? = null,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

class OpenAiCompatibleClient(
    private val configProvider: () -> OpenAiConfig,
    client: OkHttpClient? = null,
) : CloudReasoningPort {

    companion object {
        private const val TAG = "OpenAiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override val isConfigured: Boolean
        get() {
            val config = configProvider()
            // Local endpoints (localhost/192.168/10.0/127.0.0.1) often require no API key
            val isLocal = config.baseUrl.contains("localhost") ||
                    config.baseUrl.contains("127.0.0.1") ||
                    config.baseUrl.contains("192.168.") ||
                    config.baseUrl.contains(":11434")
            return config.baseUrl.isNotBlank() && (config.apiKey.isNotBlank() || isLocal)
        }

    /**
     * Completes a prompt by dispatching to the configured OpenAI-compatible endpoint.
     */
    override suspend fun complete(
        prompt: String,
        systemPrompt: String?,
        temperature: Float,
    ): String = withContext(Dispatchers.IO) {
        val config = configProvider()
        val endpointUrl = normalizeChatCompletionsUrl(config.baseUrl)

        val messages = JSONArray().apply {
            if (!systemPrompt.isNullOrBlank()) {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val requestPayload = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("temperature", temperature.toDouble())
        }

        val body = requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val requestBuilder = Request.Builder()
            .url(endpointUrl)
            .post(body)
            .addHeader("Content-Type", "application/json")

        // Add bearer token if provided (omitted for unauthenticated local models like Ollama)
        if (config.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        // Helpful metadata headers for OpenRouter / proxies
        if (config.baseUrl.contains("openrouter.ai")) {
            requestBuilder.addHeader("HTTP-Referer", "https://github.com/litmaspatra/raijin")
            requestBuilder.addHeader("X-Title", "Raijin Personal Assistant")
        }

        Log.i(TAG, "Calling OpenAI-compatible endpoint: $endpointUrl [model: ${config.model}]")

        val response = try {
            httpClient.newCall(requestBuilder.build()).execute()
        } catch (e: IOException) {
            Log.e(TAG, "Network transport failure connecting to $endpointUrl: ${e.message}")
            throw IOException("Failed to connect to ${config.baseUrl}: ${e.message}", e)
        }

        response.use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "HTTP ${resp.code} from $endpointUrl: $responseBody")
                val errorMessage = when (resp.code) {
                    401 -> "Authentication failed: Invalid API key for provider."
                    403 -> "Access forbidden: Check model permissions or endpoint."
                    429 -> "Rate limit or quota exceeded on cloud provider."
                    500, 502, 503 -> "Provider server error (${resp.code}). Please try again later."
                    else -> "Cloud error (${resp.code}): ${parseErrorMessage(responseBody)}"
                }
                // Retry-After may be seconds (standard) or an HTTP date; seconds is what
                // the major OpenAI-compatible providers send.
                val retryAfterMs = resp.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000L)
                throw CloudCallException(errorMessage, httpCode = resp.code, retryAfterMs = retryAfterMs)
            }

            parseAssistantContent(responseBody)
        }
    }

    /**
     * Fetches the live list of model IDs available on this OpenAI-compatible endpoint.
     */
    suspend fun fetchModels(): Pair<Boolean, List<String>> = withContext(Dispatchers.IO) {
        val config = configProvider()
        val modelsUrl = normalizeModelsUrl(config.baseUrl)

        val requestBuilder = Request.Builder()
            .url(modelsUrl)
            .get()
            .addHeader("Accept", "application/json")

        if (config.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }
        if (config.baseUrl.contains("openrouter.ai")) {
            requestBuilder.addHeader("HTTP-Referer", "https://github.com/litmaspatra/raijin")
            requestBuilder.addHeader("X-Title", "Raijin Personal Assistant")
        }

        try {
            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "fetchModels HTTP ${resp.code}: $body")
                    return@withContext Pair(false, emptyList())
                }
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: JSONArray()
                val list = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i)
                    val id = item?.optString("id")
                    if (!id.isNullOrBlank()) {
                        list.add(id)
                    }
                }
                Pair(true, list.sorted())
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchModels failed: ${e.message}", e)
            Pair(false, emptyList())
        }
    }

    suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val config = configProvider()
        val (modelsOk, models) = fetchModels()
        try {
            val reply = complete("Hello", "Respond concisely with: 'Raijin Cloud Connected.'", 0.1f)
            Pair(true, reply)
        } catch (e: Exception) {
            val msg = e.message ?: "Connection test failed"
            if (msg.contains("404") || msg.contains("not found", ignoreCase = true)) {
                if (modelsOk && models.isNotEmpty()) {
                    Pair(false, "API Key & Endpoint Verified! However, model '${config.model}' was not found (404). Tap 'Fetch Models' to choose from ${models.size} available models.")
                } else {
                    Pair(false, "404 Not Found from ${config.baseUrl}. Please verify the Base URL or tap 'Fetch Models'.")
                }
            } else {
                Pair(false, msg)
            }
        }
    }

    fun normalizeChatCompletionsUrl(rawUrl: String): String {
        var trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed
        }
        if (!trimmed.endsWith("/v1")) {
            trimmed = "$trimmed/v1"
        }
        return "$trimmed/chat/completions"
    }

    fun normalizeModelsUrl(rawUrl: String): String {
        var trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.endsWith("/chat/completions")) {
            trimmed = trimmed.removeSuffix("/chat/completions")
        }
        if (!trimmed.endsWith("/v1") && !trimmed.endsWith("/models")) {
            trimmed = "$trimmed/v1"
        }
        if (!trimmed.endsWith("/models")) {
            trimmed = "$trimmed/models"
        }
        return trimmed
    }

    private fun parseAssistantContent(jsonString: String): String {
        val json = JSONObject(jsonString)
        val choices = json.optJSONArray("choices")
            ?: throw IOException("Invalid response structure: missing 'choices' array")

        if (choices.length() == 0) {
            throw IOException("Empty choices array returned by LLM")
        }

        val firstChoice = choices.getJSONObject(0)
        val message = firstChoice.optJSONObject("message")
            ?: throw IOException("Missing 'message' object in choice")

        return message.optString("content", "").trim()
    }

    private fun parseErrorMessage(jsonString: String): String {
        return try {
            val json = JSONObject(jsonString)
            json.optJSONObject("error")?.optString("message") ?: jsonString.take(120)
        } catch (_: Exception) {
            jsonString.take(120)
        }
    }
}

/**
 * Configuration data class for any OpenAI-compatible provider.
 */
data class OpenAiConfig(
    val baseUrl: String = PRESET_NVIDIA_NIM.baseUrl,
    val apiKey: String = "",
    val model: String = PRESET_NVIDIA_NIM.defaultModel,
    val timeoutSeconds: Long = 60L,
) {
    companion object {
        val PRESET_NVIDIA_NIM = ProviderPreset(
            name = "NVIDIA NIM",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            defaultModel = "meta/llama-3.3-70b-instruct",
        )
        val PRESET_OPENAI = ProviderPreset(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
        )
        val PRESET_GROQ = ProviderPreset(
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile",
        )
        val PRESET_OPENROUTER = ProviderPreset(
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "meta-llama/llama-3.3-70b-instruct",
        )
        val PRESET_DEEPSEEK = ProviderPreset(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
        )
        val PRESET_OLLAMA_LOCAL = ProviderPreset(
            name = "Local Ollama",
            baseUrl = "http://10.0.2.2:11434/v1", // or LAN IP
            defaultModel = "llama3.2",
        )
    }
}

data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
)

/**
 * Storage for user-configured OpenAI endpoints.
 *
 * Non-secret fields (base URL, model, timeout) stay in SharedPreferences.
 * The API key is AES-GCM encrypted with an Android Keystore key via
 * [KeystoreSecretStore] (PRD section 6: keys never in plaintext, never logged,
 * never in backups). A legacy plaintext `api_key` value is migrated to the
 * encrypted form on first read and then removed.
 */
class OpenAiConfigStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("raijin_cloud_config", Context.MODE_PRIVATE)
    private val secrets = KeystoreSecretStore()

    fun load(): OpenAiConfig {
        return OpenAiConfig(
            baseUrl = prefs.getString("base_url", OpenAiConfig.PRESET_NVIDIA_NIM.baseUrl) ?: OpenAiConfig.PRESET_NVIDIA_NIM.baseUrl,
            apiKey = loadApiKey(),
            model = prefs.getString("model", OpenAiConfig.PRESET_NVIDIA_NIM.defaultModel) ?: OpenAiConfig.PRESET_NVIDIA_NIM.defaultModel,
            timeoutSeconds = prefs.getLong("timeout_seconds", 60L),
        )
    }

    private fun loadApiKey(): String {
        secrets.decrypt(prefs.getString("api_key_enc", null))?.let { return it }
        // One-time migration from the pre-Keystore plaintext value.
        val legacy = prefs.getString("api_key", "") ?: ""
        if (legacy.isNotBlank()) {
            secrets.encrypt(legacy)?.let { enc ->
                prefs.edit().putString("api_key_enc", enc).remove("api_key").apply()
            }
            return legacy
        }
        return ""
    }

    fun save(config: OpenAiConfig) {
        val encryptedKey = secrets.encrypt(config.apiKey.trim())
        prefs.edit()
            .putString("base_url", config.baseUrl.trim())
            .putString("model", config.model.trim())
            .putLong("timeout_seconds", config.timeoutSeconds)
            .apply {
                if (encryptedKey != null) putString("api_key_enc", encryptedKey) else remove("api_key_enc")
                remove("api_key") // never keep a plaintext copy
            }
            .apply()
    }
}
