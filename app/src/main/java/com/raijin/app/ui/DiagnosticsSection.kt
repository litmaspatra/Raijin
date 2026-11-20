package com.raijin.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raijin.app.diagnostics.DiagResult
import com.raijin.app.diagnostics.DiagnosticsRunner
import com.raijin.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * EX-1: Diagnostics. One row per capability with PASS/FAIL and the reason.
 * "Run all" executes every local check plus a live ping per configured
 * provider. Every Android-facing PRD item should keep its check here current.
 */
@Composable
fun DiagnosticsSection(initialResults: List<DiagResult> = emptyList()) {
    val context = LocalContext.current
    val runner = remember { DiagnosticsRunner(context) }
    val coroutineScope = rememberCoroutineScope()

    var results by remember { mutableStateOf(initialResults) }
    var running by remember { mutableStateOf(false) }

    fun runAll() {
        if (running) return
        running = true
        // Immediate rows so the owner sees the full list while providers ping.
        results = runner.localChecks().map { it() }
        coroutineScope.launch {
            val providerRows = runner.checkProviders()
            results = results + providerRows
            running = false
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = RaijinSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Capability checks",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RaijinGold,
            )
            Text(
                "Only enable access for features you want. Optional features do not need to turn green. Provider tests contact your configured services.",
                fontSize = 11.sp,
                color = RaijinText,
            )

            Button(
                onClick = ::runAll,
                colors = ButtonDefaults.buttonColors(containerColor = RaijinBlue, contentColor = RaijinBlack),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (running) "Running checks…" else if (results.isEmpty()) "Run all checks" else "Re-run all checks",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }

            results.forEach { row ->
                Surface(color = RaijinBlack.copy(alpha = 0.3f), shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = RaijinWhite)
                        Text(row.status, fontSize = 12.sp, color = when {
                            row.passed == true -> RaijinGreen
                            row.unsupported -> RaijinTextDim
                            else -> RaijinGold
                        })
                        Text(row.detail, fontSize = 13.sp, color = RaijinText, lineHeight = 19.sp)
                        if (row.passed == false && !row.unsupported && row.id in setOf("mic", "contacts", "alarms", "accessibility", "notif", "overlay", "media")) {
                            TextButton(onClick = {
                                val uri = android.net.Uri.parse("package:${context.packageName}")
                                val intent = when (row.id) {
                                    "alarms" -> android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, uri)
                                    "accessibility" -> android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    "notif" -> android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    "overlay" -> android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
                                    else -> android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
                                }
                                try { context.startActivity(intent) } catch (_: Exception) {
                                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)) }
                                }
                            }) { Text("Open setup", color = RaijinBlueLight) }
                        }
                    }
                }
            }
            if (results.isNotEmpty() && !running) {
                Text("${results.count { it.passed == true }} ready. Re-run checks after changing access.", fontSize = 12.sp, color = RaijinText)
            }

        }
    }

    ActionLogCard()
}

/**
 * EX-1/EX-4: what Raijin did and what the owner consented to, newest first.
 * Local-only ledger; cleared on demand.
 */
@Composable
fun ActionLogCard() {
    val context = LocalContext.current
    val store = remember { com.raijin.app.diagnostics.ActionLogStore(context) }
    var entries by remember { mutableStateOf(store.list()) }
    var confirmClear by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = RaijinSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "ACTION LOG & CONSENT LEDGER",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RaijinGold,
            )
            Text(
                "Everything Raijin attempted and every permission decision, newest first. Stored only on this device.",
                fontSize = 11.sp,
                color = RaijinText,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { entries = store.list() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) { Text("Refresh", fontSize = 11.sp, color = RaijinBlueLight) }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) { Text("Clear", fontSize = 11.sp, color = RaijinRed) }
            }

            if (entries.isEmpty()) {
                Text("Nothing recorded yet.", fontSize = 11.sp, color = RaijinTextDim)
            }

            entries.takeLast(30).reversed().forEach { e ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (e.kind == com.raijin.app.diagnostics.ActionLogEntry.Kind.CONSENT) "C" else "A",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (e.kind == com.raijin.app.diagnostics.ActionLogEntry.Kind.CONSENT) RaijinGold else RaijinBlueLight,
                        modifier = Modifier.width(18.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(e.summary, fontSize = 11.sp, color = RaijinBlueLight)
                        Text(
                            "${java.text.SimpleDateFormat("MMM d, HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(e.epochMs))} - ${e.outcome}",
                            fontSize = 10.sp,
                            color = when {
                                e.outcome.startsWith("OK") || e.outcome == "GRANTED" -> RaijinGreen
                                e.outcome.startsWith("DENIED") || e.outcome == "DENIED" -> RaijinRed
                                else -> RaijinGold
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the ledger?", fontSize = 15.sp) },
            text = { Text("This removes the on-device record of Raijin's actions and your permission decisions.") },
            confirmButton = {
                TextButton(onClick = {
                    store.clear()
                    entries = emptyList()
                    confirmClear = false
                }) { Text("Clear", color = RaijinRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Keep") }
            },
        )
    }
}
