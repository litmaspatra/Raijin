package com.raijin.app.ui.prefs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raijin.app.prefs.UserPreferencesStore
import com.raijin.app.ui.theme.RaijinBlack
import com.raijin.app.ui.theme.RaijinBlue
import com.raijin.app.ui.theme.RaijinBlueLight
import com.raijin.app.ui.theme.RaijinGold
import com.raijin.app.ui.theme.RaijinRed
import com.raijin.app.ui.theme.RaijinSurface
import com.raijin.app.ui.theme.RaijinText
import com.raijin.app.ui.theme.RaijinTextDim
import com.raijin.app.ui.theme.RaijinWhite

/**
 * ME-5: owner preferences card. Every control maps to UserPreferencesStore;
 * labels say what the preference actually changes today (honest wiring
 * status lives in STATUS.md).
 */

@Composable
private fun PrefChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .background(
                if (selected) RaijinBlue.copy(alpha = 0.25f) else RaijinBlack.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp),
            ),
    ) {
        Text(label, color = if (selected) RaijinBlueLight else RaijinText, fontSize = 10.sp)
    }
}

@Composable
private fun PrefLabel(text: String) {
    Text(text, color = RaijinWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun PrefHint(text: String) {
    Text(text, color = RaijinText, fontSize = 10.sp)
}

@Composable
fun PreferencesCard(
    language: UserPreferencesStore.Language,
    onLanguage: (UserPreferencesStore.Language) -> Unit,
    ttsSpeed: Float,
    onTtsSpeed: (Float) -> Unit,
    strictness: UserPreferencesStore.ConfirmationStrictness,
    onStrictness: (UserPreferencesStore.ConfirmationStrictness) -> Unit,
    quietEnabled: Boolean,
    onQuietEnabled: (Boolean) -> Unit,
    quietStart: Int,
    quietEnd: Int,
    onQuietStart: (Int) -> Unit,
    onQuietEnd: (Int) -> Unit,
    protectedPaths: Set<String>,
    onRemoveProtectedPath: (String) -> Unit,
    onAddProtectedPath: (String) -> Unit,
    ttsVoice: String,
    onTtsVoice: (String) -> Unit,
    providerIds: List<String>,
    preferredProviders: List<String>,
    onMoveProvider: (String, Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = RaijinSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "⚙ PREFERENCES",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RaijinGold,
            )

            PrefLabel("Language")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrefChip("English", language == UserPreferencesStore.Language.ENGLISH) {
                    onLanguage(UserPreferencesStore.Language.ENGLISH)
                }
                PrefChip("हिंदी", language == UserPreferencesStore.Language.HINDI) {
                    onLanguage(UserPreferencesStore.Language.HINDI)
                }
            }

            PrefLabel("Voice speed: ${"%.1f".format(ttsSpeed)}x")
            Slider(
                value = ttsSpeed,
                onValueChange = onTtsSpeed,
                valueRange = 0.5f..2.0f,
                colors = SliderDefaults.colors(
                    thumbColor = RaijinBlue,
                    activeTrackColor = RaijinBlue,
                    inactiveTrackColor = RaijinTextDim,
                ),
            )

            PrefLabel("Voice name")
            PrefHint("Kokoro voice id, e.g. bf_emma, af_heart, am_adam.")
            run {
                var voiceDraft by remember(ttsVoice) { mutableStateOf(ttsVoice) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = voiceDraft,
                        onValueChange = { voiceDraft = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = RaijinWhite, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RaijinBlue,
                            unfocusedBorderColor = RaijinTextDim,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val v = voiceDraft.trim()
                        if (v.isNotEmpty()) onTtsVoice(v)
                    }) { Text("SET", color = RaijinGold, fontSize = 11.sp) }
                }
            }

            PrefLabel("Ask before acting")
            PrefHint(
                when (strictness) {
                    UserPreferencesStore.ConfirmationStrictness.STRICT ->
                        "Ask before every action that changes something."
                    UserPreferencesStore.ConfirmationStrictness.BALANCED ->
                        "Ask for sensitive or irreversible actions."
                    UserPreferencesStore.ConfirmationStrictness.RELAXED ->
                        "Only ask for irreversible actions."
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UserPreferencesStore.ConfirmationStrictness.entries.forEach { level ->
                    PrefChip(level.name.lowercase().replaceFirstChar(Char::uppercase), strictness == level) {
                        onStrictness(level)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    PrefLabel("Quiet hours")
                    PrefHint(
                        if (quietEnabled) "No proactive speaking from ${quietStart}:00 to ${quietEnd}:00"
                        else "Proactive speaking allowed any time"
                    )
                }
                Switch(
                    checked = quietEnabled,
                    onCheckedChange = onQuietEnabled,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = RaijinBlue,
                        uncheckedTrackColor = RaijinTextDim,
                    ),
                )
            }
            if (quietEnabled) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrefLabel("From")
                    PrefChip("-", false) { onQuietStart((quietStart + 23) % 24) }
                    Text("${quietStart}:00", color = RaijinWhite, fontSize = 11.sp)
                    PrefChip("+", false) { onQuietStart((quietStart + 1) % 24) }
                    PrefLabel("to")
                    PrefChip("-", false) { onQuietEnd((quietEnd + 23) % 24) }
                    Text("${quietEnd}:00", color = RaijinWhite, fontSize = 11.sp)
                    PrefChip("+", false) { onQuietEnd((quietEnd + 1) % 24) }
                }
            }

            PrefLabel("Protected paths")
            PrefHint("Cleanup and delete actions never touch these.")
            protectedPaths.forEach { path ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RaijinBlack.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        path,
                        color = RaijinWhite,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRemoveProtectedPath(path) }) {
                        Text("✕", color = RaijinRed, fontSize = 11.sp)
                    }
                }
            }
            run {
                var pathDraft by remember { mutableStateOf("") }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = pathDraft,
                        onValueChange = { pathDraft = it },
                        singleLine = true,
                        placeholder = { Text("Add path…", color = RaijinTextDim, fontSize = 10.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = RaijinWhite, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RaijinBlue,
                            unfocusedBorderColor = RaijinTextDim,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val v = pathDraft.trim()
                        if (v.isNotEmpty()) {
                            onAddProtectedPath(v)
                            pathDraft = ""
                        }
                    }) { Text("ADD", color = RaijinGold, fontSize = 11.sp) }
                }
            }

            if (providerIds.isNotEmpty()) {
                PrefLabel("Provider order")
                PrefHint("First choice on top; arrows reorder. Providers not listed here keep their saved order after these.")
                providerIds.forEachIndexed { index, id ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(RaijinBlack.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}. $id",
                            color = RaijinWhite,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onMoveProvider(id, -1) }, enabled = index > 0) {
                            Text("▲", color = if (index > 0) RaijinBlue else RaijinTextDim, fontSize = 11.sp)
                        }
                        TextButton(onClick = { onMoveProvider(id, 1) }, enabled = index < providerIds.lastIndex) {
                            Text("▼", color = if (index < providerIds.lastIndex) RaijinBlue else RaijinTextDim, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
