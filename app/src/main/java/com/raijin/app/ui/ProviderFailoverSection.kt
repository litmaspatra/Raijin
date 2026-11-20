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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raijin.app.cloud.OpenAiCompatibleClient
import com.raijin.app.cloud.OpenAiConfig
import com.raijin.app.cloud.ProviderConfig
import com.raijin.app.cloud.ProviderHealth
import com.raijin.app.cloud.ProviderPreset
import com.raijin.app.cloud.ProviderRegistryStore
import com.raijin.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * BR-5: multi-provider failover management UI.
 *
 * When at least one provider is registered here, the brain's failover chain
 * (BR-2/BR-3/BR-4) is used instead of the legacy single Cloud Brain config.
 * API keys are written only to the Keystore-backed store via
 * [ProviderRegistryStore]; this UI never reads a saved key back (a blank key
 * field on edit means "keep the existing key").
 */
@Composable
fun ProviderFailoverSection() {
    val context = LocalContext.current
    val registry = remember { ProviderRegistryStore(context) }
    val coroutineScope = rememberCoroutineScope()

    var providers by remember { mutableStateOf(registry.listProviders()) }
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }

    fun reload() { providers = registry.listProviders() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = RaijinSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "FAILOVER PROVIDER CHAIN (BR-5)",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RaijinGold,
            )
            Text(
                "Add one or more AI providers. Raijin tries them in priority order and fails over automatically. " +
                    "Once at least one provider is listed here, this chain replaces the single endpoint above. " +
                    "Keys are stored encrypted in the Android Keystore.",
                fontSize = 11.sp,
                color = RaijinText,
            )

            if (providers.isEmpty()) {
                Text(
                    "No providers registered yet - the single endpoint above is still in use.",
                    fontSize = 11.sp,
                    color = RaijinTextDim,
                )
            }

            providers.sortedBy { it.first.priority }.forEach { (cfg, health) ->
                ProviderRow(
                    cfg = cfg,
                    health = health,
                    isTesting = testingId == cfg.id,
                    onToggle = { enabled ->
                        registry.upsertProvider(cfg.copy(enabled = enabled))
                        reload()
                    },
                    onTest = {
                        testingId = cfg.id
                        statusText = "Pinging ${cfg.label}…"
                        coroutineScope.launch {
                            val key = registry.getApiKey(cfg.id)
                            val client = OpenAiCompatibleClient(configProvider = {
                                OpenAiConfig(cfg.baseUrl, key, cfg.model, cfg.timeoutSeconds)
                            })
                            val res = client.testConnection()
                            testingId = null
                            if (res.first) {
                                registry.clearAttention(cfg.id)
                                statusText = "✓ ${cfg.label}: ${res.second}"
                            } else {
                                statusText = "✗ ${cfg.label}: ${res.second}"
                            }
                            reload()
                        }
                    },
                    onEdit = {
                        editing = cfg
                        showEditor = true
                    },
                    onDelete = {
                        registry.removeProvider(cfg.id)
                        statusText = "Removed ${cfg.label}."
                        reload()
                    },
                )
            }

            // Quick-add presets
            Text("Add from preset:", fontSize = 11.sp, color = RaijinText)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PresetAddChip(OpenAiConfig.PRESET_NVIDIA_NIM) { editing = it; showEditor = true }
                PresetAddChip(OpenAiConfig.PRESET_GROQ) { editing = it; showEditor = true }
                PresetAddChip(OpenAiConfig.PRESET_OPENAI) { editing = it; showEditor = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PresetAddChip(OpenAiConfig.PRESET_OPENROUTER) { editing = it; showEditor = true }
                PresetAddChip(OpenAiConfig.PRESET_DEEPSEEK) { editing = it; showEditor = true }
                PresetAddChip(OpenAiConfig.PRESET_OLLAMA_LOCAL) { editing = it; showEditor = true }
            }

            OutlinedButton(
                onClick = {
                    editing = ProviderConfig(
                        id = "custom-${System.currentTimeMillis()}",
                        label = "",
                        baseUrl = "",
                        model = "",
                        priority = (providers.maxOfOrNull { it.first.priority } ?: 0) + 1,
                    )
                    showEditor = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("+ Add custom provider", fontSize = 12.sp, color = RaijinBlueLight)
            }

            statusText?.let {
                Text(
                    text = it,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (it.startsWith("✓")) RaijinGreen else if (it.startsWith("✗")) RaijinRed else RaijinGold,
                )
            }
        }
    }

    if (showEditor && editing != null) {
        ProviderEditorDialog(
            initial = editing!!,
            isNew = providers.none { it.first.id == editing!!.id },
            onDismiss = { showEditor = false; editing = null },
            onSave = { cfg, apiKey ->
                if (cfg.label.isBlank() || cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
                    statusText = "✗ Name, Base URL and Model are required."
                } else {
                    registry.upsertProvider(cfg, apiKey?.takeIf { it.isNotBlank() })
                    registry.clearAttention(cfg.id)
                    statusText = "Saved ${cfg.label} (priority ${cfg.priority})."
                    reload()
                    showEditor = false
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun PresetAddChip(preset: ProviderPreset, onPick: (ProviderConfig) -> Unit) {
    OutlinedButton(
        onClick = {
            onPick(
                ProviderConfig(
                    id = preset.name.lowercase().replace(" ", "-"),
                    label = preset.name,
                    baseUrl = preset.baseUrl,
                    model = preset.defaultModel,
                    priority = 1,
                ),
            )
        },
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(preset.name, fontSize = 10.sp, color = RaijinBlueLight)
    }
}

@Composable
private fun ProviderRow(
    cfg: ProviderConfig,
    health: ProviderHealth,
    isTesting: Boolean,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = RaijinBlueDim.copy(alpha = 0.25f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "#${cfg.priority} ${cfg.label}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = RaijinBlueLight,
                    )
                    Text(cfg.model, fontSize = 11.sp, color = RaijinText)
                    Text(cfg.baseUrl, fontSize = 10.sp, color = RaijinTextDim, fontFamily = FontFamily.Monospace)
                }
                Switch(checked = cfg.enabled, onCheckedChange = onToggle)
            }

            if (health.needsAttention) {
                Text(
                    "⚠ Failing repeatedly - check key/endpoint, then Test to clear.",
                    fontSize = 10.sp,
                    color = RaijinRed,
                )
            } else if (health.cooldownUntilEpochMs > System.currentTimeMillis()) {
                Text(
                    "Cooling down after failures (failover skips it temporarily).",
                    fontSize = 10.sp,
                    color = RaijinGold,
                )
            } else if (health.consecutiveFailures > 0) {
                Text(
                    "${health.consecutiveFailures} recent failure(s).",
                    fontSize = 10.sp,
                    color = RaijinGold,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onTest, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text(if (isTesting) "Testing…" else "Test", fontSize = 11.sp, color = RaijinBlueLight)
                }
                OutlinedButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text("Edit", fontSize = 11.sp, color = RaijinBlueLight)
                }
                OutlinedButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text("Delete", fontSize = 11.sp, color = RaijinRed)
                }
            }
        }
    }
}

@Composable
private fun ProviderEditorDialog(
    initial: ProviderConfig,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig, String?) -> Unit,
) {
    var label by remember { mutableStateOf(initial.label) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var priorityText by remember { mutableStateOf(initial.priority.toString()) }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add provider" else "Edit ${initial.label}", fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true)
                OutlinedTextField(
                    value = priorityText,
                    onValueChange = { priorityText = it.filter(Char::isDigit).take(3) },
                    label = { Text("Priority (1 = tried first)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(if (isNew) "API key" else "API key (blank = keep current)") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "Hide" else "Show", fontSize = 11.sp, color = RaijinBlue)
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        label = label.trim(),
                        baseUrl = baseUrl.trim(),
                        model = model.trim(),
                        priority = priorityText.toIntOrNull() ?: initial.priority,
                    ),
                    apiKey.trim().takeIf { it.isNotBlank() },
                )
            }) { Text("Save", color = RaijinBlueLight) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
