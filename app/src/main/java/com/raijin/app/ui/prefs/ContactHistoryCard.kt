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
import androidx.compose.material3.TextButton
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

/** Visible switch for call-history contact ranking. Everything stays on the phone. */
@Composable
fun ContactHistoryCard(
    enabled: Boolean,
    permissionGranted: Boolean,
    onEnabled: (Boolean) -> Unit,
    onGrantPermission: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = RaijinSurface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "📞 CONTACT HISTORY",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = RaijinGold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use my call history to pick the right contact", color = RaijinWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "When two contacts sound alike, Raijin prefers the one you call often, recently, or at this time of day. " +
                            "It reads your phone's call log on the phone only. Nothing is stored by Raijin and nothing leaves the phone.",
                        color = RaijinText, fontSize = 10.sp,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = RaijinBlue, uncheckedTrackColor = RaijinTextDim),
                )
            }
            if (enabled && !permissionGranted) {
                Text("Call log access is not allowed yet, so this has no effect.", color = RaijinText, fontSize = 10.sp)
                TextButton(onClick = onGrantPermission) { Text("ALLOW CALL LOG ACCESS", color = RaijinGold, fontSize = 11.sp) }
            }
        }
    }
}
