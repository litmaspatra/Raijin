package com.raijin.app.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchResult(
    val query: String,
    val summary: String,
    val sourceUrl: String? = null,
    val snippets: List<String> = emptyList(),
)

/**
 * Native, standalone Web Search and Research client.
 *
 * Provides real-time internet search intelligence without requiring Termux, Python daemons,
 * or paid search API keys:
 * 1. Queries DuckDuckGo Instant Answers API for entity and fact definitions.
 * 2. Scrapes instant summaries from DuckDuckGo HTML when abstract is absent.
 * 3. Supports launching deep-dive browser queries via Android Intent.
 */
class WebSearchClient(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    companion object {
        private const val TAG = "WebSearchClient"
    }

    /**
     * Executes a background web research query and returns a concise, tactical intelligence briefing.
     */
    suspend fun research(query: String): SearchResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return@withContext SearchResult(query, "Search query was empty.")
        }

        // 1. Try DuckDuckGo Instant Answers API
        try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "RaijinTacticalHUD/2.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val abstractText = json.optString("AbstractText", "")
                        val heading = json.optString("Heading", "")
                        val sourceUrl = json.optString("AbstractURL", "").takeIf { it.isNotBlank() }

                        if (abstractText.isNotBlank()) {
                            val summary = if (heading.isNotBlank()) "$heading: $abstractText" else abstractText
                            return@withContext SearchResult(
                                query = trimmed,
                                summary = summary,
                                sourceUrl = sourceUrl,
                            )
                        }

                        // Check related topics if main abstract is empty
                        val related = json.optJSONArray("RelatedTopics")
                        if (related != null && related.length() > 0) {
                            val snippets = mutableListOf<String>()
                            for (i in 0 until minOf(related.length(), 3)) {
                                val item = related.optJSONObject(i) ?: continue
                                val itemText = item.optString("Text", "")
                                if (itemText.isNotBlank()) snippets.add(itemText)
                            }
                            if (snippets.isNotEmpty()) {
                                return@withContext SearchResult(
                                    query = trimmed,
                                    summary = snippets.first(),
                                    snippets = snippets,
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Instant answer query failed: ${e.message}")
        }

        // 2. Fallback: Quick query to Wikipedia REST API if it looks like an entity or topic
        try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
            val wikiReq = Request.Builder()
                .url(wikiUrl)
                .header("User-Agent", "RaijinTacticalHUD/2.0 (contact: github.com/litmaspatra/raijin)")
                .build()

            httpClient.newCall(wikiReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val obj = JSONObject(body)
                        val extract = obj.optString("extract", "")
                        val title = obj.optString("title", "")
                        if (extract.isNotBlank()) {
                            return@withContext SearchResult(
                                query = trimmed,
                                summary = "$title: $extract",
                                sourceUrl = obj.optJSONObject("content_urls")
                                    ?.optJSONObject("desktop")?.optString("page"),
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia summary lookup failed: ${e.message}")
        }

        // Fallback default message
        SearchResult(
            query = trimmed,
            summary = "Intel gathered on '$trimmed'. No high-confidence summary extracted.",
            sourceUrl = "https://duckduckgo.com/?q=${URLEncoder.encode(trimmed, "UTF-8")}",
        )
    }

    /**
     * Opens the web search in the user's default browser.
     */
    fun openSearchInBrowser(query: String) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://duckduckgo.com/?q=${URLEncoder.encode(query, "UTF-8")}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
