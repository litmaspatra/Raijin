package io.raijin.brain.skills

/**
 * SK-2: classifies where a skill comes from and what to fetch.
 * Pure logic: the app side performs the actual pick/download.
 * PRD 7.9 SK-2: install from file, HTTPS URL with checksum, or a GitHub repo.
 */
object SkillSourceResolver {

    sealed interface SkillSource {
        /** Owner picked a local file (SAF uri string); no fetch needed. */
        data class LocalFile(val uri: String) : SkillSource

        /** HTTPS payload; [expectedSha256] comes from a `#sha256=<hex>` fragment when present. */
        data class HttpsUrl(val url: String, val expectedSha256: String?) : SkillSource

        /** GitHub repo; [rawUrl] points at the skill.json inside it. */
        data class GitHubRepo(
            val owner: String,
            val repo: String,
            val path: String,
            val rawUrl: String,
            /** Branch, tag or commit the owner asked for (`owner/repo@ref`); null means the default branch. */
            val ref: String? = null,
        ) : SkillSource {
            /** Raw file URL at an exact commit, so what the owner reviews is what gets installed. */
            fun rawUrlAt(commitSha: String) = "https://raw.githubusercontent.com/$owner/$repo/$commitSha/$path"
        }

        data class Invalid(val reason: String) : SkillSource
    }

    private val SHA = Regex("[0-9a-f]{64}")
    private val SHORTHAND_REF = Regex("""^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)@([A-Za-z0-9_./-]+)$""")
    private val GITHUB_RE = Regex("""^(?:https://)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?$""")

    fun resolve(input: String): SkillSource {
        val text = input.trim()
        if (text.isEmpty()) return SkillSource.Invalid("empty input")

        if (text.startsWith("content://") || text.startsWith("file://")) {
            return SkillSource.LocalFile(text)
        }

        // GitHub shorthand "owner/repo" or a github.com URL -> raw skill.json.
        SHORTHAND_REF.matchEntire(text)?.let { m ->
            val ref = m.groupValues[3]
            if (ref.contains("..") || ref.startsWith("/") || ref.endsWith("/")) {
                return SkillSource.Invalid("not a valid branch, tag or commit: $ref")
            }
            return githubSource(m.groupValues[1], m.groupValues[2], ref)
        }
        val gh = GITHUB_RE.matchEntire(text)
            ?: if (text.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) {
                val (owner, repo) = text.split("/", limit = 2)
                return githubSource(owner, repo)
            } else {
                null
            }
        if (gh != null) {
            return githubSource(gh.groupValues[1], gh.groupValues[2])
        }

        if (text.startsWith("https://")) {
            val url = text.substringBefore('#')
            if ('#' !in text) return SkillSource.HttpsUrl(url, null)
            val fragment = text.substringAfter('#')
            val expected = fragment.removePrefix("sha256=").takeIf { it != fragment }
                ?: return SkillSource.Invalid("URL fragment must be #sha256=<64 hex>")
            if (!expected.matches(SHA)) {
                return SkillSource.Invalid("checksum in fragment is not 64 lowercase hex")
            }
            return SkillSource.HttpsUrl(url, expected)
        }

        if (text.startsWith("http://")) {
            return SkillSource.Invalid("plain http is not allowed for skill installs")
        }

        return SkillSource.Invalid("not a file, https URL, or GitHub repo: $text")
    }

    private fun githubSource(owner: String, repo: String, ref: String? = null): SkillSource {
        val path = "skill.json"
        return SkillSource.GitHubRepo(
            owner = owner,
            repo = repo,
            path = path,
            rawUrl = "https://raw.githubusercontent.com/$owner/$repo/${ref ?: "HEAD"}/$path",
            ref = ref,
        )
    }

    /**
     * Post-download check for HTTPS installs that carried a checksum fragment.
     * Null expected = nothing to check (owner still approves via the normal flow).
     */
    fun checksumMatches(expectedSha256: String?, computedSha256: String): Boolean =
        expectedSha256 == null || expectedSha256 == computedSha256
}
