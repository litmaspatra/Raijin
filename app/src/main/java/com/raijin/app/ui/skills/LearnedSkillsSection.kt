package com.raijin.app.ui.skills

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
import com.raijin.app.skill.SkillEntity
import com.raijin.app.ui.theme.RaijinBlack
import com.raijin.app.ui.theme.RaijinBlue
import com.raijin.app.ui.theme.RaijinBorderDim
import com.raijin.app.ui.theme.RaijinGold
import com.raijin.app.ui.theme.RaijinRed
import com.raijin.app.ui.theme.RaijinSurface
import com.raijin.app.ui.theme.RaijinText
import com.raijin.app.ui.theme.RaijinTextDim
import com.raijin.app.ui.theme.RaijinWhite

/**
 * SK-4: one learned reflex row - visible, with edit and delete. Learned
 * reflexes only re-parameterise existing handlers; editing here changes
 * the name and trigger phrase, never the capability.
 */
/** Human label for a reflex action type ("ORGANIZE_FILES" -> "Organize files"). */
fun actionTypeLabel(actionType: String): String =
    actionType.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

@Composable
fun LearnedSkillRow(
    skill: SkillEntity,
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
            Text(skill.name, color = RaijinWhite, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(
                "Trigger: ${skill.pattern} -> ${actionTypeLabel(skill.actionType)}",
                color = RaijinText,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "used ${skill.usageCount}x",
                color = RaijinTextDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        TextButton(onClick = onEdit) {
            Text("Edit", color = RaijinBlue, fontSize = 11.sp)
        }
        TextButton(onClick = onDelete) {
            Text("✕", color = RaijinRed, fontSize = 11.sp)
        }
    }
}

/**
 * SK-4 edit card: name and trigger phrase only. The action (handler +
 * parameters) is intentionally not editable - a reflex can never grow a
 * new capability through editing.
 */
@Composable
fun LearnedSkillEditDialog(
    skill: SkillEntity,
    onSave: (name: String, pattern: String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        LearnedSkillEditCard(skill, onSave, onDismiss)
    }
}

@Composable
fun LearnedSkillEditCard(
    skill: SkillEntity,
    onSave: (name: String, pattern: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(skill.id) { mutableStateOf(skill.name) }
    var pattern by remember(skill.id) { mutableStateOf(skill.pattern) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = RaijinSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "EDIT LEARNED REFLEX",
                color = RaijinGold,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name", fontSize = 11.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RaijinBlue,
                    unfocusedBorderColor = RaijinBorderDim,
                    focusedTextColor = RaijinWhite,
                    unfocusedTextColor = RaijinText,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text("Trigger phrase", fontSize = 11.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RaijinBlue,
                    unfocusedBorderColor = RaijinBorderDim,
                    focusedTextColor = RaijinWhite,
                    unfocusedTextColor = RaijinText,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Action stays: ${actionTypeLabel(skill.actionType)} (reflexes can re-target phrases, never gain capabilities)",
                color = RaijinText,
                fontSize = 10.sp,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = RaijinText)
                }
                Button(
                    onClick = { if (name.isNotBlank() && pattern.isNotBlank()) onSave(name.trim(), pattern.trim()) },
                    colors = ButtonDefaults.buttonColors(containerColor = RaijinBlue, contentColor = RaijinBlack),
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
