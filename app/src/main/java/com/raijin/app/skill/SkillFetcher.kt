package com.raijin.app.skill

import io.raijin.brain.skills.SkillSourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * SK-2: fetches a skill payload from a resolved source. The network
 * transport is a seam ([fetch]) so the orchestration - size cap, fragment
 * checksum, error honesty - is unit-tested without network.
 */
class SkillFetcher(
    private val fetch: suspend (url: String) -> String? = ::httpGet,
) {
    sealed interface Outcome {
        /** Downloaded payload; [sourceLabel] goes into the install record. */
        data class Ok(val payloadJson: String, val sourceLabel: String) : Outcome
        data class Error(val reason: String) : Outcome
    }

    suspend fun load(source: SkillSourceResolver.SkillSource): Outcome {
        return when (source) {
        is SkillSourceResolver.SkillSource.LocalFile ->
            Outcome.Error("local files are read through the document picker, not the fetcher")

        is SkillSourceResolver.SkillSource.Invalid ->
            Outcome.Error(source.reason)

        is SkillSourceResolver.SkillSource.HttpsUrl -> {
            val body = fetch(source.url)
                ?: return Outcome.Error("download failed: ${source.url}")
            if (body.length > MAX_PAYLOAD_CHARS) {
                return Outcome.Error("skill file too large (>${MAX_PAYLOAD_CHARS} chars)")
            }
            if (!SkillSourceResolver.checksumMatches(
                    source.expectedSha256,
                    SkillInstallService.sha256Hex(body),
                )
            ) {
                return Outcome.Error("downloaded file does not match the #sha256 checksum in the link")
            }
            Outcome.Ok(body, "https:${source.url}")
        }

        is SkillSourceResolver.SkillSource.GitHubRepo -> {
            val body = fetch(source.rawUrl)
                ?: return Outcome.Error("no skill.json at ${source.owner}/${source.repo}")
            if (body.length > MAX_PAYLOAD_CHARS) {
                return Outcome.Error("skill file too large (>${MAX_PAYLOAD_CHARS} chars)")
            }
            Outcome.Ok(body, "github:${source.owner}/${source.repo}")
        }
        }
    }

    companion object {
        /** Skills are small declarative manifests; anything bigger is wrong. */
        const val MAX_PAYLOAD_CHARS = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

        /** Real HTTPS GET: timeouts, size-capped stream, no redirects off HTTPS. */
        suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.instanceFollowRedirects = false
                    if (connection.responseCode !in 200..299) return@runCatching null
                    connection.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(MAX_PAYLOAD_CHARS + 1)
                        var total = 0
                        while (total <= MAX_PAYLOAD_CHARS) {
                            val read = reader.read(buffer, total, buffer.size - total)
                            if (read < 0) break
                            total += read
                        }
                        String(buffer, 0, minOf(total, MAX_PAYLOAD_CHARS + 1))
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
    }
}
