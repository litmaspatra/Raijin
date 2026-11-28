package com.raijin.app.ui.prefs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raijin.app.ui.theme.RaijinBlue
import com.raijin.app.ui.theme.RaijinBlueDim
import com.raijin.app.ui.theme.RaijinBlueLight
import com.raijin.app.ui.theme.RaijinBorderDim
import com.raijin.app.ui.theme.RaijinGold
import com.raijin.app.ui.theme.RaijinSurface
import com.raijin.app.ui.theme.RaijinText
import com.raijin.app.ui.theme.RaijinWhite

/** Settings card for the Obsidian vault folder (Android folder picker). Notes are only ever written inside it. */
@Composable
fun ObsidianVaultCard(
    folderLabel: String?,
    onChoose: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = RaijinSurface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "📓 OBSIDIAN VAULT",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = RaijinGold,
            )
            Text(
                "Raijin reads this folder to learn your note layout and writes notes only inside it, " +
                    "only after you approve each draft.",
                color = RaijinText, fontSize = 10.sp,
            )
            Text(
                if (folderLabel != null) "Selected folder: $folderLabel" else "No folder selected. Nothing is written until you choose one.",
                color = RaijinText, fontSize = 10.sp,
            )
            Text(
                "Your choice is kept only on this phone and never sent anywhere.",
                color = RaijinText, fontSize = 10.sp,
            )
            Button(
                onClick = onChoose,
                colors = ButtonDefaults.buttonColors(containerColor = RaijinBlueDim, contentColor = RaijinBlueLight),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (folderLabel == null) "Choose vault folder" else "Change vault folder", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            if (folderLabel != null) {
                TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                    Text("Forget this folder", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = RaijinText)
                }
            }
        }
    }
}
