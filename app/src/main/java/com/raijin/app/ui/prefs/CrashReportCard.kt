package com.raijin.app.ui.prefs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raijin.app.crash.CrashCapture
import com.raijin.app.ui.theme.RaijinBlueLight
import com.raijin.app.ui.theme.RaijinGold
import com.raijin.app.ui.theme.RaijinSurface
import com.raijin.app.ui.theme.RaijinText
import com.raijin.app.ui.theme.RaijinWhite

/** Shows the reason for the last crash, kept on the phone. Appears only after a crash; Copy puts it on the clipboard. */
@Composable
fun CrashReportCard() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashCapture.read(context)) }
    var copied by remember { mutableStateOf(false) }
    val text = report ?: return
    Surface(shape = RoundedCornerShape(12.dp), color = RaijinSurface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "⚠ LAST CRASH",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = RaijinGold,
            )
            Text(
                "Raijin closed unexpectedly. This is kept only on this phone. Copy it if you want to share it.",
                color = RaijinWhite, fontSize = 11.sp,
            )
            Text(text.take(1500), color = RaijinText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Raijin crash report", text))
                    copied = true
                }) { Text(if (copied) "Copied" else "Copy", fontSize = 11.sp, color = RaijinBlueLight) }
                OutlinedButton(onClick = { CrashCapture.clear(context); report = null }) {
                    Text("Dismiss", fontSize = 11.sp, color = RaijinBlueLight)
                }
            }
            Spacer(Modifier.height(0.dp))
        }
    }
}
