package com.raijin.app.ui.prefs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raijin.app.audio.KokoroVoices
import com.raijin.app.ui.theme.RaijinBlack
import com.raijin.app.ui.theme.RaijinBlue
import com.raijin.app.ui.theme.RaijinBlueLight
import com.raijin.app.ui.theme.RaijinGold
import com.raijin.app.ui.theme.RaijinSurface
import com.raijin.app.ui.theme.RaijinText
import com.raijin.app.ui.theme.RaijinTextDim
import com.raijin.app.ui.theme.RaijinWhite

/**
 * Switch and picker for the on-device English voice (Kokoro). The voice runs on the phone:
 * no key, no server, nothing sent anywhere. Hindi always uses the system voice.
 */
@Composable
fun KokoroVoiceCard(
    available: Boolean,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    voiceId: String,
    onVoice: (String) -> Unit,
    testing: Boolean,
    onTest: () -> Unit,
    status: String?,
    lastProblem: String?,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = RaijinSurface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "🔊 ON-DEVICE VOICE (ENGLISH)",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = RaijinGold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Speak with Kokoro", color = RaijinWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (available) {
                            "Runs entirely on this phone. Nothing is sent anywhere. Hindi and sentences with unusual words use the system voice."
                        } else {
                            "This build does not include the voice files, so the system voice is used."
                        },
                        color = RaijinText, fontSize = 10.sp,
                    )
                }
                Switch(
                    checked = enabled && available,
                    enabled = available,
                    onCheckedChange = onEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = RaijinBlue, uncheckedTrackColor = RaijinTextDim),
                )
            }
            if (available && enabled) {
                Text("Voice", color = RaijinText, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    KokoroVoices.ALL.forEach { v ->
                        val selected = v.id == voiceId
                        Surface(
                            onClick = { onVoice(v.id) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) RaijinBlue else RaijinBlack,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                v.label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = if (selected) RaijinBlack else RaijinWhite,
                                maxLines = 1,
                            )
                        }
                    }
                }
                OutlinedButton(onClick = onTest, enabled = !testing) {
                    Text(if (testing) "Speaking..." else "Test voice", fontSize = 11.sp, color = RaijinBlueLight)
                }
                status?.let { Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = RaijinText) }
                lastProblem?.let { Text("Last fallback: $it", fontSize = 10.sp, color = RaijinTextDim) }
            }
        }
    }
}
