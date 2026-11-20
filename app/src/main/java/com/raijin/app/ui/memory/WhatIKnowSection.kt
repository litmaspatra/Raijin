package com.raijin.app.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import com.raijin.app.memory.MemoryFactEntity
import com.raijin.app.memory.MemoryPatternEntity
import com.raijin.app.ui.theme.RaijinAmber
import com.raijin.app.ui.theme.RaijinBlack
import com.raijin.app.ui.theme.RaijinBlue
import com.raijin.app.ui.theme.RaijinBlueLight
import com.raijin.app.ui.theme.RaijinGreen
import com.raijin.app.ui.theme.RaijinRed
import com.raijin.app.ui.theme.RaijinSurface
import com.raijin.app.ui.theme.RaijinText
import com.raijin.app.ui.theme.RaijinTextDim
import com.raijin.app.ui.theme.RaijinWhite

/**
 * ME-4: "What I know about you" - every stored fact, preference, alias and
 * mined pattern, each visible, editable or deletable, plus export and a
 * forget-everything control. Content composables stay separate from Dialog
 * windows so Paparazzi goldens capture the real composed UI.
 */

/** Human label for a memory tier. */
fun tierLabel(tier: String): String = when (tier) {
    "HOT" -> "this session"
    "COLD" -> "kept until you delete it"
    else -> "kept about 30 days"
}

@Composable
fun MemoryFactRow(
    fact: MemoryFactEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RaijinBlack.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(fact.text, color = RaijinWhite, fontSize = 11.sp)
            Text(
                "${fact.kind.lowercase()} - ${tierLabel(fact.tier)}",
                color = RaijinText,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        TextButton(onClick = onEdit) { Text("Edit", color = RaijinBlue, fontSize = 11.sp) }
        TextButton(onClick = onDelete) { Text("✕", color = RaijinRed, fontSize = 11.sp) }
    }
}

@Composable
fun AliasRow(
    spokenForm: String,
    meaning: String,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RaijinBlack.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(spokenForm, color = RaijinWhite, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(meaning, color = RaijinText, fontSize = 10.sp)
        }
        TextButton(onClick = onDelete) { Text("✕", color = RaijinRed, fontSize = 11.sp) }
    }
}

@Composable
fun PatternRow(
    pattern: MemoryPatternEntity,
    description: String,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RaijinBlack.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(description, color = RaijinWhite, fontSize = 11.sp)
            Text(
                "learned from your routine - no words stored",
                color = RaijinText,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        TextButton(onClick = onDelete) { Text("✕", color = RaijinRed, fontSize = 11.sp) }
    }
}

@Composable
fun WhatIKnowFooter(
    onExport: () -> Unit,
    onForgetEverything: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        TextButton(onClick = onExport) {
            Text("Export to file", color = RaijinBlueLight, fontSize = 11.sp)
        }
        Button(
            onClick = onForgetEverything,
            colors = ButtonDefaults.buttonColors(containerColor = RaijinRed),
        ) {
            Text("Forget everything", color = RaijinWhite, fontSize = 11.sp)
        }
    }
}

@Composable
fun FactEditCard(
    fact: MemoryFactEntity,
    onSave: (text: String, tier: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(fact.text) }
    var tier by remember { mutableStateOf(fact.tier) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = RaijinSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "EDIT MEMORY",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RaijinAmber,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("What I should remember") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = RaijinWhite,
                    unfocusedTextColor = RaijinWhite,
                    focusedLabelColor = RaijinText,
                    unfocusedLabelColor = RaijinText,
                    focusedBorderColor = RaijinBlue,
                    unfocusedBorderColor = RaijinText,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Keep for:", color = RaijinText, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("HOT", "WARM", "COLD").forEach { option ->
                    val selected = tier == option
                    TextButton(
                        onClick = { tier = option },
                        modifier = Modifier
                            .background(
                                if (selected) RaijinBlue.copy(alpha = 0.25f) else RaijinBlack.copy(alpha = 0.5f),
                                RoundedCornerShape(6.dp),
                            ),
                    ) {
                        Text(tierLabel(option), color = if (selected) RaijinBlueLight else RaijinText, fontSize = 10.sp)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = RaijinText, fontSize = 11.sp) }
                Button(
                    onClick = { if (text.isNotBlank()) onSave(text.trim(), tier) },
                    colors = ButtonDefaults.buttonColors(containerColor = RaijinBlue),
                ) {
                    Text("Save", color = RaijinWhite, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun FactEditDialog(
    fact: MemoryFactEntity,
    onSave: (text: String, tier: String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        FactEditCard(fact = fact, onSave = onSave, onDismiss = onDismiss)
    }
}

@Composable
fun ForgetEverythingCard(
    factCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = RaijinSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "FORGET EVERYTHING?",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RaijinRed,
            )
            Text(
                "This deletes every stored fact, preference, alias, learned reflex, mined " +
                    "pattern, the interaction log, the skill run history and the action " +
                    "ledger on this phone. Your reminders, installed skills and provider " +
                    "settings are not affected. This cannot be undone.",
                color = RaijinText,
                fontSize = 11.sp,
            )
            Text("$factCount facts will be forgotten.", color = RaijinWhite, fontSize = 11.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Keep my memory", color = RaijinGreen, fontSize = 11.sp) }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = RaijinRed),
                ) {
                    Text("Forget everything", color = RaijinWhite, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun ForgetEverythingDialog(
    factCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        ForgetEverythingCard(factCount = factCount, onConfirm = onConfirm, onDismiss = onDismiss)
    }
}
