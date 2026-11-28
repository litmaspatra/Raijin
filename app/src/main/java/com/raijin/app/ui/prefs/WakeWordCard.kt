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

/** Switches for the "hey <name>" wake word. Off by default; it keeps the microphone open, which uses battery. */
@Composable
fun WakeWordCard(
    available: Boolean,
    name: String,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onlyScreenOnOrCharging: Boolean,
    onOnlyScreenOnOrCharging: (Boolean) -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = RaijinSurface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "🎙 WAKE WORD",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = RaijinGold,
            )
            ToggleRow(
                title = "Wake me with \"hey ${name.lowercase()}\"",
                body = if (available) {
                    "The phone listens for the phrase using a small model on the device. Audio is checked in memory and thrown away; nothing is stored or sent. Listening keeps the microphone open and uses battery. Not yet measured on a real phone; unusual names may need a few tries."
                } else {
                    "This build does not include the wake word model."
                },
                checked = enabled && available,
                enabled = available,
                onChange = onEnabled,
            )
            if (available && enabled) {
                ToggleRow(
                    title = "Only while the screen is on or charging",
                    body = "Saves battery. Turn off to listen all the time.",
                    checked = onlyScreenOnOrCharging,
                    enabled = true,
                    onChange = onOnlyScreenOnOrCharging,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = RaijinWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(body, color = RaijinText, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = RaijinBlue, uncheckedTrackColor = RaijinTextDim),
        )
    }
}
