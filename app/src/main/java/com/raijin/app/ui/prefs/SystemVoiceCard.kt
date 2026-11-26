package com.raijin.app.ui.prefs

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raijin.app.audio.VoiceChooser
import com.raijin.app.audio.VoiceOption
import com.raijin.app.prefs.UserPreferencesStore
import com.raijin.app.ui.theme.RaijinBlack
import com.raijin.app.ui.theme.RaijinBlue
import com.raijin.app.ui.theme.RaijinBlueLight
import com.raijin.app.ui.theme.RaijinGold
import com.raijin.app.ui.theme.RaijinSurface
import com.raijin.app.ui.theme.RaijinText
import com.raijin.app.ui.theme.RaijinTextDim
import com.raijin.app.ui.theme.RaijinWhite
import java.util.Locale

/**
 * Phone-voice picker: lists the English voices installed on this phone, lets the
 * owner pick one (or "Auto", the best installed), set pitch, and hear a sample.
 * Speed lives in the Preferences card and is applied to the same voice.
 */
@Composable
fun SystemVoiceCard(context: Context, store: UserPreferencesStore) {
    var voices by remember { mutableStateOf(emptyList<VoiceOption>()) }
    var chosen by remember { mutableStateOf(store.systemVoice) }
    var pitch by remember { mutableStateOf(store.ttsPitch) }
    var engineState by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            val e = engine ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                voices = e.voices.orEmpty().map {
                    VoiceOption(
                        name = it.name,
                        languageTag = it.locale.toLanguageTag(),
                        quality = it.quality,
                        needsNetwork = it.isNetworkConnectionRequired,
                        installed = !it.features.orEmpty()
                            .contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
                    )
                }.filter { it.language == "en" && it.installed }
                engineState = e
            }
        }
        onDispose { engine?.shutdown() }
    }

    val best = VoiceChooser.best(voices, "en")

    Surface(shape = RoundedCornerShape(12.dp), color = RaijinSurface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "🔊 PHONE VOICE",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = RaijinGold,
            )
            Text(
                if (voices.isEmpty()) "No English voices found on this phone."
                else "Auto uses the best installed voice: ${best?.label() ?: "none"}",
                color = RaijinText, fontSize = 10.sp,
            )

            VoiceChip("Auto (best installed)", chosen == null) {
                chosen = null; store.systemVoice = null
            }
            voices.sortedWith(compareByDescending<VoiceOption> { it.quality }.thenBy { it.name })
                .take(12).forEach { v ->
                    VoiceChip(v.label(), chosen == v.name) {
                        chosen = v.name; store.systemVoice = v.name
                    }
                }

            Text("Pitch: ${"%.1f".format(pitch)}x", color = RaijinWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = pitch,
                onValueChange = { pitch = it; store.ttsPitch = it },
                valueRange = 0.5f..2.0f,
                colors = SliderDefaults.colors(
                    thumbColor = RaijinBlue, activeTrackColor = RaijinBlue, inactiveTrackColor = RaijinTextDim,
                ),
            )

            Row {
                TextButton(onClick = {
                    val e = engineState ?: return@TextButton
                    val pick = VoiceChooser.resolve(voices, "en", chosen)
                    e.language = Locale.ENGLISH
                    e.voices.orEmpty().firstOrNull { it.name == pick?.name }?.let { e.voice = it }
                    e.setSpeechRate(store.ttsSpeed)
                    e.setPitch(store.ttsPitch)
                    e.speak("Hello boss. This is how I sound.", TextToSpeech.QUEUE_FLUSH, null, "voice-preview")
                }) { Text("PLAY SAMPLE", color = RaijinGold, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun VoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) RaijinBlue.copy(alpha = 0.25f) else RaijinBlack.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp),
            ),
    ) {
        Text(label, color = if (selected) RaijinBlueLight else RaijinText, fontSize = 10.sp)
    }
}
