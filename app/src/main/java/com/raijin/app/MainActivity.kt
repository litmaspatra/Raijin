package com.raijin.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.raijin.app.audio.KokoroAudioClient
import com.raijin.app.audio.KokoroConfigStore
import com.raijin.app.audio.KokoroVoiceConfig
import com.raijin.app.cloud.OpenAiCompatibleClient
import com.raijin.app.cloud.OpenAiConfig
import com.raijin.app.cloud.OpenAiConfigStore
import com.raijin.app.hud.HudOverlay
import com.raijin.app.memory.MemoryFactEntity
import com.raijin.app.skill.SkillEntity
import com.raijin.app.skill.SkillInstallService
import com.raijin.app.ui.skills.DeclarativeSkillsSection
import com.raijin.app.storage.StorageAuditReport
import com.raijin.app.storage.StorageAuditor
import com.raijin.app.ui.ProviderFailoverSection
import com.raijin.app.ui.DiagnosticsSection
import com.raijin.app.ui.OemSetupSection

import com.raijin.app.ui.island.IslandComposable
import com.raijin.app.ui.island.IslandState
import com.raijin.app.ui.theme.*
import com.raijin.app.viewmodel.IslandViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tactical Command Deck & Master Settings Hub for Raijin.
 *
 * Provides:
 * - Persistent solid dark window (never auto-closes)
 * - Automatic permission onboarding and overlay permission prompts
 * - Interactive Storage Audit & Safe Selective Pruning (Zero unapproved deletion)
 * - Cloud Brain configuration (NVIDIA NIM, Groq, OpenAI, Ollama) with live test ping
 * - HUD vertical offset adjustment for punch-holes / system islands
 * - Tap-to-Talk single-shot voice loop & text directive console
 * - Memory & Skills management
 * - 1-Click Permissions Gateway
 */
class MainActivity : ComponentActivity() {

    private val vm: IslandViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // EX-1 consent ledger: record each runtime-permission decision.
        val ledger = com.raijin.app.diagnostics.ActionLogStore(this)
        grants.forEach { (perm, granted) ->
            val short = perm.substringAfterLast('.')
            runCatching {
                ledger.append(
                    com.raijin.app.diagnostics.ActionLogEntry.Kind.CONSENT,
                    "Permission $short",
                    if (granted) "GRANTED" else "DENIED",
                )
            }
        }
        startRaijinServiceIfReady()
    }

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        runCatching {
            com.raijin.app.diagnostics.ActionLogStore(this).append(
                com.raijin.app.diagnostics.ActionLogEntry.Kind.CONSENT,
                "Overlay (display over other apps)",
                if (Settings.canDrawOverlays(this)) "GRANTED" else "DENIED",
            )
        }
        startRaijinServiceIfReady()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Immediately request core runtime permissions on launch
        requestNeededPermissions()

        // 2. Start service if overlay already granted
        startRaijinServiceIfReady()

        setContent {
            RaijinTheme {
                MasterSettingsAndCommandDeck(
                    vm = vm,
                    onRequestPermissions = ::requestNeededPermissions,
                    onRequestOverlay = ::requestOverlayPermission,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startRaijinServiceIfReady()
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.SEND_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            try {
                permissionLauncher.launch(needed.toTypedArray())
            } catch (e: Exception) {
                Log.w("MainActivity", "Error launching permissions request: ${e.message}")
            }
        }
    }

    private fun requestOverlayPermission() {
        try {
            overlayPermLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            // Fallback for OEM ROMs that reject package Uri
            try {
                overlayPermLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                Log.e("MainActivity", "Failed opening overlay settings: ${e2.message}", e2)
            }
        }
    }

    private fun startRaijinServiceIfReady() {
        if (!Settings.canDrawOverlays(this)) return
        try {
            val intent = Intent(this, RaijinService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed starting RaijinService: ${e.message}", e)
        }
    }
}

enum class DeckTab(val label: String) {
    COMMANDS("Directives"),
    SETTINGS("Cloud Brain & HUD"),
    MEMORY("Memory & Skills"),
    PERMISSIONS("Permissions"),
}

@Composable
private fun MasterSettingsAndCommandDeck(
    vm: IslandViewModel,
    onRequestPermissions: () -> Unit,
    onRequestOverlay: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(DeckTab.COMMANDS) }

    val configStore = remember { OpenAiConfigStore(context) }
    var currentConfig by remember { mutableStateOf(configStore.load()) }
    var baseUrl by remember { mutableStateOf(currentConfig.baseUrl) }
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var model by remember { mutableStateOf(currentConfig.model) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }

    val hudPrefs = remember { context.getSharedPreferences("raijin_hud_prefs", Context.MODE_PRIVATE) }
    var hudOffsetDp by remember { mutableStateOf(hudPrefs.getInt("hud_offset_y", HudOverlay.DEFAULT_OFFSET_DP)) }

    var commandText by remember { mutableStateOf("") }

    // Kokoro Neural TTS State
    val kokoroStore = remember { KokoroConfigStore(context) }
    var kokoroConfig by remember { mutableStateOf(kokoroStore.load()) }
    var kokoroEndpoint by remember { mutableStateOf(kokoroConfig.endpointUrl) }
    var kokoroApiKey by remember { mutableStateOf(kokoroConfig.apiKey) }
    var kokoroVoice by remember { mutableStateOf(kokoroConfig.voice) }
    var kokoroEnabled by remember { mutableStateOf(kokoroConfig.enabled) }
    var kokoroTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingKokoro by remember { mutableStateOf(false) }

    // Cloud Brain Model Catalog State
    var isFetchingModels by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelPicker by remember { mutableStateOf(false) }
    var modelSearchQuery by remember { mutableStateOf("") }

    // Live Island State: Observe real RaijinService state, fall back to vm
    val liveGlobalState by RaijinService.globalIslandState.collectAsState(initial = IslandState.Idle)
    val vmState by vm.island.collectAsState(initial = IslandState.Idle)
    val state = if (liveGlobalState !is IslandState.Idle) liveGlobalState else vmState

    // Storage Auditor State
    val storageAuditor = remember { StorageAuditor(context) }
    var storageReport by remember { mutableStateOf<StorageAuditReport?>(null) }
    var isScanningStorage by remember { mutableStateOf(false) }
    var approvedStorageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var storageStatusMessage by remember { mutableStateOf<String?>(null) }

    var recentMemories by remember { mutableStateOf(emptyList<MemoryFactEntity>()) }
    var installedSkills by remember { mutableStateOf(emptyList<SkillEntity>()) }
    var editingLearnedSkill by remember { mutableStateOf<SkillEntity?>(null) }
    // ME-4: "What I know about you" data
    var knownAppAliases by remember { mutableStateOf(emptyList<com.raijin.app.apps.AppAliasEntity>()) }
    var knownCorrections by remember { mutableStateOf(emptyList<com.raijin.app.contact.CorrectionEntry>()) }
    var knownPatterns by remember { mutableStateOf(emptyList<com.raijin.app.memory.MemoryPatternEntity>()) }
    var pendingReminders by remember { mutableStateOf(emptyList<com.raijin.app.reminder.ReminderEntity>()) }
    var editingFact by remember { mutableStateOf<MemoryFactEntity?>(null) }
    var showForgetEverything by remember { mutableStateOf(false) }

    fun refreshDatabaseData() {
        coroutineScope.launch(Dispatchers.IO) {
            val db = RaijinDatabase.getInstance(context)
            recentMemories = try { db.memoryDao().getAll() } catch (_: Exception) { emptyList() }
            installedSkills = try { db.skillDao().getAll() } catch (_: Exception) { emptyList() }
            knownAppAliases = try { db.appAliasDao().getAll() } catch (_: Exception) { emptyList() }
            knownCorrections = try { db.correctionEntryDao().all() } catch (_: Exception) { emptyList() }
            knownPatterns = try { db.memoryPatternDao().all() } catch (_: Exception) { emptyList() }
            pendingReminders = try {
                com.raijin.app.reminder.ReminderListing.pending(db.reminderDao().findAllUnfired())
            } catch (_: Exception) { emptyList() }
        }
    }

    // ME-4: export everything Raijin knows to a JSON file the owner controls.
    fun exportMemory() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val db = RaijinDatabase.getInstance(context)
                val root = org.json.JSONObject()
                root.put("exported_at", System.currentTimeMillis())
                root.put("facts", org.json.JSONArray().apply {
                    db.memoryDao().getAll().forEach { f ->
                        put(org.json.JSONObject().apply {
                            put("kind", f.kind); put("text", f.text); put("tier", f.tier)
                            f.entityAlias?.let { put("entity_alias", it) }
                            put("created_at", f.createdAt)
                        })
                    }
                })
                root.put("app_aliases", org.json.JSONArray().apply {
                    db.appAliasDao().getAll().forEach { a ->
                        put(org.json.JSONObject().apply {
                            put("spoken_alias", a.spokenAlias); put("intended_app", a.intendedApp)
                        })
                    }
                })
                root.put("contact_corrections", org.json.JSONArray().apply {
                    db.correctionEntryDao().all().forEach { c ->
                        put(org.json.JSONObject().apply {
                            put("wrong_alias", c.wrongAlias); put("correct_contact_id", c.correctContactId)
                        })
                    }
                })
                root.put("learned_reflexes", org.json.JSONArray().apply {
                    db.skillDao().getAll().forEach { sk ->
                        put(org.json.JSONObject().apply {
                            put("name", sk.name); put("pattern", sk.pattern)
                            put("action", sk.actionType); put("usage_count", sk.usageCount)
                        })
                    }
                })
                root.put("skill_run_log", org.json.JSONArray().apply {
                    db.skillRunLogDao().recent(500).forEach { r ->
                        put(org.json.JSONObject().apply {
                            put("skill_id", r.skill_id); put("skill_version", r.skill_version)
                            put("utterance", r.utterance); put("outcome", r.outcome)
                            put("detail", r.detail); put("ran_at", r.ran_at)
                        })
                    }
                })
                root.put("action_log", org.json.JSONArray().apply {
                    com.raijin.app.diagnostics.ActionLogStore(context).list().forEach { e ->
                        put(org.json.JSONObject().apply {
                            put("kind", e.kind.name); put("summary", e.summary)
                            put("outcome", e.outcome); put("at", e.epochMs)
                        })
                    }
                })
                root.put("patterns", org.json.JSONArray().apply {
                    db.memoryPatternDao().all().forEach { pt ->
                        put(org.json.JSONObject().apply {
                            put("signature", pt.signature); put("day_part", pt.dayPart)
                            put("count", pt.count)
                        })
                    }
                })
                val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                val file = java.io.File(dir, "raijin-memory-${System.currentTimeMillis()}.json")
                file.writeText(root.toString(2))
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file,
                )
                val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    android.content.Intent.createChooser(share, "Export Raijin memory")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshDatabaseData()
    }

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var showOverlayDialog by remember { mutableStateOf(!Settings.canDrawOverlays(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val hasContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val hasCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    val hasSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    // First Launch Overlay Permission Dialog
    if (showOverlayDialog && !overlayGranted) {
        AlertDialog(
            onDismissRequest = { showOverlayDialog = false },
            title = {
                Text("Enable Floating Arc HUD", color = RaijinWhite, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Raijin operates as an autonomous sci-fi tactical companion (inspired by Karen from Spider-Man & Friday from Iron Man). To project the Arc-Reactor island over your apps, please grant 'Display over other apps'.",
                    color = RaijinText,
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOverlayDialog = false
                        onRequestOverlay()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RaijinBlue, contentColor = RaijinBlack),
                ) {
                    Text("Grant Permission", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayDialog = false }) {
                    Text("Later", color = RaijinTextDim)
                }
            },
            containerColor = RaijinSurface,
        )
    }

    // Cloud Brain Model Picker Dialog
    if (showModelPicker) {
        val filteredModels = remember(availableModels, modelSearchQuery) {
            if (modelSearchQuery.isBlank()) availableModels
            else availableModels.filter { it.contains(modelSearchQuery.trim(), ignoreCase = true) }
        }

        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = {
                Text(
                    "Select Model (${availableModels.size} available)",
                    color = RaijinWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                ) {
                    OutlinedTextField(
                        value = modelSearchQuery,
                        onValueChange = { modelSearchQuery = it },
                        label = { Text("Search models (e.g. 'llama', 'qwen', 'meta')", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RaijinBlue,
                            unfocusedBorderColor = RaijinBorderDim,
                            focusedTextColor = RaijinWhite,
                            unfocusedTextColor = RaijinText,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        items(filteredModels.size) { idx ->
                            val m = filteredModels[idx]
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        model = m
                                        val cfg = OpenAiConfig(baseUrl, apiKey, m)
                                        configStore.save(cfg)
                                        showModelPicker = false
                                        testResultText = "Selected & saved model: $m"
                                    }
                                    .padding(vertical = 4.dp),
                                color = if (m == model) RaijinBlueDim else Color.Transparent,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = m,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = if (m == model) RaijinBlueLight else RaijinWhite,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (m == model) {
                                        Text(
                                            "ACTIVE ✓",
                                            color = RaijinGreen,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(RaijinBorderDim.copy(alpha = 0.5f))
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelPicker = false }) {
                    Text("Close", color = RaijinTextDim)
                }
            },
            containerColor = RaijinSurface,
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = RaijinBlack,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Spacer(Modifier.height(28.dp))

                // Missing Overlay Alert Banner
                if (!overlayGranted) {
                    Surface(
                        color = RaijinRed.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, RaijinRed),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRequestOverlay() },
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("⚠️ OVERLAY PERMISSION REQUIRED", color = RaijinRed, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                Spacer(Modifier.height(2.dp))
                                Text("Tap to allow Raijin HUD to float over other apps", color = RaijinWhite, fontSize = 11.sp)
                            }
                            Button(
                                onClick = onRequestOverlay,
                                colors = ButtonDefaults.buttonColors(containerColor = RaijinRed, contentColor = RaijinWhite),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text("ENABLE", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    // In-window interactive Arc-Reactor Island (only displayed when system overlay is not yet active)
                    IslandComposable(
                        state = state,
                        onTap = { RaijinService.startVoiceListening() },
                        onAction = { traceId, actionId, payload ->
                            RaijinService.brainInstance?.let { brain ->
                                coroutineScope.launch {
                                    brain.resolveActionConfirmation(traceId, payload.equals("true", ignoreCase = true) || payload.equals("confirm", ignoreCase = true))
                                }
                            } ?: vm.onUserResponse(traceId, actionId, payload)
                        },
                    )
                } else {
                    // Clearance under the floating system Arc HUD so window content doesn't collide
                    Spacer(Modifier.height(54.dp))
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "RAIJIN // TACTICAL COMMAND DECK",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = RaijinBlueLight,
                    letterSpacing = 1.sp,
                )

                Spacer(Modifier.height(16.dp))

                // Tab Selector Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DeckTab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Surface(
                            onClick = {
                                selectedTab = tab
                                if (tab == DeckTab.MEMORY) refreshDatabaseData()
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) RaijinBlue else RaijinSurface,
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 8.dp),
                            ) {
                                Text(
                                    text = tab.label,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) RaijinBlack else RaijinText,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            // ── TAB CONTENT ──────────────────────────────────────────────────────────

            when (selectedTab) {
                DeckTab.COMMANDS -> {
                    item {
                        // Hero Tap to Talk Button
                        Button(
                            onClick = { RaijinService.startVoiceListening() },
                            colors = ButtonDefaults.buttonColors(containerColor = RaijinBlue, contentColor = RaijinBlack),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "🎤 TAP TO TALK // SINGLE-SHOT VOICE",
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // Directive Input Box
                        OutlinedTextField(
                            value = commandText,
                            onValueChange = { commandText = it },
                            label = { Text("Directive (e.g. 'open whatsapp', 'call Maa on speaker')", fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RaijinBlue,
                                unfocusedBorderColor = RaijinBorderDim,
                                focusedTextColor = RaijinWhite,
                                unfocusedTextColor = RaijinText,
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (commandText.isNotBlank()) {
                                    RaijinService.dispatchUtterance(commandText.trim())
                                    commandText = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RaijinBlueDim, contentColor = RaijinBlueLight),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Execute Directive", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(20.dp))

                        // STORAGE AUDIT & DEFENSE CARD
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = RaijinSurface,
                            border = BorderStroke(1.dp, RaijinBorderDim),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text("STORAGE AUDIT & DEFENSE", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RaijinBlue)
                                        Text("Zero unapproved deletion · Review before pruning", fontSize = 10.sp, color = RaijinTextDim)
                                    }
                                    Surface(shape = RoundedCornerShape(4.dp), color = RaijinGreen.copy(alpha = 0.15f)) {
                                        Text("🛡️ SAFE", color = RaijinGreen, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            isScanningStorage = true
                                            storageStatusMessage = null
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val report = storageAuditor.scan()
                                                withContext(Dispatchers.Main) {
                                                    storageReport = report
                                                    approvedStorageIds = report.items.map { it.id }.toSet()
                                                    isScanningStorage = false
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = RaijinBlueDim, contentColor = RaijinBlueLight),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(if (isScanningStorage) "Auditing…" else "Audit Storage Junk", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                storageReport?.let { report ->
                                    Text("AUDITED STORAGE CANDIDATES (${StorageAuditor.formatBytes(report.totalSizeBytes)})", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RaijinGold)
                                    report.items.forEach { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    approvedStorageIds = if (approvedStorageIds.contains(item.id)) {
                                                        approvedStorageIds - item.id
                                                    } else {
                                                        approvedStorageIds + item.id
                                                    }
                                                },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(
                                                checked = approvedStorageIds.contains(item.id),
                                                onCheckedChange = { checked ->
                                                    approvedStorageIds = if (checked) approvedStorageIds + item.id else approvedStorageIds - item.id
                                                },
                                                colors = CheckboxDefaults.colors(checkedColor = RaijinBlue, uncheckedColor = RaijinTextDim),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(item.title, color = RaijinWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                    Text(StorageAuditor.formatBytes(item.sizeBytes), color = RaijinBlueLight, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                                }
                                                Text(item.description, color = RaijinTextDim, fontSize = 10.sp)
                                            }
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val freed = storageAuditor.cleanApproved(approvedStorageIds)
                                                val updated = storageAuditor.scan()
                                                withContext(Dispatchers.Main) {
                                                    storageReport = updated
                                                    approvedStorageIds = emptySet()
                                                    storageStatusMessage = "✓ Cleaned ${StorageAuditor.formatBytes(freed)} approved junk. 0 personal files touched."
                                                }
                                            }
                                        },
                                        enabled = approvedStorageIds.isNotEmpty(),
                                        colors = ButtonDefaults.buttonColors(containerColor = RaijinRed, contentColor = RaijinWhite),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Clean Approved Items Only", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }

                                storageStatusMessage?.let { msg ->
                                    Text(msg, color = RaijinGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        Text(
                            text = "TACTICAL QUICK DIRECTIVES",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = RaijinTextDim,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TacticalChip("💬 Open WhatsApp", Modifier.weight(1f)) {
                                    RaijinService.dispatchUtterance("open whatsapp")
                                }
                                TacticalChip("🧹 Scan Storage Junk", Modifier.weight(1f)) {
                                    RaijinService.dispatchUtterance("clean storage")
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TacticalChip("☀️ Morning Briefing", Modifier.weight(1f)) {
                                    RaijinService.triggerMorningBrief()
                                }
                                TacticalChip("⏰ Remind in 5m", Modifier.weight(1f)) {
                                    RaijinService.dispatchUtterance("remind me to check oven in 5 minutes")
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TacticalChip("🌐 Research Quantum Computing", Modifier.weight(1f)) {
                                    RaijinService.dispatchUtterance("research quantum computing")
                                }
                                TacticalChip("📞 Call on Speaker", Modifier.weight(1f)) {
                                    RaijinService.dispatchUtterance("call Maa on speaker")
                                }
                            }
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }

                DeckTab.SETTINGS -> {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = RaijinSurface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "CLOUD BRAIN ENDPOINT (NVIDIA NIM / OPENAI)",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RaijinGold,
                                )

                                Text(
                                    "Choose a provider preset or configure any custom OpenAI-compatible endpoint:",
                                    fontSize = 11.sp,
                                    color = RaijinText,
                                )

                                // Preset Chips
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ProviderChip("NVIDIA NIM", baseUrl.contains("nvidia")) {
                                        baseUrl = OpenAiConfig.PRESET_NVIDIA_NIM.baseUrl
                                        model = OpenAiConfig.PRESET_NVIDIA_NIM.defaultModel
                                    }
                                    ProviderChip("Groq", baseUrl.contains("groq")) {
                                        baseUrl = OpenAiConfig.PRESET_GROQ.baseUrl
                                        model = OpenAiConfig.PRESET_GROQ.defaultModel
                                    }
                                    ProviderChip("OpenAI", baseUrl.contains("openai.com")) {
                                        baseUrl = "https://api.openai.com/v1"
                                        model = "gpt-4o-mini"
                                    }
                                    ProviderChip("Ollama", baseUrl.contains("11434")) {
                                        baseUrl = OpenAiConfig.PRESET_OLLAMA_LOCAL.baseUrl
                                        model = OpenAiConfig.PRESET_OLLAMA_LOCAL.defaultModel
                                    }
                                }

                                OutlinedTextField(
                                    value = baseUrl,
                                    onValueChange = { baseUrl = it },
                                    label = { Text("Base URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = { apiKey = it },
                                    label = { Text("API Key (e.g. nvapi-...)") },
                                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        TextButton(onClick = { showApiKey = !showApiKey }) {
                                            Text(if (showApiKey) "Hide" else "Show", fontSize = 11.sp, color = RaijinBlue)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = model,
                                    onValueChange = { model = it },
                                    label = { Text("Model Name (e.g. meta/llama-3.3-70b-instruct)") },
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                Button(
                                    onClick = {
                                        isFetchingModels = true
                                        testResultText = "Connecting to $baseUrl/v1/models..."
                                        coroutineScope.launch {
                                            val client = OpenAiCompatibleClient(configProvider = { OpenAiConfig(baseUrl, apiKey, model) })
                                            val res = client.fetchModels()
                                            isFetchingModels = false
                                            if (res.first && res.second.isNotEmpty()) {
                                                availableModels = res.second
                                                showModelPicker = true
                                                testResultText = "✓ Fetched ${res.second.size} models from API!"
                                            } else {
                                                testResultText = "✗ Failed fetching models: ${res.second.firstOrNull() ?: "Check endpoint & API key"}"
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RaijinBlueDim, contentColor = RaijinBlueLight),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = if (isFetchingModels) "Querying API Catalog…" else if (availableModels.isNotEmpty()) "⚡ Select Model (${availableModels.size} available)" else "⚡ Fetch Models from API",
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            val cfg = OpenAiConfig(baseUrl, apiKey, model)
                                            configStore.save(cfg)
                                            testResultText = "Config saved to device."
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = RaijinBlue, contentColor = RaijinBlack),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("Save Config", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            isTestingConnection = true
                                            testResultText = "Pinging endpoint…"
                                            coroutineScope.launch {
                                                val client = OpenAiCompatibleClient(configProvider = { OpenAiConfig(baseUrl, apiKey, model) })
                                                val res = client.testConnection()
                                                isTestingConnection = false
                                                testResultText = if (res.first) "✓ Connected: ${res.second}" else "✗ Failed: ${res.second}"
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(if (isTestingConnection) "Testing…" else "Test Ping", fontSize = 12.sp, color = RaijinBlueLight)
                                    }
                                }

                                testResultText?.let {
                                    Text(
                                        text = it,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = if (it.startsWith("✓")) RaijinGreen else if (it.startsWith("✗")) RaijinRed else RaijinGold,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // BR-5: multi-provider failover chain (replaces the single endpoint when populated)
                        ProviderFailoverSection()

                        Spacer(Modifier.height(16.dp))

                        // EX-1: objective diagnostics self-tests
                        DiagnosticsSection()

                        Spacer(Modifier.height(16.dp))

                        // EX-2: OEM keep-alive setup wizard
                        OemSetupSection()

                        Spacer(Modifier.height(16.dp))

                        // Kokoro Neural Voice Synthesis & Persona Configuration
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = RaijinSurface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "KOKORO NEURAL VOICE SYNTHESIS",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = RaijinBlueLight,
                                        )
                                        Text("Sci-fi HUD voice personas (Karen & Friday)", fontSize = 10.sp, color = RaijinTextDim)
                                    }
                                    Switch(
                                        checked = kokoroEnabled,
                                        onCheckedChange = {
                                            kokoroEnabled = it
                                            val updated = KokoroVoiceConfig(
                                                endpointUrl = kokoroEndpoint,
                                                apiKey = kokoroApiKey,
                                                voice = kokoroVoice,
                                                speed = 1.0f,
                                                enabled = it,
                                            )
                                            kokoroStore.save(updated)
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = RaijinBlue,
                                            checkedTrackColor = RaijinBlueDim,
                                        ),
                                    )
                                }

                                if (kokoroEnabled) {
                                    Text("Select Persona Voice:", fontSize = 11.sp, color = RaijinText)

                                    // Voice Selection Chips
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        KokoroVoiceConfig.AVAILABLE_VOICES.chunked(2).forEach { rowVoices ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                rowVoices.forEach { opt ->
                                                    Surface(
                                                        onClick = {
                                                            kokoroVoice = opt.id
                                                            val updated = KokoroVoiceConfig(
                                                                endpointUrl = kokoroEndpoint,
                                                                apiKey = kokoroApiKey,
                                                                voice = opt.id,
                                                                speed = 1.0f,
                                                                enabled = kokoroEnabled,
                                                            )
                                                            kokoroStore.save(updated)
                                                        },
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = if (kokoroVoice == opt.id) RaijinBlue else RaijinBlack,
                                                        border = ButtonDefaults.outlinedButtonBorder.copy(
                                                            brush = androidx.compose.ui.graphics.SolidColor(if (kokoroVoice == opt.id) RaijinBlue else RaijinBorderDim)
                                                        ),
                                                        modifier = Modifier.weight(1f),
                                                    ) {
                                                        Box(
                                                            contentAlignment = Alignment.Center,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                                                        ) {
                                                            Text(
                                                                text = opt.label,
                                                                fontSize = 9.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = if (kokoroVoice == opt.id) RaijinBlack else RaijinWhite,
                                                                maxLines = 1,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    OutlinedTextField(
                                        value = kokoroEndpoint,
                                        onValueChange = { kokoroEndpoint = it },
                                        label = { Text("Kokoro Endpoint URL (/v1/audio/speech)") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    OutlinedTextField(
                                        value = kokoroApiKey,
                                        onValueChange = { kokoroApiKey = it },
                                        label = { Text("TTS API Key (DeepInfra / OpenAI / optional)") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Button(
                                            onClick = {
                                                val updated = KokoroVoiceConfig(
                                                    endpointUrl = kokoroEndpoint,
                                                    apiKey = kokoroApiKey,
                                                    voice = kokoroVoice,
                                                    speed = 1.0f,
                                                    enabled = kokoroEnabled,
                                                )
                                                kokoroStore.save(updated)
                                                kokoroTestStatus = "Kokoro voice settings saved."
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = RaijinBlue, contentColor = RaijinBlack),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text("Save Voice", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                isTestingKokoro = true
                                                kokoroTestStatus = "Synthesizing voice sample..."
                                                val client = KokoroAudioClient(context) {
                                                    KokoroVoiceConfig(
                                                        endpointUrl = kokoroEndpoint,
                                                        apiKey = kokoroApiKey,
                                                        voice = kokoroVoice,
                                                        speed = 1.0f,
                                                        enabled = true,
                                                    )
                                                }
                                                val testPhrase = if (kokoroVoice == "bm_george") {
                                                    "At your service, sir. All systems are operational."
                                                } else if (kokoroVoice == "bf_emma") {
                                                    "Friday online. Atmospheric pressure and tactical telemetry verified."
                                                } else {
                                                    "Karen online. Neural audio channel connected and ready."
                                                }
                                                client.speak(testPhrase) { success ->
                                                    coroutineScope.launch(Dispatchers.Main) {
                                                        isTestingKokoro = false
                                                        kokoroTestStatus = if (success) "✓ Kokoro neural audio playback verified!" else "✗ Voice synthesis failed. Falling back to native TTS."
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(if (isTestingKokoro) "Voicing…" else "🔊 Test Voice", fontSize = 11.sp, color = RaijinBlueLight)
                                        }
                                    }

                                    kokoroTestStatus?.let {
                                        Text(
                                            text = it,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = if (it.startsWith("✓")) RaijinGreen else if (it.startsWith("✗")) RaijinRed else RaijinGold,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // HUD Positioning Controls
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = RaijinSurface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "HUD VERTICAL OFFSET (CAMERA / NOTCH CLEARANCE)",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RaijinBlueLight,
                                )

                                Text(
                                    "Adjust how far down the floating HUD sits from the top bar (currently ${hudOffsetDp}dp):",
                                    fontSize = 11.sp,
                                    color = RaijinText,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Button(
                                        onClick = {
                                            hudOffsetDp = (hudOffsetDp - 10).coerceAtLeast(0)
                                            RaijinService.updateHudOffset(hudOffsetDp)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = RaijinSurfaceGlass, contentColor = RaijinWhite),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("▲ Move Up (-10dp)", fontSize = 11.sp)
                                    }

                                    Button(
                                        onClick = {
                                            hudOffsetDp = (hudOffsetDp + 10).coerceAtMost(150)
                                            RaijinService.updateHudOffset(hudOffsetDp)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = RaijinSurfaceGlass, contentColor = RaijinWhite),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("▼ Move Down (+10dp)", fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }

                DeckTab.MEMORY -> {
                    item {
                        // Memory Facts Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = RaijinSurface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("⏰ REMINDERS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RaijinBlueLight)
                                    Text("${pendingReminders.size} pending", fontSize = 10.sp, color = RaijinTextDim)
                                }
                                if (pendingReminders.isEmpty()) {
                                    Text("No pending reminders. Say 'remind me to ...' and it shows up here.", fontSize = 11.sp, color = RaijinTextDim)
                                } else {
                                    pendingReminders.forEach { reminder ->
                                        com.raijin.app.ui.memory.ReminderRow(
                                            text = reminder.text,
                                            detail = com.raijin.app.reminder.ReminderListing.detail(reminder),
                                            onCancel = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val db = RaijinDatabase.getInstance(context)
                                                    com.raijin.app.reminder.ReminderListing.cancel(context, db.reminderDao(), reminder)
                                                    refreshDatabaseData()
                                                }
                                            },
                                        )
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("🧠 WHAT I KNOW ABOUT YOU", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RaijinBlueLight)
                                    Text("${recentMemories.size} facts", fontSize = 10.sp, color = RaijinTextDim)
                                }

                                if (recentMemories.isEmpty()) {
                                    Text("No facts memorized yet. Say 'remember that ...' and it shows up here.", fontSize = 11.sp, color = RaijinTextDim)
                                } else {
                                    recentMemories.forEach { fact ->
                                        com.raijin.app.ui.memory.MemoryFactRow(
                                            fact = fact,
                                            onEdit = { editingFact = fact },
                                            onDelete = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    RaijinDatabase.getInstance(context).memoryDao().deleteById(fact.id)
                                                    refreshDatabaseData()
                                                }
                                            },
                                        )
                                    }
                                }

                                if (knownAppAliases.isNotEmpty() || knownCorrections.isNotEmpty()) {
                                    Text("ALIASES", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = RaijinBlueLight)
                                    knownAppAliases.forEach { alias ->
                                        com.raijin.app.ui.memory.AliasRow(
                                            spokenForm = alias.spokenAlias,
                                            meaning = "opens ${alias.intendedApp}",
                                            onDelete = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    RaijinDatabase.getInstance(context).appAliasDao().delete(alias.spokenAlias)
                                                    refreshDatabaseData()
                                                }
                                            },
                                        )
                                    }
                                    knownCorrections.forEach { correction ->
                                        com.raijin.app.ui.memory.AliasRow(
                                            spokenForm = correction.wrongAlias,
                                            meaning = "contact correction - resolves to the right person",
                                            onDelete = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    // delete via raw query on the row id
                                                    val db = RaijinDatabase.getInstance(context)
                                                    db.correctionEntryDao().deleteById(correction.id)
                                                    refreshDatabaseData()
                                                }
                                            },
                                        )
                                    }
                                }

                                if (knownPatterns.isNotEmpty()) {
                                    Text("LEARNED ROUTINES", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = RaijinBlueLight)
                                    knownPatterns.forEach { pattern ->
                                        com.raijin.app.ui.memory.PatternRow(
                                            pattern = pattern,
                                            description = io.raijin.brain.memory.InteractionPatterns.PatternCandidate(
                                                signature = pattern.signature,
                                                dayPart = io.raijin.brain.memory.InteractionPatterns.DayPart.valueOf(pattern.dayPart),
                                                count = pattern.count,
                                                firstAtEpochMillis = pattern.firstAt,
                                                lastAtEpochMillis = pattern.lastAt,
                                            ).describe(),
                                            onDelete = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val db = RaijinDatabase.getInstance(context)
                                                    db.memoryPatternDao().deleteById(pattern.id)
                                                    // Also remove the mining input that would recreate it.
                                                    com.raijin.app.memory.PatternMiner(
                                                        db.interactionLogDao(), db.memoryPatternDao(),
                                                    ).forgetPatternSources(pattern.signature)
                                                    refreshDatabaseData()
                                                }
                                            },
                                        )
                                    }
                                }

                                com.raijin.app.ui.memory.WhatIKnowFooter(
                                    onExport = { exportMemory() },
                                    onForgetEverything = { showForgetEverything = true },
                                )
                            }
                        }

                        // ME-4 dialogs
                        editingFact?.let { fact ->
                            com.raijin.app.ui.memory.FactEditDialog(
                                fact = fact,
                                onSave = { text, tier ->
                                    coroutineScope.launch(Dispatchers.IO) {
                                        RaijinDatabase.getInstance(context).memoryDao()
                                            .update(fact.copy(text = text, tier = tier))
                                        editingFact = null
                                        refreshDatabaseData()
                                    }
                                },
                                onDismiss = { editingFact = null },
                            )
                        }
                        if (showForgetEverything) {
                            com.raijin.app.ui.memory.ForgetEverythingDialog(
                                factCount = recentMemories.size,
                                onConfirm = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val db = RaijinDatabase.getInstance(context)
                                        db.memoryDao().deleteAll()
                                        db.memoryPatternDao().deleteAll()
                                        db.appAliasDao().deleteAll()
                                        db.correctionEntryDao().deleteAll()
                                        db.skillDao().deleteAll()
                                        db.interactionLogDao().deleteAll()
                                        db.skillRunLogDao().deleteAll()
                                        com.raijin.app.diagnostics.ActionLogStore(context).clear()
                                        showForgetEverything = false
                                        refreshDatabaseData()
                                    }
                                },
                                onDismiss = { showForgetEverything = false },
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Phone voice picker (system TTS)
                        run {
                            val voiceStore = remember { com.raijin.app.prefs.UserPreferencesStore(context) }
                            com.raijin.app.ui.prefs.SystemVoiceCard(context = context, store = voiceStore)
                            Spacer(Modifier.height(16.dp))
                            var lookupOn by remember { mutableStateOf(voiceStore.onlineMediaLookup) }
                            com.raijin.app.ui.prefs.OnlineLookupCard(
                                enabled = lookupOn,
                                onEnabled = { lookupOn = it; voiceStore.onlineMediaLookup = it },
                            )
                            Spacer(Modifier.height(16.dp))
                            var historyOn by remember { mutableStateOf(voiceStore.useContactHistory) }
                            var historyGranted by remember { mutableStateOf(com.raijin.app.contact.CallHistorySignals.hasPermission(context)) }
                            val historyLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                            ) { granted -> historyGranted = granted }
                            val vaultStore = remember { com.raijin.app.obsidian.ObsidianVaultStore(context) }
                            var vaultLabel by remember { mutableStateOf(vaultStore.takeIf { it.openFs() != null }?.label()) }
                            val vaultPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                                androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                            ) { uri ->
                                if (uri != null) {
                                    runCatching { vaultStore.select(uri) }
                                    vaultLabel = vaultStore.takeIf { it.openFs() != null }?.label()
                                }
                            }
                            com.raijin.app.ui.prefs.ObsidianVaultCard(
                                folderLabel = vaultLabel,
                                onChoose = { vaultPicker.launch(null) },
                                onClear = { vaultStore.clear(); vaultLabel = null },
                            )
                            Spacer(Modifier.height(16.dp))
                            val whisperStore = remember { com.raijin.app.audio.WhisperStore(context) }
                            var whisperOn by remember { mutableStateOf(whisperStore.enabled) }
                            com.raijin.app.ui.prefs.OnDeviceSpeechCard(
                                enabled = whisperOn,
                                available = remember { com.raijin.app.audio.WhisperEngine.isAvailable(context) },
                                onEnabled = { whisperOn = it; whisperStore.enabled = it },
                            )
                            Spacer(Modifier.height(16.dp))
                            com.raijin.app.ui.prefs.ContactHistoryCard(
                                enabled = historyOn,
                                permissionGranted = historyGranted,
                                onEnabled = { historyOn = it; voiceStore.useContactHistory = it },
                                onGrantPermission = { historyLauncher.launch(android.Manifest.permission.READ_CALL_LOG) },
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // ME-5: preferences card
                        run {
                            val prefsStore = remember { com.raijin.app.prefs.UserPreferencesStore(context) }
                            var prefLanguage by remember { mutableStateOf(prefsStore.language) }
                            var prefSpeed by remember { mutableStateOf(prefsStore.ttsSpeed) }
                            var prefStrict by remember { mutableStateOf(prefsStore.confirmationStrictness) }
                            var prefQuietOn by remember { mutableStateOf(prefsStore.quietHoursEnabled) }
                            var prefQuietStart by remember { mutableStateOf(prefsStore.quietHoursStart) }
                            var prefQuietEnd by remember { mutableStateOf(prefsStore.quietHoursEnd) }
                            var prefPaths by remember { mutableStateOf(prefsStore.protectedPaths) }
                            var prefVoice by remember { mutableStateOf(prefsStore.ttsVoice) }
                            var prefProviders by remember { mutableStateOf(prefsStore.preferredProviders) }
                            val providerRegistryStore = remember {
                                com.raijin.app.cloud.ProviderRegistryStore(context)
                            }
                            // Display order: preferred ids first (in order), then the
                            // remaining registered providers in their stored order.
                            val orderedProviderIds = run {
                                val registered = providerRegistryStore.listProviders().map { it.first.id }
                                (prefProviders.filter { it in registered } + registered.filter { it !in prefProviders })
                            }
                            com.raijin.app.ui.prefs.PreferencesCard(
                                language = prefLanguage,
                                onLanguage = { prefLanguage = it; prefsStore.language = it },
                                ttsSpeed = prefSpeed,
                                onTtsSpeed = { prefSpeed = it; prefsStore.ttsSpeed = it },
                                strictness = prefStrict,
                                onStrictness = { prefStrict = it; prefsStore.confirmationStrictness = it },
                                quietEnabled = prefQuietOn,
                                onQuietEnabled = { prefQuietOn = it; prefsStore.quietHoursEnabled = it },
                                quietStart = prefQuietStart,
                                quietEnd = prefQuietEnd,
                                onQuietStart = { prefQuietStart = it; prefsStore.quietHoursStart = it },
                                onQuietEnd = { prefQuietEnd = it; prefsStore.quietHoursEnd = it },
                                protectedPaths = prefPaths,
                                onRemoveProtectedPath = { path ->
                                    prefPaths = prefPaths - path
                                    prefsStore.protectedPaths = prefPaths
                                },
                                onAddProtectedPath = { path ->
                                    prefPaths = prefPaths + path
                                    prefsStore.protectedPaths = prefPaths
                                },
                                ttsVoice = prefVoice,
                                onTtsVoice = { prefVoice = it; prefsStore.ttsVoice = it },
                                providerIds = orderedProviderIds,
                                preferredProviders = prefProviders,
                                onMoveProvider = { id, delta ->
                                    val idx = orderedProviderIds.indexOf(id)
                                    val target = idx + delta
                                    if (idx >= 0 && target in orderedProviderIds.indices) {
                                        // Persist the full displayed order as the new preference:
                                        // it keeps unknown/future providers appended after.
                                        val reordered = orderedProviderIds.toMutableList().apply {
                                            add(target, removeAt(idx))
                                        }
                                        prefProviders = reordered
                                        prefsStore.preferredProviders = reordered
                                    }
                                },
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Custom Tactical Skills Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = RaijinSurface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("⚡ INSTALLED TACTICAL SKILLS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RaijinGold)

                                if (installedSkills.isEmpty()) {
                                    Text("No custom skills installed. Say 'learn skill: when I say workout, clean storage' to author one.", fontSize = 11.sp, color = RaijinTextDim)
                                } else {
                                    installedSkills.forEach { skill ->
                                        com.raijin.app.ui.skills.LearnedSkillRow(
                                            skill = skill,
                                            onEdit = { editingLearnedSkill = skill },
                                            onDelete = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    RaijinDatabase.getInstance(context).skillDao().deleteById(skill.id)
                                                    refreshDatabaseData()
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // SK-4: edit dialog for learned reflexes
                        editingLearnedSkill?.let { skill ->
                            com.raijin.app.ui.skills.LearnedSkillEditDialog(
                                skill = skill,
                                onSave = { name, pattern ->
                                    coroutineScope.launch(Dispatchers.IO) {
                                                        RaijinDatabase.getInstance(context).skillDao()
                                                            .update(skill.copy(name = name, pattern = pattern))
                                        editingLearnedSkill = null
                                        refreshDatabaseData()
                                    }
                                },
                                onDismiss = { editingLearnedSkill = null },
                            )
                        }

                        // SK-2 declarative skills: install + approval + manage
                        DeclarativeSkillsSection(
                            installService = remember {
                                SkillInstallService(RaijinDatabase.getInstance(context))
                            },
                            runLogDao = remember {
                                RaijinDatabase.getInstance(context).skillRunLogDao()
                            },
                        )

                        Spacer(Modifier.height(32.dp))
                    }
                }

                DeckTab.PERMISSIONS -> {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = RaijinSurface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("SYSTEM PERMISSIONS PERIMETER", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = RaijinTextDim, fontWeight = FontWeight.SemiBold)
                                PermissionRow("Overlay Floating HUD", overlayGranted) { onRequestOverlay() }
                                PermissionRow("Audio & Mic", hasAudio) { onRequestPermissions() }
                                PermissionRow("Contacts Directory", hasContacts) { onRequestPermissions() }
                                PermissionRow("Phone Calling", hasCall) { onRequestPermissions() }
                                PermissionRow("SMS Messaging", hasSms) { onRequestPermissions() }
                                PermissionRow("Notification Listener", true) {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                            }
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (selected) RaijinBlue else RaijinBlack,
        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(if (selected) RaijinBlue else RaijinBorderDim)),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (selected) RaijinBlack else RaijinWhite)
        }
    }
}

@Composable
private fun TacticalChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = RaijinSurface,
        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(RaijinBorderDim)),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
        ) {
            Text(
                text = label,
                color = RaijinWhite,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PermissionRow(name: String, granted: Boolean, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            color = RaijinWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        TextButton(onClick = onAction) {
            Text(
                text = if (granted) "ACTIVE ✓" else "GRANT ➜",
                color = if (granted) RaijinGreen else RaijinGold,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
