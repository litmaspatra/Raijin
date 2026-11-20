package com.raijin.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.raijin.app.prefs.UserPreferencesStore
import com.raijin.app.ui.prefs.PreferencesCard
import org.junit.Rule
import org.junit.Test

/** ME-5 goldens: preferences card, quiet hours off and on. */
class PreferencesPaparazziTest {

    // Frame tall enough for the full card incl. Protected paths + Provider order
    // (1400 clipped the lower sections; 2400 leaves headroom, image bottom is empty background).
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 2400))

    @Test
    fun preferencesCardQuietHoursOff() {
        paparazzi.snapshot {
            PreferencesCard(
                language = UserPreferencesStore.Language.ENGLISH,
                onLanguage = {},
                ttsSpeed = 1.2f,
                onTtsSpeed = {},
                strictness = UserPreferencesStore.ConfirmationStrictness.BALANCED,
                onStrictness = {},
                quietEnabled = false,
                onQuietEnabled = {},
                quietStart = 22,
                quietEnd = 6,
                onQuietStart = {},
                onQuietEnd = {},
                protectedPaths = setOf("/storage/emulated/0/DCIM", "/storage/emulated/0/Documents"),
                onRemoveProtectedPath = {},
                onAddProtectedPath = {},
                ttsVoice = "bf_emma",
                onTtsVoice = {},
                providerIds = listOf("openai-main", "openrouter-backup"),
                preferredProviders = listOf("openai-main", "openrouter-backup"),
                onMoveProvider = { _, _ -> },
            )
        }
    }

    @Test
    fun preferencesCardQuietHoursOnHindi() {
        paparazzi.snapshot {
            PreferencesCard(
                language = UserPreferencesStore.Language.HINDI,
                onLanguage = {},
                ttsSpeed = 0.9f,
                onTtsSpeed = {},
                strictness = UserPreferencesStore.ConfirmationStrictness.STRICT,
                onStrictness = {},
                quietEnabled = true,
                onQuietEnabled = {},
                quietStart = 23,
                quietEnd = 7,
                onQuietStart = {},
                onQuietEnd = {},
                protectedPaths = setOf("/storage/emulated/0/DCIM"),
                onRemoveProtectedPath = {},
                onAddProtectedPath = {},
                ttsVoice = "af_heart",
                onTtsVoice = {},
                providerIds = emptyList(),
                preferredProviders = emptyList(),
                onMoveProvider = { _, _ -> },
            )
        }
    }
}
