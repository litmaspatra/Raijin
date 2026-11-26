package com.raijin.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceChooserTest {
    private fun v(name: String, tag: String, q: Int, net: Boolean = false, inst: Boolean = true) =
        VoiceOption(name, tag, q, net, inst)

    private val all = listOf(
        v("us-normal", "en-US", 300),
        v("in-normal", "en-IN", 300),
        v("us-high", "en-US", 400),
        v("gb-vhigh-online", "en-GB", 500, net = true),
        v("hi-normal", "hi-IN", 300),
        v("us-missing", "en-US", 500, inst = false),
    )

    @Test fun bestPrefersOfflineHighestQuality() =
        assertEquals("us-high", VoiceChooser.best(all, "en")?.name)

    @Test fun regionBreaksQualityTie() =
        assertEquals("in-normal", VoiceChooser.best(all.filter { it.quality == 300 }, "en")?.name)

    @Test fun usesOnlineOnlyWhenNoOfflineVoice() =
        assertEquals("gb-vhigh-online", VoiceChooser.best(listOf(all[3], all[0].copy(installed = false)), "en")?.name)

    @Test fun noVoiceForLanguageIsNull() = assertNull(VoiceChooser.best(all, "fr"))

    @Test fun hindiPicksHindiVoice() = assertEquals("hi-normal", VoiceChooser.best(all, "hi")?.name)

    @Test fun resolveHonorsChoiceThenFallsBackToBest() {
        assertEquals("us-normal", VoiceChooser.resolve(all, "en", "us-normal")?.name)
        assertEquals("us-high", VoiceChooser.resolve(all, "en", "gone-voice")?.name)
        assertEquals("us-high", VoiceChooser.resolve(all, "en", "us-missing")?.name)
        assertEquals("us-high", VoiceChooser.resolve(all, "en", "hi-normal")?.name)
    }

    @Test fun labelNamesLanguageQualityAndId() =
        assertEquals("English (United States) (high) - us-high", all[2].label())
}
