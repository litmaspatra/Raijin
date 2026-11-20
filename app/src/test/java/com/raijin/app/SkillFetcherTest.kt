package com.raijin.app

import com.raijin.app.skill.SkillFetcher
import com.raijin.app.skill.SkillInstallService
import io.raijin.brain.skills.SkillSourceResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillFetcherTest {

    private fun fetcherReturning(body: String?): SkillFetcher {
        var fetcher = SkillFetcher(fetch = { body })
        return fetcher
    }

    @Test
    fun `https without fragment passes through with source label`() = runTest {
        val source = SkillSourceResolver.resolve("https://example.com/s.json")
        val outcome = fetcherReturning("{}").load(source)
        assertTrue(outcome is SkillFetcher.Outcome.Ok)
        assertEquals("https:https://example.com/s.json", (outcome as SkillFetcher.Outcome.Ok).sourceLabel)
    }

    @Test
    fun `matching fragment checksum passes`() = runTest {
        val body = "{\"id\":\"x\"}"
        val hash = SkillInstallService.sha256Hex(body)
        val source = SkillSourceResolver.resolve("https://example.com/s.json#sha256=$hash")
        assertTrue(fetcherReturning(body).load(source) is SkillFetcher.Outcome.Ok)
    }

    @Test
    fun `mismatched fragment checksum rejected`() = runTest {
        val source = SkillSourceResolver.resolve("https://example.com/s.json#sha256=" + "a".repeat(64))
        val outcome = fetcherReturning("{\"id\":\"x\"}").load(source)
        assertTrue(outcome is SkillFetcher.Outcome.Error)
        assertTrue((outcome as SkillFetcher.Outcome.Error).reason.contains("checksum"))
    }

    @Test
    fun `network failure is an honest error`() = runTest {
        val source = SkillSourceResolver.resolve("https://example.com/s.json")
        assertTrue(fetcherReturning(null).load(source) is SkillFetcher.Outcome.Error)
    }

    @Test
    fun `oversize payload is rejected`() = runTest {
        val source = SkillSourceResolver.resolve("https://example.com/s.json")
        val big = "x".repeat(SkillFetcher.MAX_PAYLOAD_CHARS + 1)
        assertTrue(fetcherReturning(big).load(source) is SkillFetcher.Outcome.Error)
    }

    @Test
    fun `github source fetches the raw url`() = runTest {
        var requested: String? = null
        val fetcher = SkillFetcher(fetch = { url -> requested = url; "{}" })
        val source = SkillSourceResolver.resolve("litmaspatra/raijin-skills")
        val outcome = fetcher.load(source)
        assertTrue(outcome is SkillFetcher.Outcome.Ok)
        assertEquals(
            "https://raw.githubusercontent.com/litmaspatra/raijin-skills/HEAD/skill.json",
            requested,
        )
        assertEquals("github:litmaspatra/raijin-skills", (outcome as SkillFetcher.Outcome.Ok).sourceLabel)
    }

    @Test
    fun `local file source is the pickers job`() = runTest {
        val source = SkillSourceResolver.resolve("content://docs/1")
        assertTrue(fetcherReturning("{}").load(source) is SkillFetcher.Outcome.Error)
    }
}
