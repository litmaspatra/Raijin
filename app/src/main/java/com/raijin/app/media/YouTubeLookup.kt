package com.raijin.app.media

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One search hit: the watch id, its title and length in seconds (0 when unknown). */
data class VideoHit(val videoId: String, val title: String, val lengthSeconds: Int) {
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
    val musicWatchUrl: String get() = "https://music.youtube.com/watch?v=$videoId"
}

/**
 * Background lookup of the first watchable result for a spoken query, like
 * yt-dlp's search but on-device and with no extra library. The only thing sent
 * is the query text (plus the IP address any web request carries) to youtube.com.
 */
object YouTubeLookup {

    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val TIMEOUT_MS = 6_000

    /** Pure: pulls every videoRenderer out of a search response, in order. */
    fun parseHits(responseJson: String): List<VideoHit> {
        val root = try { JSONObject(responseJson) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<VideoHit>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("videoRenderer")?.let { v ->
                        val id = v.optString("videoId")
                        if (id.isNotBlank()) {
                            val runs = v.optJSONObject("title")?.optJSONArray("runs")
                            val title = buildString {
                                if (runs != null) for (i in 0 until runs.length()) append(runs.getJSONObject(i).optString("text"))
                            }
                            val length = parseLength(v.optJSONObject("lengthText")?.optString("simpleText"))
                            out += VideoHit(id, title, length)
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) walk(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        return out
    }

    /** "4:28" -> 268, "1:02:03" -> 3723, null/garbage -> 0. */
    fun parseLength(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val parts = text.split(':').map { it.trim().toIntOrNull() ?: return 0 }
        return parts.fold(0) { acc, p -> acc * 60 + p }
    }

    /**
     * Songs skip hour-long mixes and compilations when a normal-length result
     * exists; videos take the first result as is.
     */
    fun pick(hits: List<VideoHit>, preferSong: Boolean): VideoHit? {
        if (!preferSong) return hits.firstOrNull()
        return hits.firstOrNull { it.lengthSeconds in 1..(12 * 60) } ?: hits.firstOrNull()
    }

    /** Blocking network call; run off the main thread. Null on any failure. */
    fun search(query: String): List<VideoHit>? {
        return try {
            val body = JSONObject()
                .put(
                    "context",
                    JSONObject().put(
                        "client",
                        JSONObject()
                            .put("clientName", "WEB")
                            .put("clientVersion", "2.20241126.01.00")
                            .put("hl", "en")
                            .put("gl", "IN"),
                    ),
                )
                .put("query", query)
                .toString()
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            parseHits(text)
        } catch (_: Exception) {
            null
        }
    }
}
