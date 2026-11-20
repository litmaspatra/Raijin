package io.raijin.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppResolverTest {

    private val deviceApps = listOf(
        AppResolver.AppEntry("com.whatsapp", "WhatsApp"),
        AppResolver.AppEntry("com.whatsapp.w4b", "WhatsApp Business"),
        AppResolver.AppEntry("com.google.android.youtube", "YouTube"),
        AppResolver.AppEntry("com.instagram.android", "Instagram"),
        AppResolver.AppEntry("com.google.android.apps.maps", "Google Maps"),
        AppResolver.AppEntry("com.google.android.gm", "Gmail"),
        AppResolver.AppEntry("com.android.vending", "Play Store"),
        AppResolver.AppEntry("com.spotify.music", "Spotify"),
        AppResolver.AppEntry("net.oneplus.calculator", "Calculator"),
        AppResolver.AppEntry("com.google.android.apps.walletnfcrel", "Google Pay"),
        AppResolver.AppEntry("com.android.settings", "Settings"),
        AppResolver.AppEntry("com.zomato", "Zomato"),
    )

    private fun resolve(q: String) = AppResolver.resolve(q, deviceApps)?.packageName

    private fun resolveLearned(q: String, learned: Map<String, String>) =
        AppResolver.resolve(q, deviceApps, learned)?.packageName

    @Test fun `learned alias resolves the intended app`() {
        // Owner taught: "zap" means Instagram.
        assertEquals("com.instagram.android", resolveLearned("zap", mapOf("zap" to "instagram")))
    }

    @Test fun `learned alias beats built-in alias`() {
        // Built-in maps insta -> Instagram; an explicit owner correction
        // remapping it to Snapchat must win.
        val snap = deviceApps + AppResolver.AppEntry("com.snapchat.android", "Snapchat")
        val ranked = AppResolver.rank("insta", snap, mapOf("insta" to "snapchat"))
        assertEquals("com.snapchat.android", ranked.first().packageName)
        assertTrue(ranked.first().score >= AppResolver.RESOLVE_THRESHOLD)
    }

    @Test fun `unknown learned alias target does not rescue a weak match`() {
        // "zap" -> "nonexistent app" must not make some unrelated app win.
        assertNull(resolveLearned("zap", mapOf("zap" to "nonexistent app")))
    }

    @Test fun `without learned aliases behavior is unchanged`() {
        assertEquals("com.instagram.android", resolve("insta"))
        assertNull(resolve("zap"))
    }

    @Test fun `exact app name resolves`() {
        assertEquals("com.whatsapp", resolve("whatsapp"))
    }

    @Test fun `case and punctuation variants resolve`() {
        assertEquals("com.whatsapp", resolve("WhatsApp!"))
        assertEquals("com.google.android.youtube", resolve("YouTube"))
    }

    @Test fun `misspelled spoken forms fuzzy match`() {
        assertEquals("com.whatsapp", resolve("wattsapp"))
        assertEquals("com.whatsapp", resolve("whats app"))
        assertEquals("com.whatsapp", resolve("watsapp"))
    }

    @Test fun `built-in aliases resolve (PRD AP-2)`() {
        assertEquals("com.instagram.android", resolve("insta"))
        assertEquals("com.google.android.youtube", resolve("yt"))
        assertEquals("com.google.android.apps.walletnfcrel", resolve("gpay"))
        assertEquals("com.whatsapp.w4b", resolve("whatsapp business"))
    }

    @Test fun `phonetic misspellings resolve`() {
        assertEquals("com.zomato", resolve("zomatoo"))
        assertEquals("com.instagram.android", resolve("instagrm"))
    }

    @Test fun `generic word does not hijack an app`() {
        assertNull(resolve("pay"))
        assertNull(resolve("music"))
        assertNull(resolve("flibbertigibbet"))
    }

    @Test fun `empty and blank queries resolve nothing`() {
        assertNull(resolve(""))
        assertNull(resolve("   "))
    }

    @Test fun `rank is sorted best first`() {
        val ranking = AppResolver.rank("whatsapp", deviceApps)
        assertEquals("com.whatsapp", ranking.first().packageName)
        assertTrue(ranking.zipWithNext().all { (a, b) -> a.score >= b.score })
    }
}
