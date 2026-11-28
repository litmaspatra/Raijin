package com.raijin.app

import androidx.test.core.app.ApplicationProvider
import com.raijin.app.prefs.UserPreferencesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserPreferencesStoreTest {

    private lateinit var store: UserPreferencesStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("raijin_user_prefs", 0).edit().clear().commit()
        context.getSharedPreferences("raijin_kokoro_voice", 0).edit().clear().commit()
        store = UserPreferencesStore(context)
    }

    @Test
    fun `defaults are safe`() {
        assertEquals(UserPreferencesStore.Language.ENGLISH, store.language)
        assertEquals(UserPreferencesStore.ConfirmationStrictness.BALANCED, store.confirmationStrictness)
        assertEquals(1.0f, store.ttsSpeed)
        assertFalse(store.quietHoursEnabled)
        assertTrue(store.preferredProviders.isEmpty())
        assertTrue(store.isProtectedPath("/storage/emulated/0/DCIM/Camera/photo.jpg"))
        assertFalse(store.isProtectedPath("/storage/emulated/0/Download/tmp.apk"))
    }

    @Test
    fun `quiet hours wrap past midnight`() {
        store.quietHoursEnabled = true
        store.quietHoursStart = 22
        store.quietHoursEnd = 6
        assertTrue(store.isQuietHour(23))
        assertTrue(store.isQuietHour(2))
        assertFalse(store.isQuietHour(6))
        assertFalse(store.isQuietHour(12))
    }

    @Test
    fun `quiet hours disabled means never quiet`() {
        store.quietHoursEnabled = false
        assertFalse(store.isQuietHour(2))
    }

    @Test
    fun `tts speed clamps and mirrors into the kokoro store`() {
        store.ttsSpeed = 5.0f
        assertEquals(2.0f, store.ttsSpeed)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(2.0f, context.getSharedPreferences("raijin_kokoro_voice", 0).getFloat("speed", 0f))
    }

    @Test
    fun `protected path matching covers children but not siblings`() {
        store.protectedPaths = setOf("/storage/emulated/0/DCIM")
        assertTrue(store.isProtectedPath("/storage/emulated/0/DCIM"))
        assertTrue(store.isProtectedPath("/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertFalse(store.isProtectedPath("/storage/emulated/0/DCIMBackup/a.jpg"))
    }

    @Test
    fun `explicit language is null until the owner picks one`() {
        assertNull(store.explicitLanguage)
        assertEquals(UserPreferencesStore.Language.ENGLISH, store.language)
        store.language = UserPreferencesStore.Language.HINDI
        assertEquals(UserPreferencesStore.Language.HINDI, store.explicitLanguage)
    }

    @Test
    fun `preferred providers round-trip in order`() {
        store.preferredProviders = listOf("nvidia", "openai", "gemini")
        assertEquals(listOf("nvidia", "openai", "gemini"), store.preferredProviders)
    }

    @Test
    fun `system voice defaults to auto and pitch is clamped`() {
        assertEquals(null, store.systemVoice)
        assertEquals(1.0f, store.ttsPitch)
        store.systemVoice = "en-in-x-abc-local"
        assertEquals("en-in-x-abc-local", store.systemVoice)
        store.systemVoice = null
        assertEquals(null, store.systemVoice)
        store.ttsPitch = 9f
        assertEquals(2.0f, store.ttsPitch)
    }

    @Test
    fun `online media lookup defaults on and can be switched off`() {
        assertEquals(true, store.onlineMediaLookup)
        store.onlineMediaLookup = false
        assertEquals(false, store.onlineMediaLookup)
    }

    @Test
    fun `contact history ranking defaults on and can be switched off`() {
        assertEquals(true, store.useContactHistory)
        store.useContactHistory = false
        assertEquals(false, store.useContactHistory)
    }

    @Test
    fun `address term persists and rewrites spoken boss`() {
        assertNull(store.addressTerm)
        store.addressTerm = "sir"
        assertEquals("sir", UserPreferencesStore(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(),
        ).addressTerm)
        assertEquals("Done, sir.", io.raijin.brain.AddressForm.rewrite("Done, boss.", store.addressTerm))
        store.addressTerm = null
        assertNull(store.addressTerm)
    }
}
