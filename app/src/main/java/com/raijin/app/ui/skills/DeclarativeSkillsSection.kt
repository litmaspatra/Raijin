package com.raijin.app.ui.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raijin.app.skill.InstalledSkillEntity
import com.raijin.app.skill.SkillFetcher
import com.raijin.app.skill.SkillInstallService
import com.raijin.app.skill.SkillRunLogDao
import com.raijin.app.skill.SkillRunLogEntity
import com.raijin.app.ui.theme.RaijinBlack
import com.raijin.app.ui.theme.RaijinBlue
import com.raijin.app.ui.theme.RaijinBlueLight
import com.raijin.app.ui.theme.RaijinBorderDim
import com.raijin.app.ui.theme.RaijinGreen
import com.raijin.app.ui.theme.RaijinRed
import com.raijin.app.ui.theme.RaijinSurface
import com.raijin.app.ui.theme.RaijinText
import com.raijin.app.ui.theme.RaijinTextDim
import com.raijin.app.ui.theme.RaijinWhite
import io.raijin.brain.skills.SkillSourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SK-2 declarative skills card: install from a file, an https URL or a
 * GitHub repo; every install passes through the owner approval dialog
 * before anything is stored. Enable/disable and rollback are per skill id.
 */
@Composable
fun DeclarativeSkillsSection(
    installService: SkillInstallService,
    runLogDao: SkillRunLogDao,
    fetcher: SkillFetcher = remember { SkillFetcher() },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<SkillInstallService.PlanOutcome?>(null) }
    var installed by remember { mutableStateOf(emptyList<InstalledSkillEntity>()) }
    var recentRuns by remember { mutableStateOf(emptyList<SkillRunLogEntity>()) }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            installed = installService.installed()
            recentRuns = runLogDao.recent(5)
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    fun planFromPayload(payload: String, sourceLabel: String) {
        scope.launch(Dispatchers.IO) {
            val outcome = installService.planInstall(payload, sourceLabel)
            withContext(Dispatchers.Main) {
                busy = false
                pending = outcome
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch(Dispatchers.IO) {
                val payload = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
                if (payload == null) {
                    withContext(Dispatchers.Main) {
                        busy = false
                        statusMessage = "Could not read that file."
                    }
                } else if (payload.length > SkillFetcher.MAX_PAYLOAD_CHARS) {
                    withContext(Dispatchers.Main) {
                        busy = false
                        statusMessage = "Skill file too large (>${SkillFetcher.MAX_PAYLOAD_CHARS} chars)."
                    }
                } else {
                    planFromPayload(payload, "file:${uri.lastPathSegment ?: "picked"}")
                }
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = RaijinSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "🧩 DECLARATIVE SKILLS",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RaijinBlueLight,
            )

            OutlinedTextField(
                value = sourceInput,
                onValueChange = { sourceInput = it },
                label = { Text("skill URL or owner/repo (e.g. litmaspatra/raijin-skills)", fontSize = 11.sp) },
                singleLine = true,
                enabled = !busy,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RaijinBlue,
                    unfocusedBorderColor = RaijinBorderDim,
                    focusedTextColor = RaijinWhite,
                    unfocusedTextColor = RaijinText,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val source = SkillSourceResolver.resolve(sourceInput)
                        when (source) {
                            is SkillSourceResolver.SkillSource.Invalid -> statusMessage = source.reason
                            is SkillSourceResolver.SkillSource.LocalFile ->
                                statusMessage = "Use the file button for local files."
                            else -> {
                                busy = true
                                statusMessage = null
                                scope.launch(Dispatchers.IO) {
                                    when (val outcome = fetcher.load(source)) {
                                        is SkillFetcher.Outcome.Error ->
                                            withContext(Dispatchers.Main) {
                                                busy = false
                                                statusMessage = outcome.reason
                                            }
                                        is SkillFetcher.Outcome.Ok ->
                                            planFromPayload(outcome.payloadJson, outcome.sourceLabel)
                                    }
                                }
                            }
                        }
                    },
                    enabled = !busy && sourceInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = RaijinBlue, contentColor = RaijinBlack),
                ) {
                    Text(if (busy) "Working..." else "Install", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                TextButton(
                    onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    enabled = !busy,
                ) {
                    Text("From file", color = RaijinText, fontSize = 12.sp)
                }
            }

            statusMessage?.let { Text(it, color = RaijinRed, fontSize = 11.sp) }

            val latestBySkill = installed.groupBy { it.skillId }
                .mapValues { (_, versions) -> versions.maxBy { it.version } }
            if (latestBySkill.isEmpty()) {
                Text(
                    "No declarative skills installed. They run before learned shortcuts and never exceed a spoken command.",
                    fontSize = 11.sp,
                    color = RaijinTextDim,
                )
            } else {
                latestBySkill.forEach { (skillId, latest) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(RaijinBlack.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(skillId, color = RaijinWhite, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text(
                                "v${latest.version} · ${latest.source}",
                                color = RaijinText,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (installed.count { it.skillId == skillId } > 1) {
                            TextButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        installService.rollback(skillId)
                                        refresh()
                                    }
                                }
                            ) {
                                Text("Rollback", color = RaijinTextDim, fontSize = 10.sp)
                            }
                        }
                        Switch(
                            checked = latest.enabled,
                            onCheckedChange = { on ->
                                scope.launch(Dispatchers.IO) {
                                    installService.setEnabled(skillId, on)
                                    refresh()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = RaijinGreen,
                                uncheckedTrackColor = RaijinBorderDim,
                            ),
                        )
                    }
                }
            }
            if (recentRuns.isNotEmpty()) {
                Text(
                    "RECENT RUNS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = RaijinTextDim,
                )
                recentRuns.forEach { run -> SkillRunLogRow(run) }
            }
        }
    }

    when (val outcome = pending) {
        is SkillInstallService.PlanOutcome.Ready -> SkillInstallApprovalDialog(
            ready = outcome,
            onApprove = {
                pending = null
                scope.launch(Dispatchers.IO) {
                    val ok = installService.confirmInstall(outcome)
                    withContext(Dispatchers.Main) {
                        statusMessage = if (ok) null else "Install failed; nothing was stored."
                    }
                    refresh()
                }
            },
            onCancel = { pending = null },
        )
        is SkillInstallService.PlanOutcome.Rejected -> SkillInstallRejectedDialog(
            rejected = outcome,
            onDismiss = { pending = null },
        )
        null -> Unit
    }
}

/** One run-log entry; separate composable so it can be screenshotted. */
@Composable
fun SkillRunLogRow(run: SkillRunLogEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RaijinBlack.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(run.skill_id, color = RaijinWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                "\"${run.utterance}\"",
                color = RaijinText,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        Text(
            runOutcomeLabel(run.outcome),
            color = if (run.outcome == "completed") RaijinGreen else RaijinRed,
            fontSize = 10.sp,
        )
    }
}
