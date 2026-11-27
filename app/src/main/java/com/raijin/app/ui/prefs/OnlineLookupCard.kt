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

/** Visible switch for the background song/video lookup, with plain wording about what leaves the phone. */
@Composable
fun OnlineLookupCard(enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = RaijinSurface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "🌐 SONG AND VIDEO LOOKUP",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = RaijinGold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Look up songs and videos online", color = RaijinWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (enabled)
                            "On. When you say play a song or video that is not on this phone, Raijin searches YouTube in the background and opens the first result. Only the words you said (and your phone's IP address, like any web request) are sent to youtube.com. Nothing else."
                        else
                            "Off. Raijin only opens YouTube or YouTube Music with a search and nothing is sent by Raijin.",
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
