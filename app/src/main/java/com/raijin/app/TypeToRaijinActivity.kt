package com.raijin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.raijin.app.ui.TypeToRaijinCard
import com.raijin.app.ui.theme.RaijinTheme

/**
 * UI-6: small floating window to type a command. Opened by long-pressing the
 * island or from the "Type" action on Raijin's notification.
 */
class TypeToRaijinActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI-8: shared text only pre-fills the window; nothing runs until Send.
        val shared = if (intent?.action == android.content.Intent.ACTION_SEND) {
            com.raijin.app.ui.ShareCompose.clean(intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT))
        } else null
        setContent {
            RaijinTheme {
                var text by remember { mutableStateOf("") }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(modifier = Modifier.fillMaxSize().imePadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                    TypeToRaijinCard(
                        text = text,
                        onTextChange = { text = it },
                        onSend = {
                            if (com.raijin.app.ui.ShareCompose.canSend(text, shared)) {
                                RaijinService.dispatchUtterance(com.raijin.app.ui.ShareCompose.compose(text, shared))
                                finish()
                            }
                        },
                        onClose = { finish() },
                        focusRequester = focus,
                        sharedPreview = shared,
                    )
                }
            }
        }
    }
}
