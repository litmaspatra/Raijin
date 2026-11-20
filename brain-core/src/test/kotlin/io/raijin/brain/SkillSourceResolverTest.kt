package io.raijin.brain

import io.raijin.brain.skills.SkillSourceResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillSourceResolverTest {

    @Test
    fun `content uri is a local file`() {
        val source = SkillSourceResolver.resolve("content://com.android.providers.documents/document/1234")
        assertIs<SkillSourceResolver.SkillSource.LocalFile>(source)
    }

    @Test
    fun `https url without fragment has no checksum`() {
        val source = SkillSourceResolver.resolve("https://example.com/skills/good-night.json")
        assertIs<SkillSourceResolver.SkillSource.HttpsUrl>(source)
        assertEquals(null, source.expectedSha256)
    }

    @Test
    fun `https url with sha256 fragment carries the checksum`() {
        val hash = "a".repeat(64)
        val source = SkillSourceResolver.resolve("https://example.com/s.json#sha256=$hash")
        assertIs<SkillSourceResolver.SkillSource.HttpsUrl>(source)
        assertEquals("https://example.com/s.json", source.url)
        assertEquals(hash, source.expectedSha256)
    }

    @Test
    fun `bad fragment is rejected`() {
        assertIs<SkillSourceResolver.SkillSource.Invalid>(
            SkillSourceResolver.resolve("https://example.com/s.json#sha256=XYZ"),
        )
        assertIs<SkillSourceResolver.SkillSource.Invalid>(
            SkillSourceResolver.resolve("https://example.com/s.json#other=abc"),
        )
    }

    @Test
    fun `plain http is rejected`() {
        val source = SkillSourceResolver.resolve("http://example.com/s.json")
        assertIs<SkillSourceResolver.SkillSource.Invalid>(source)
        assertTrue(source.reason.contains("http"))
    }

    @Test
    fun `github shorthand resolves to raw skill dot json`() {
        val source = SkillSourceResolver.resolve("litmaspatra/raijin-skills")
        assertIs<SkillSourceResolver.SkillSource.GitHubRepo>(source)
        assertEquals("litmaspatra", source.owner)
        assertEquals("raijin-skills", source.repo)
        assertEquals(
            "https://raw.githubusercontent.com/litmaspatra/raijin-skills/HEAD/skill.json",
            source.rawUrl,
        )
    }

    @Test
    fun `github url resolves the same way`() {
        val source = SkillSourceResolver.resolve("https://github.com/litmaspatra/raijin-skills")
        assertIs<SkillSourceResolver.SkillSource.GitHubRepo>(source)
        assertEquals("litmaspatra", source.owner)
    }

    @Test
    fun `empty and garbage input rejected`() {
        assertIs<SkillSourceResolver.SkillSource.Invalid>(SkillSourceResolver.resolve("  "))
        assertIs<SkillSourceResolver.SkillSource.Invalid>(SkillSourceResolver.resolve("hello world"))
    }

    @Test
    fun `checksum comparison is exact`() {
        val hash = "a".repeat(64)
        assertTrue(SkillSourceResolver.checksumMatches(hash, hash))
        assertTrue(!SkillSourceResolver.checksumMatches(hash, "b".repeat(64)))
        assertTrue(SkillSourceResolver.checksumMatches(null, "anything"))
    }
}
