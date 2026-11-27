package com.raijin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

/**
 * UI-6: type a command when speaking is impractical. The text goes through
 * the same path as a spoken command and stays on the phone.
 */
@Composable
fun TypeToRaijinCard(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** UI-8: text shared into Raijin, shown read-only under the field. */
    sharedPreview: String? = null,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = RaijinSurface, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "TYPE TO RAIJIN",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = RaijinGold,
            )
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = { Text(if (sharedPreview != null) "What should I do with this?" else "What should I do?", fontSize = 12.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RaijinBlue,
                    unfocusedBorderColor = RaijinBorderDim,
                    focusedTextColor = RaijinWhite,
                    unfocusedTextColor = RaijinText,
                    focusedLabelColor = RaijinBlueLight,
                    unfocusedLabelColor = RaijinText,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().let { m ->
                    if (focusRequester != null) m.focusRequester(focusRequester) else m
                },
            )
            if (sharedPreview != null) {
                Text("SHARED TEXT", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RaijinGold)
                Text(sharedPreview, color = RaijinText, fontSize = 12.sp, maxLines = 4)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClose) { Text("Cancel", color = RaijinText, fontSize = 12.sp) }
                Button(
                    onClick = onSend,
                    enabled = text.isNotBlank() || sharedPreview != null,
                    colors = ButtonDefaults.buttonColors(containerColor = RaijinBlueDim, contentColor = RaijinBlueLight),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Send", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}
