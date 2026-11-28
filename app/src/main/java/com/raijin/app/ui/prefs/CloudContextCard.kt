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

/** Switch for what the cloud AI is told about the owner. Shows exactly what leaves the phone. */
@Composable
fun CloudContextCard(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = RaijinSurface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "☁ CLOUD AI CONTEXT",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = RaijinGold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Let my cloud AI know what Raijin has saved", color = RaijinWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "When a question goes to your cloud AI, Raijin also sends: the facts you asked it to remember (including your name and how to address you), " +
                            "and the names and short descriptions of its tools and installed skills. Never sent: notifications, contacts, call history, " +
                            "note or vault contents. Turn off to send only your question.",
                        color = RaijinText, fontSize = 10.sp,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = RaijinBlue, uncheckedTrackColor = RaijinTextDim),
                )
            }
        }
    }
}
