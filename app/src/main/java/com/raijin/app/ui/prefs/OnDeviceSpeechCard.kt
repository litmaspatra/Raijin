package com.raijin.app.ui.prefs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.raijin.app.ui.theme.RaijinBlue
import com.raijin.app.ui.theme.RaijinGold
import com.raijin.app.ui.theme.RaijinSurface
import com.raijin.app.ui.theme.RaijinText
import com.raijin.app.ui.theme.RaijinTextDim
import com.raijin.app.ui.theme.RaijinWhite

/** Switch for on-device Whisper speech recognition (audio stays on the phone). */
@Composable
fun OnDeviceSpeechCard(
    enabled: Boolean,
    available: Boolean,
    onEnabled: (Boolean) -> Unit,
    lastProblem: String? = null,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = RaijinSurface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "🎙 ON-DEVICE SPEECH",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = RaijinGold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Understand my voice with Whisper on this phone", color = RaijinWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Uses a speech model bundled in the app. Your voice is processed on the phone only and is never recorded to a file or sent anywhere. " +
                            "Slower than the system recognizer on the first command; it replies after you stop talking.",
                        color = RaijinText, fontSize = 10.sp,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = RaijinBlue, uncheckedTrackColor = RaijinTextDim),
                )
            }
            if (enabled && lastProblem != null) {
                Text("Last on-device speech note: $lastProblem.", color = RaijinText, fontSize = 10.sp)
            }
            if (enabled && !available) {
                Text("This build has no bundled speech model, so the system recognizer is used.", color = RaijinText, fontSize = 10.sp)
            }
        }
    }
}
