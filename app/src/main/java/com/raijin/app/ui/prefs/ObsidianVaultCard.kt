package com.raijin.app.ui.prefs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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

/** Settings field for the Obsidian vault folder. Notes are only ever written inside it. */
@Composable
fun ObsidianVaultCard(
    path: String,
    status: String,
    onPathChange: (String) -> Unit,
    onSave: () -> Unit,
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
            OutlinedTextField(
                value = path,
                onValueChange = onPathChange,
                label = { Text("Vault folder", fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RaijinBlue,
                    unfocusedBorderColor = RaijinBorderDim,
                    focusedTextColor = RaijinWhite,
                    unfocusedTextColor = RaijinText,
                    focusedLabelColor = RaijinBlueLight,
                    unfocusedLabelColor = RaijinText,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (status.isNotBlank()) Text(status, color = RaijinText, fontSize = 10.sp)
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = RaijinBlueDim, contentColor = RaijinBlueLight),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save vault folder", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
    }
}
