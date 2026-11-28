package com.raijin.app

import android.app.Notification
import android.content.pm.ServiceInfo
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.StateFlow
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.raijin.app.actuator.AndroidActuator
import com.raijin.app.audio.RaijinTTS
import com.raijin.app.audio.STTEngine
import com.raijin.app.bridge.NotificationListenerService
import com.raijin.app.cloud.OpenAiCompatibleClient
import com.raijin.app.cloud.OpenAiConfigStore
import com.raijin.app.hud.HudOverlay
import com.raijin.app.memory.MemoryFactEntity
import com.raijin.app.memory.RoomRaijinMemoryAdapter
import com.raijin.app.morning.MorningBriefTrigger
import com.raijin.app.permissions.ActionExecutor
import com.raijin.app.permissions.PermissionBroker
import com.raijin.app.search.WebSearchClient
import com.raijin.app.skill.AppSkillRuntime
import com.raijin.app.skill.SkillEngine
import com.raijin.app.skill.SkillInstallService
import com.raijin.app.skill.SkillRunner
import com.raijin.app.ui.island.IslandTapPolicy
import com.raijin.app.ui.island.IslandState
import com.raijin.app.ui.island.TraceLine
import io.raijin.brain.RaijinBrain
import io.raijin.contract.ActionRisk
import io.raijin.contract.CapabilitySnapshot
import io.raijin.contract.InputKind
import io.raijin.contract.IslandVisualState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Native, standalone Raijin foreground service.
 *
 * Runs the complete in-process AI assistant pipeline:
 * - Deterministic FastBrain + Room-backed Memory + Dynamic Skills Engine
 * - Natural Language Memory Learning & Recall ("remember that...", "what is my...")
 * - Hermes-Style Dynamic Skill Authoring ("learn skill: when I say...")
 * - Real Android Actuators (Calls, SMS, Music, Alarms, Storage)
 * - Tap-to-Talk Voice Interaction Loop ([STTEngine]); wake word returns in WS-C (PRD VO-4)
 * - Hardware Telemetry Sentinels (Battery critical & power connectivity)
 * - Provider-Neutral OpenAI-Compatible Cloud LLM escalation (NVIDIA NIM, Groq, OpenAI, Ollama)
 * - Autonomous background perception & Proactive Morning Briefing
 * - Real-time DuckDuckGo / Wikipedia Web Research
 * - Drives the floating HUD overlay ([HudOverlay])
 */
class RaijinService : Service() {

    companion object {
        /** Voice answers accepted as consent in strict confirmation mode. */
        private val AFFIRMATIVE_ANSWERS = setOf("yes", "ok", "okay", "sure", "allow", "confirm", "haan", "हाँ", "हां", "han")

        private const val TAG      = "RaijinService"
        private const val NOTIF_ID = 1
        private const val WAKE_TAG = "raijin:active"
        private const val VOICE_WATCHDOG_MS = 12_000L
        // Whisper: up to 8 s waiting for speech, 20 s max speech, then CPU transcription.
        private const val WHISPER_WATCHDOG_MS = 45_000L

        /** Start/stop actions for external control */
        const val ACTION_START          = "com.raijin.app.ACTION_START"
        const val ACTION_STOP           = "com.raijin.app.ACTION_STOP"
        const val ACTION_TRIGGER_BRIEF  = "com.raijin.app.ACTION_TRIGGER_BRIEF"
        const val ACTION_VOICE_CAPTURE  = "com.raijin.app.ACTION_VOICE_CAPTURE"

        @Volatile
        var instance: RaijinService? = null
            private set

        @Volatile
        var brainInstance: RaijinBrain? = null
            private set

        @Volatile
        var skillEngineInstance: SkillEngine? = null
            private set

        private val _globalIslandState = MutableStateFlow<IslandState>(IslandState.Idle)
        val globalIslandState: StateFlow<IslandState> = _globalIslandState.asStateFlow()

        /**
         * Dispatches an utterance directly to the active assistant.
         */
        fun dispatchUtterance(text: String, traceId: String = UUID.randomUUID().toString()) {
            val svc = instance ?: return
            svc.handleUserUtterance(text, traceId)
        }

        /**
         * Starts a single-shot voice capture session on demand.
         */
        fun startVoiceListening() {
            val svc = instance ?: return
            svc.startVoiceCapture()
        }

        /**
         * Updates vertical positioning of floating HUD overlay.
         */
        fun updateHudOffset(offsetDp: Int) {
            val svc = instance ?: return
            svc.hudOverlay.updateVerticalOffset(offsetDp)
        }

        /**
         * Triggers a tactical morning briefing assembled from Room reminders and unread notifications.
         */
        fun triggerMorningBrief() {
            val svc = instance ?: return
            svc.runMorningBriefing()
        }

        /**
         * Relays a proactive perception event (e.g. from NotificationListenerService) to the HUD and TTS.
         */
        fun notifyProactiveEvent(title: String, category: String) {
            val svc = instance ?: return
            svc.handleProactiveEvent(title, category)
        }

        /** NT-3: queue a suggestion chip for a low-importance/low-confidence event. */
        fun notifyProactiveSuggestion(id: String, event: com.raijin.app.bridge.DetectedEvent) {
            val svc = instance ?: return
            svc.handleProactiveSuggestion(id, event)
        }

        /** TM-9: quiet island flash (no TTS), e.g. a conditional reminder cancelled on reply. */
        fun showIslandInfo(text: String) {
            val svc = instance ?: return
            svc.showQuietInfo(text)
        }

        private const val ACTION_PROACTIVE_SUGGESTION = "PROACTIVE_SUGGESTION"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var hudOverlay: HudOverlay
    private lateinit var brain: RaijinBrain
    private lateinit var actuator: AndroidActuator
    private lateinit var skillEngine: SkillEngine
    private lateinit var skillInstallService: SkillInstallService
    private lateinit var skillRunner: SkillRunner
    private lateinit var skillAuthoringService: com.raijin.app.skill.SkillAuthoringService
    private lateinit var skillAskBridge: com.raijin.app.skill.SkillAskBridge
    private var authoringCloud: (suspend (String, String?) -> String)? = null
    private lateinit var permissionBroker: PermissionBroker
    private lateinit var database: RaijinDatabase
    private lateinit var tts: RaijinTTS
    private lateinit var webSearchClient: WebSearchClient
    private lateinit var sttEngine: STTEngine
    private var morningReceiver: MorningBriefTrigger? = null
    private var telemetryReceiver: BroadcastReceiver? = null

    // Island state owned by the service; HudOverlay reads this as a StateFlow.
    private val _islandState = MutableStateFlow<IslandState>(IslandState.Idle)
    private val islandState  = _islandState.asStateFlow()

    // WakeLock — held only during active states
    private var wakeLock: PowerManager.WakeLock? = null

    // ── lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate — Initializing native Raijin Tactical Assistant")
        instance = this

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // VO-1 fix (island listens once): without the microphone FGS type,
                // Android 12+ cuts mic access as soon as the app leaves the foreground,
                // so only the first listen session after each app open (the grace
                // window) ever captures speech. Declaring the type keeps background
                // capture alive while this service runs. From a boot/background start
                // a mic-type FGS is forbidden (SecurityException): the fallback keeps
                // the service alive mic-less, and promoteMicFgsTypeIfEligible() adds
                // the type when MainActivity starts the service from the foreground.
                startForegroundWithTypes(includeMic = true)
            } else {
                startForeground(NOTIF_ID, buildNotification("Raijin Tactical HUD // Ready"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground with types failed: ${e.message}, falling back")
            try {
                startForeground(NOTIF_ID, buildNotification("Raijin Tactical HUD // Ready"))
            } catch (e2: Exception) {
                Log.e(TAG, "Fatal startForeground error: ${e2.message}", e2)
            }
        }

        serviceScope.launch {
            _islandState.collect { _globalIslandState.value = it }
        }

        // 1. Initialize core system adapters
        permissionBroker = PermissionBroker(this)
        val actionExecutor = ActionExecutor(this, serviceScope, permissionBroker)
        database = RaijinDatabase.getInstance(this)
        // ME-3: HOT-tier facts live for one session; a new session clears them.
        serviceScope.launch {
            runCatching { database.memoryDao().purgeHot() }
        }
        val memoryAdapter = RoomRaijinMemoryAdapter(database.memoryDao(), database.appAliasDao(), permissionBroker,
            com.raijin.app.contact.ContactResolver(
                permissionBroker, database.contactEntityDao(), database.correctionEntryDao(),
                historySignals = {
                    // CT-5: call-history ranking, on-device, owner-switchable in Settings.
                    if (com.raijin.app.prefs.UserPreferencesStore(applicationContext).useContactHistory)
                        com.raijin.app.contact.CallHistorySignals.load(applicationContext)
                    else emptyMap()
                },
            ))
        // ME-5: STRICT confirmation mode asks before reversible actions.
        // Strictness only ADDS asks; egress, install and dangerous-permission
        // consents are never relaxed by any strictness level.
        val userPrefsStore = com.raijin.app.prefs.UserPreferencesStore(this)
        actuator = AndroidActuator(this, permissionBroker, actionExecutor, database,
            confirmReversible = { actionType ->
                if (userPrefsStore.confirmationStrictness != com.raijin.app.prefs.UserPreferencesStore.ConfirmationStrictness.STRICT) {
                    true
                } else {
                    val answer = try {
                        askOwnerForSkill("Strict mode: allow ${actionType.name.lowercase().replace('_', ' ')}?", "", "Allow")
                    } catch (e: Exception) {
                        "" // timeout or cancel fails closed
                    }
                    answer.trim().lowercase() in AFFIRMATIVE_ANSWERS
                }
            },
        )
        tts = RaijinTTS(this)
        tts.addressProvider = { com.raijin.app.prefs.UserPreferencesStore(this).addressTerm }
        webSearchClient = WebSearchClient(this)
        sttEngine = STTEngine(this, serviceScope)

        // 2. Initialize provider registry + failover cloud bridge (PRD BR-2..BR-4).
        //    Falls back to the legacy single-provider config until the multi-provider
        //    settings UI lands, so Settings keeps working unchanged.
        val providerRegistry = com.raijin.app.cloud.ProviderRegistryStore(this)
        val cloudClient = com.raijin.app.cloud.FailoverCloudReasoningPort(providerRegistry)

        // 3. Instantiate in-process Brain & Skills Engine
        brain = RaijinBrain(
            memory = memoryAdapter,
            actuator = actuator,
            cloudReasoning = cloudClient,
            speakSentence = { sentence -> tts.speakQueued(sentence) },
            cloudContext = {
                val prefs = com.raijin.app.prefs.UserPreferencesStore(this)
                if (!prefs.shareContextWithCloud) {
                    ""
                } else {
                    val facts = database.memoryDao().getAll().filter { it.kind == "FACT" }.map { it.text }
                    val skills = runCatching { skillRunner.manifests() }.getOrDefault(emptyList())
                        .map { io.raijin.brain.CloudContext.Entry(it.name, it.description) }
                    io.raijin.brain.CloudContext.build(facts, prefs.addressTerm, skills)
                }
            },
        )
        brainInstance = brain

        skillEngine = SkillEngine(database.skillDao(), actuator)
        skillEngineInstance = skillEngine

        // SK-2: declarative installed skills (owner-approved manifests)
        skillInstallService = SkillInstallService(database)
        skillRunner = SkillRunner(skillInstallService)

        // SK-3: voice authoring + ask/consent channel
        skillAuthoringService = com.raijin.app.skill.SkillAuthoringService()
        skillAskBridge = com.raijin.app.skill.SkillAskBridge()
        authoringCloud = { prompt, system -> cloudClient.complete(prompt, system, 0.2f) }

        serviceScope.launch {
            skillEngine.seedDefaultsIfEmpty()
        }

        // Supply live capability snapshot
        brain.submitCapabilitySnapshot(
            CapabilitySnapshot(
                microphoneAvailable = permissionBroker.canRecordAudio(),
                contactsAvailable = permissionBroker.canReadContacts(),
                phoneCallAvailable = permissionBroker.canCallPhone(),
                storageAvailable = permissionBroker.canReadStorage(),
                networkAvailable = run {
                    val cm = getSystemService(android.net.ConnectivityManager::class.java)
                    cm?.activeNetwork != null
                },
                localCodingAvailable = false,
                cloudCodingEnabled = true,
                deviceRegionIso = run {
                    val sim = (getSystemService(TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)
                        ?.simCountryIso?.trim()?.uppercase()
                    if (!sim.isNullOrEmpty()) sim
                    else java.util.Locale.getDefault().country.trim().uppercase().takeIf { it.length == 2 }
                },
            ),
        )

        // 4. Attach HUD Overlay
        hudOverlay = HudOverlay(this)
        hudOverlay.attach(
            stateFlow = islandState,
            onTap     = ::onIslandTap,
            onAction  = ::onIslandAction,
            onDismiss = { _islandState.value = IslandState.Idle },
            onLongPress = ::openTypeWindow,
        )

        // 5. Connect Brain visual state stream directly to HUD
        brain.islandState
            .onEach { visualState ->
                val uiState = mapVisualStateToUi(visualState)
                _islandState.value = uiState
                manageWakeLock(uiState)

                // UI-7: a clarifying question is spoken, then the mic reopens for 6 s
                // with no tap. Confirmations/consents never auto-listen.
                if (visualState is IslandVisualState.NeedsInput &&
                    io.raijin.brain.FollowUpPolicy.shouldListen(visualState.kind)
                ) {
                    askAndListen(visualState.traceId, io.raijin.brain.FollowUpPolicy.spokenPrompt(visualState.prompt, visualState.options))
                }

                // Vocalize conclusion if present
                if (visualState is IslandVisualState.Done && visualState.feedbackLine.isNotBlank() &&
                    !visualState.spokenAlready
                ) {
                    tts.speak(visualState.feedbackLine)
                }

                // TM-7: the undo chip replaces Done, so it keeps the spoken confirmation.
                if (visualState is IslandVisualState.Undoable && visualState.feedbackLine.isNotBlank()) {
                    tts.speak(visualState.feedbackLine)
                }

                // PRD UI-3: never silent. Every Failed state is also spoken, one short line.
                if (visualState is IslandVisualState.Failed && visualState.errorReason.isNotBlank()) {
                    tts.speak(visualState.errorReason)
                }

                if (uiState is IslandState.Done || uiState is IslandState.Failed || uiState is IslandState.Undoable) {
                    val delayMs = when (uiState) {
                        is IslandState.Done   -> uiState.autoHideMs
                        is IslandState.Failed -> uiState.autoHideMs
                        // TM-7: the chip lives exactly as long as the undo offer.
                        is IslandState.Undoable -> uiState.windowMs
                        else -> 2500L
                    }
                    serviceScope.launch {
                        delay(delayMs)
                        if (_islandState.value === uiState) {
                            _islandState.value = IslandState.Idle
                            manageWakeLock(IslandState.Idle)
                        }
                    }
                }
            }
            .launchIn(serviceScope)

        // 6. Register dynamic screen-on receiver for morning brief
        try {
            val trigger = MorningBriefTrigger()
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            registerReceiver(trigger, filter)
            morningReceiver = trigger
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering MorningBriefTrigger: ${e.message}")
        }

        // 7. Register hardware telemetry receiver (Battery & Charging)
        try {
            val telFilter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            val telReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_BATTERY_LOW -> {
                            _islandState.value = IslandState.Acting("Suit Power Telemetry", "Battery critical below 15%")
                            tts.speak("Boss, device battery is down to 15%. Recommend docking to power.")
                        }
                        Intent.ACTION_POWER_CONNECTED -> {
                            _islandState.value = IslandState.Done("Power Grid Online // Charging")
                            tts.speak("Power connected. Charging systems engaged.")
                        }
                        Intent.ACTION_POWER_DISCONNECTED -> {
                            _islandState.value = IslandState.Idle
                        }
                    }
                }
            }
            registerReceiver(telReceiver, telFilter)
            telemetryReceiver = telReceiver
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering TelemetryReceiver: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                stopSelf()
            }
            ACTION_TRIGGER_BRIEF -> {
                runMorningBriefing()
            }
            ACTION_VOICE_CAPTURE -> {
                startVoiceCapture()
            }
            else -> {
                // MainActivity start path (no action): app is foreground, so this
                // is the safe point to promote a boot-fallback service to mic type.
                promoteMicFgsTypeIfEligible()
                // Wake word ("hey <name>"): idle unless the owner turned it on in Settings.
                if (wakeListener == null) {
                    wakeListener = com.raijin.app.wake.WakeWordListener(
                        this,
                        isBusy = { _islandState.value !is IslandState.Idle },
                        onWake = { startVoiceCapture() },
                    )
                }
                wakeListener?.start()
            }
        }
        return START_STICKY
    }

    private var wakeListener: com.raijin.app.wake.WakeWordListener? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeListener?.stop()
        Log.i(TAG, "onDestroy")
        morningReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        telemetryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        sttEngine.stop()
        _globalIslandState.value = IslandState.Idle
        instance = null
        brainInstance = null
        skillEngineInstance = null
        serviceScope.cancel()
        tts.shutdown()
        hudOverlay.detach()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Voice Interaction Loop ──────────────────────────────────────────────

    /**
     * UI-7: speak a clarifying question, then listen once for
     * [io.raijin.brain.FollowUpPolicy.WINDOW_MS]. The question card stays on
     * screen (tap still works); silence just ends the listen.
     */
    private fun askAndListen(traceId: String, spoken: String) {
        if (!permissionBroker.canRecordAudio()) {
            tts.speak(spoken)
            return
        }
        tts.speak(spoken) {
            val stillAsking = (_islandState.value as? IslandState.NeedsInput)?.traceId == traceId
            if (!stillAsking) return@speak
            val windowJob = serviceScope.launch {
                delay(io.raijin.brain.FollowUpPolicy.WINDOW_MS)
                sttEngine.stop()
            }
            sttEngine.startListening(
                onResult = { text ->
                    windowJob.cancel()
                    val stillOpen = (_islandState.value as? IslandState.NeedsInput)?.traceId == traceId
                    if (text.isNotBlank() && stillOpen) {
                        serviceScope.launch {
                            try { brain.resolveClarification(traceId, text.trim()) }
                            catch (e: Exception) { Log.w(TAG, "Follow-up answer failed: ${e.message}") }
                        }
                    }
                },
                onError = { windowJob.cancel() },
            )
        }
    }

    /** UI-6: open the floating "Type to Raijin" window. */
    private fun openTypeWindow() {
        startActivity(
            Intent(this, TypeToRaijinActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun startVoiceCapture() {
        if (!permissionBroker.canRecordAudio()) {
            _islandState.value = IslandState.Failed("Microphone permission required")
            // UI-3: every Failed is spoken.
            tts.speak("I need the microphone permission to hear you, boss.")
            val i = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(i)
            return
        }

        _islandState.value = IslandState.Listening
        manageWakeLock(IslandState.Listening)

        // Watchdog: if the recognizer never calls back (OEM edge), the island
        // must not sit in a false Listening state forever.
        val watchdogMs = if (sttEngine.usesWhisper()) WHISPER_WATCHDOG_MS else VOICE_WATCHDOG_MS
        serviceScope.launch {
            delay(watchdogMs)
            if (_islandState.value is IslandState.Listening) {
                Log.w(TAG, "Voice capture watchdog: still Listening after ${watchdogMs}ms")
                sttEngine.stop()
                val msg = "Listening timed out with no result. Try again."
                _islandState.value = IslandState.Failed(msg)
                tts.speak(msg)
                delay(4000L)
                if (_islandState.value is IslandState.Failed) {
                    _islandState.value = IslandState.Idle
                    manageWakeLock(IslandState.Idle)
                }
            }
        }

        sttEngine.startListening(
            onResult = { text ->
                if (text.isNotBlank()) {
                    handleUserUtterance(text, UUID.randomUUID().toString())
                } else {
                    _islandState.value = IslandState.Idle
                    manageWakeLock(IslandState.Idle)
                }
            },
            onAlternatives = { alternatives ->
                val clean = alternatives.filter { it.isNotBlank() }
                if (clean.isNotEmpty()) handleUserUtterance(clean.first(), UUID.randomUUID().toString(), clean)
            },
            onPartial = { partial ->
                Log.d(TAG, "STT partial: $partial")
            },
            onError = { err ->
                Log.w(TAG, "STT recognition error: $err")
                // PRD UI-3: never silent. Every failure shows and speaks its real reason.
                val reason = com.raijin.app.audio.SttFailure.describe(err)
                _islandState.value = IslandState.Failed(reason.text)
                tts.speak(reason.text)
                manageWakeLock(IslandState.Failed(reason.text))
                if (reason.needsMainActivity) {
                    val i = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    runCatching { startActivity(i) }
                }
                serviceScope.launch {
                    delay(5000L)
                    if (_islandState.value is IslandState.Failed) {
                        _islandState.value = IslandState.Idle
                        manageWakeLock(IslandState.Idle)
                    }
                }
            }
        )
    }

    // ── Command Processing ──────────────────────────────────────────────────

    fun handleUserUtterance(text: String, traceId: String, alternatives: List<String> = listOf(text)) {
        serviceScope.launch {
            Log.d(TAG, "Processing utterance: '$text' [traceId: $traceId]")
            val trimmed = text.trim()

            // STT phase 0: decide which recognizer hypothesis the intent probes
            // interpret. The selection is a PROPOSED interpretation only — every
            // branch below re-derives its arguments from the chosen text and the
            // normal confirmation/egress gates still apply. The raw primary is
            // preserved: pending skill answers (0a) always see it, and any
            // substitution is marked on the island and in the log.
            val learnRe = Regex("""^(?:learn\s+skill|create\s+skill)\s*(?:named\s+([^:]+))?:\s*when\s+i\s+say\s+([^,]+),\s*(.+)$""", RegexOption.IGNORE_CASE)
            val selection = run {
                val manifests = runCatching { skillRunner.manifests() }.getOrDefault(emptyList())
                val legacyPatterns = runCatching { skillEngine.skillPatterns() }.getOrDefault(emptyList())
                io.raijin.brain.IntentAlternativeResolver(
                    listOf(
                        io.raijin.brain.IntentAlternativeResolver.Probe("skill_author") {
                            com.raijin.app.skill.SkillAuthoringService.extractSpec(it) != null
                        },
                        io.raijin.brain.IntentAlternativeResolver.Probe("declarative_skill") { u ->
                            manifests.any { io.raijin.brain.skills.SkillMatcher.matches(it, u) }
                        },
                        io.raijin.brain.IntentAlternativeResolver.Probe("legacy_skill") { u ->
                            legacyPatterns.any { p ->
                                runCatching { Regex(p, RegexOption.IGNORE_CASE).containsMatchIn(u) }
                                    .getOrDefault(false)
                            }
                        },
                        io.raijin.brain.IntentAlternativeResolver.Probe("remember") { io.raijin.brain.MemoryRecall.extractFact(it) != null || io.raijin.brain.AddressForm.parse(it) != null },
                        io.raijin.brain.IntentAlternativeResolver.Probe("recall") { io.raijin.brain.MemoryRecall.extractQuery(it) != null },
                        io.raijin.brain.IntentAlternativeResolver.Probe("learn_skill") { learnRe.containsMatchIn(it) },
                        io.raijin.brain.IntentAlternativeResolver.Probe("search") { isSearchQuery(it) },
                        // OB-3: "write a note …" — the user's write/right path.
                        io.raijin.brain.IntentAlternativeResolver.Probe("obsidian_note") {
                            io.raijin.brain.NoteTriggerParser.matches(it)
                        },
                    ),
                ).select(alternatives)
            }
            val effective = selection?.chosen ?: trimmed
            if (selection != null && selection.substituted) {
                // No transcript content in logcat: dictated notes/identifiers are
                // sensitive. Decision metadata only; the content lives in the
                // user-visible island line and the run record, not in logs.
                Log.i(TAG, "STT hypothesis substitution applied (probe=${selection.probeId}, alternatives=${alternatives.size})")
                _islandState.value = IslandState.Thinking("Heard '${selection.primary}' - using '${selection.chosen}'")
            }

            // 0a. A skill question is pending: this utterance IS the answer.
            if (skillAskBridge.isAwaiting && skillAskBridge.tryAnswer(trimmed)) {
                _islandState.value = IslandState.Thinking("Resuming skill…")
                return@launch
            }

            // 0b. SK-3 voice authoring: "make a skill: <spec>". Pre-send
            // consent happens inside before ANY cloud request.
            com.raijin.app.skill.SkillAuthoringService.extractSpec(effective)?.let { spec ->
                recordInteraction("skill_author")
                handleSkillAuthoring(spec, traceId)
                return@launch
            }

            // 1a. Declarative installed skills (SK-2): owner-approved manifests
            // run through the brain-core interpreter on the real actuator.
            val declarative = skillRunner.tryRun(
                effective,
                AppSkillRuntime(
                    actuator = actuator,
                    traceId = traceId,
                    speakLine = { line -> tts.speak(line) },
                    askHandler = { prompt -> askOwnerForSkill(prompt, traceId, "Answer") },
                ),
                logUtterance = if (selection?.substituted == true) {
                    // Raw and interpreted kept together in the existing run
                    // record (same store, same retention) so an owner reviewing
                    // it sees what was heard AND what was interpreted.
                    "heard: ${selection.primary} | interpreted: ${selection.chosen}"
                } else {
                    null
                },
            )
            if (declarative != null) {
                recordInteraction("skill_run")
                val vocal = when (declarative) {
                    is io.raijin.brain.skills.SkillRunResult.Completed -> "Done, boss."
                    is io.raijin.brain.skills.SkillRunResult.FailedAt ->
                        "Skill stopped: ${declarative.reason}"
                }
                _islandState.value = IslandState.Done(vocal)
                tts.speak(vocal)
                return@launch
            }

            // 1b. Legacy regex skills
            val skillResult = skillEngine.tryExecuteSkill(effective, traceId)
            if (skillResult != null) {
                recordInteraction("legacy_skill")
                val vocal = skillResult.vocalResponse ?: "Skill executed"
                _islandState.value = IslandState.Done(vocal)
                tts.speak(vocal)
                return@launch
            }

            // 2a. Voice self-edit: only the editable layer (form of address, speech speed, on-device
            // voice, undo). Core, permissions and what leaves the phone are refused (SelfEdit policy).
            run {
                val remembered = io.raijin.brain.MemoryRecall.extractFact(effective)
                val awaiting = com.raijin.app.prefs.UserPreferencesStore(this@RaijinService).pendingGenderForName != null
                val cmd = io.raijin.brain.SelfEdit.parse(remembered ?: effective, remembered = remembered != null, awaitingGender = awaiting)
                if (cmd != null) {
                    recordInteraction("self_edit")
                    val msg = com.raijin.app.selfedit.SelfEditExecutor(this@RaijinService, database.memoryDao())
                        .apply(cmd, (remembered ?: effective))
                    _islandState.value = IslandState.Done(msg)
                    tts.speak(msg)
                    delay(2500L)
                    if (_islandState.value is IslandState.Done) _islandState.value = IslandState.Idle
                    return@launch
                }
            }

            // 2. Natural Language Memory Learning: "remember that <fact>"
            val factToRemember = io.raijin.brain.MemoryRecall.extractFact(effective)
            if (factToRemember != null) {
                val fact = factToRemember
                recordInteraction("remember_fact")
                database.memoryDao().insert(
                    MemoryFactEntity(
                        kind = "FACT",
                        text = fact,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                val feedback = "Recorded to memory: $fact"
                _islandState.value = IslandState.Done(feedback)
                tts.speak("Got it, boss. I'll remember that.")
                delay(2500L)
                if (_islandState.value is IslandState.Done) _islandState.value = IslandState.Idle
                return@launch
            }

            // 3. Memory recall, answered on the phone before any cloud call: "what is my name",
            //    "who am I", "do you remember my bike". Nothing is sent anywhere.
            val recallQuery = io.raijin.brain.MemoryRecall.extractQuery(effective)
            if (recallQuery != null) {
                val keywords = io.raijin.brain.MemoryRecall.keywords(recallQuery)
                val found = keywords.flatMap { database.memoryDao().search(it, 10) }
                    .distinctBy { it.id }
                    .map { it.text }
                val best = io.raijin.brain.MemoryRecall.rank(found, keywords).firstOrNull()
                if (best != null) {
                    recordInteraction("recall_memory")
                    val answer = io.raijin.brain.MemoryRecall.phrase(best)
                    _islandState.value = IslandState.Done(answer)
                    tts.speak(answer)
                    delay(4000L)
                    if (_islandState.value is IslandState.Done) _islandState.value = IslandState.Idle
                    return@launch
                }
            }

            // 4. Hermes-Style Dynamic Skill Authoring: "learn skill: when I say <phrase>, <action>"
            val skillCreateMatch = learnRe.find(effective)
            if (skillCreateMatch != null) {
                val givenName = skillCreateMatch.groupValues[1].trim().ifBlank { "Custom Protocol" }
                val triggerPhrase = skillCreateMatch.groupValues[2].trim()
                val actionDesc = skillCreateMatch.groupValues[3].trim().lowercase()

                val actionType = com.raijin.app.skill.SkillActionMapper.forDescription(actionDesc)

                // PRD 2.2.1: fail closed. An unrecognised action description must
                // never fall back to a destructive action like DELETE_FILES.
                if (actionType == null) {
                    val refusal = "I can't turn '$actionDesc' into a safe action yet, so I did not save that skill."
                    _islandState.value = IslandState.Failed(refusal)
                    tts.speak(refusal)
                    delay(4000L)
                    if (_islandState.value is IslandState.Failed) _islandState.value = IslandState.Idle
                    return@launch
                }

                recordInteraction("learn_skill")
                skillEngine.installSkill(
                    name = givenName,
                    pattern = "^(?:initiate )?" + Regex.escape(triggerPhrase) + "$",
                    actionType = actionType,
                    description = "User-authored protocol: $actionDesc",
                )

                val feedback = "Tactical protocol '$givenName' registered for '$triggerPhrase'."
                _islandState.value = IslandState.Done(feedback)
                tts.speak("Tactical skill registered, boss. Say $triggerPhrase to execute.")
                delay(3000L)
                if (_islandState.value is IslandState.Done) _islandState.value = IslandState.Idle
                return@launch
            }

            // 4z. Obsidian: "what is inside my folder" is answered on the phone from the
            //     selected vault, before the web-research reflex can take "what is ...".
            if (io.raijin.brain.VaultQueryParser.matches(effective)) {
                recordInteraction("obsidian_query")
                handleVaultQuery(traceId)
                return@launch
            }

            // 5. Direct Web Research reflex: "search for...", "who is...", "what is..."
            if (isSearchQuery(effective)) {
                recordInteraction("web_search")
                val query = extractSearchQuery(effective)
                _islandState.value = IslandState.Thinking("Researching $query…")
                val searchResult = webSearchClient.research(query)
                _islandState.value = IslandState.Done(searchResult.summary)
                tts.speak(searchResult.summary)
                delay(4000L)
                if (_islandState.value is IslandState.Done) {
                    _islandState.value = IslandState.Idle
                }
                return@launch
            }

            // 5b. OB-3: Obsidian note request — consent, draft, review, write-on-yes.
            io.raijin.brain.NoteTriggerParser.extract(effective)?.let { req ->
                recordInteraction("obsidian_note")
                handleNoteRequest(req, selection?.primary ?: trimmed, traceId)
                return@launch
            }
            if (io.raijin.brain.NoteTriggerParser.isBare(effective)) {
                recordInteraction("obsidian_note")
                val said = try {
                    askOwnerForSkill("What should the note say?", traceId, "Use this")
                } catch (e: com.raijin.app.skill.SkillAskBridge.AskCancelledException) { null }
                if (said.isNullOrBlank()) {
                    _islandState.value = IslandState.Done("No note")
                    tts.speak("Okay, no note.")
                    return@launch
                }
                handleNoteRequest(io.raijin.brain.NoteTriggerParser.NoteRequest(said.trim()), said.trim(), traceId)
                return@launch
            }

            // 6. Process via FastBrain / CloudBrain
            brain.processAlternatives(alternatives, traceId)
        }
    }

    /** ME-3: record a structured interaction signature for the pattern miner. Never the raw utterance. */
    private suspend fun recordInteraction(signature: String) {
        runCatching {
            database.interactionLogDao().insert(
                com.raijin.app.memory.InteractionLogEntity(signature = signature)
            )
        }.onFailure { Log.w(TAG, "interaction log write failed: ${it.javaClass.simpleName}") }
    }

    private fun isSearchQuery(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("search ") ||
               lower.startsWith("search for ") ||
               lower.startsWith("research ") ||
               lower.startsWith("who is ") ||
               lower.startsWith("what is ") ||
               lower.startsWith("look up ")
    }

    private fun extractSearchQuery(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.startsWith("search for ") -> text.substring("search for ".length).trim()
            lower.startsWith("search ")     -> text.substring("search ".length).trim()
            lower.startsWith("research ")   -> text.substring("research ".length).trim()
            lower.startsWith("look up ")    -> text.substring("look up ".length).trim()
            else -> text
        }
    }

    // ── Proactive Sentinel & Morning Briefing ────────────────────────────────

    /** TM-9: silent Done flash, no TTS (conditional reminder cancelled). */
    private fun showQuietInfo(text: String) {
        serviceScope.launch {
            _islandState.value = IslandState.Done(text)
            delay(2500L)
            if (_islandState.value is IslandState.Done) {
                _islandState.value = IslandState.Idle
            }
        }
    }

    private fun handleProactiveEvent(title: String, category: String) {
        // ME-5: quiet hours silence proactive speaking.
        val hour = java.time.ZonedDateTime.now().hour
        if (com.raijin.app.prefs.UserPreferencesStore(this).isQuietHour(hour)) return
        serviceScope.launch {
            _islandState.value = IslandState.Acting("Proactive Sentinel", "$category detected: $title")
            val speakText = "Boss, detected an upcoming $category in your notifications. Scheduled a reminder."
            tts.speak(speakText)
            delay(2800L)
            _islandState.value = IslandState.Done("Auto-scheduled: $title")
            delay(2500L)
            if (_islandState.value is IslandState.Done) {
                _islandState.value = IslandState.Idle
            }
        }
    }

    /**
     * NT-3/NT-4: a low-importance or low-confidence notification event shows
     * as a silent suggestion chip (no TTS: it was not important enough to
     * interrupt). Confirm creates the reminder; dismiss drops it. If another
     * suggestion is already queued, the next chip surfaces after this one
     * resolves.
     */
    private fun handleProactiveSuggestion(suggestionId: String, event: com.raijin.app.bridge.DetectedEvent) {
        serviceScope.launch {
            _islandState.value = IslandState.NeedsInput(
                label = "Reminder suggestion",
                question = event.title,
                context = "from your notifications",
                confirmLabel = "Remind me",
                cancelLabel = "Dismiss",
                isDangerous = false,
                traceId = suggestionId,
                actionId = ACTION_PROACTIVE_SUGGESTION,
            )
        }
    }

    private fun onProactiveSuggestionAction(suggestionId: String, payload: String) {
        serviceScope.launch {
            val accepted = payload.equals("confirm", ignoreCase = true) || payload.equals("true", ignoreCase = true)
            if (accepted) {
                val event = com.raijin.app.bridge.PendingSuggestions.store.accept(suggestionId)
                if (event != null) {
                    runCatching {
                        com.raijin.app.bridge.ProactiveReminderScheduler.schedule(
                            applicationContext, event, "notification",
                        )
                    }.onSuccess {
                        _islandState.value = IslandState.Done("Reminder set")
                        tts.speak("Done, I'll remind you.")
                    }.onFailure { e ->
                        Log.e(TAG, "Accepted suggestion failed to schedule: ${e.message}", e)
                        _islandState.value = IslandState.Failed("Couldn't set that reminder")
                        // UI-3: every Failed is spoken.
                        tts.speak("I couldn't set that reminder, boss.")
                    }
                } else {
                    _islandState.value = IslandState.Failed("That suggestion expired")
                    // UI-3: every Failed is spoken.
                    tts.speak("That suggestion expired, boss.")
                }
            } else {
                com.raijin.app.bridge.PendingSuggestions.store.dismiss(suggestionId)
                _islandState.value = IslandState.Idle
            }
            delay(2500L)
            // Surface the next queued suggestion, if any; otherwise settle.
            val next = com.raijin.app.bridge.PendingSuggestions.store.pendingIds().firstOrNull()
            if (next != null) {
                com.raijin.app.bridge.PendingSuggestions.store.peek(next)?.let { event ->
                    handleProactiveSuggestion(next, event)
                }
            } else if (_islandState.value is IslandState.Done) {
                _islandState.value = IslandState.Idle
            }
        }
    }

    private fun runMorningBriefing() {
        serviceScope.launch {
            val localNow = java.time.ZonedDateTime.now()
            val nowSec = localNow.toEpochSecond()
            val pendingReminders = runCatching { database.reminderDao().findPending(nowSec) }.getOrNull()
            val notificationAccess = androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(this@RaijinService).contains(packageName)
            val lines = mutableListOf<String>()
            lines.add(io.raijin.brain.BriefingText.greeting(localNow.hour) + ".")
            lines.add(when {
                pendingReminders == null -> "Reminders couldn't be checked."
                pendingReminders.isEmpty() -> "No pending reminders found."
                else -> "${pendingReminders.size} pending reminder(s)."
            })
            lines.add(io.raijin.brain.BriefingText.notificationDigest(
                notificationAccess,
                NotificationListenerService.listenerConnected,
                NotificationListenerService.cachedAppDigests,
            ))
            _islandState.value = IslandState.MorningBrief(lines)
            tts.speak(lines.joinToString(" "))
            delay(10_000L)
            if (_islandState.value is IslandState.MorningBrief) {
                _islandState.value = IslandState.Idle
            }
        }
    }

    // ── Island interaction callbacks ───────────────────────────────────────

    /**
     * Direct RECORD_AUDIO check used before [PermissionBroker] is initialized.
     */
    private fun canRecordAudioNow(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun baseFgsType(): Int {
        var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return fgsType
    }

    private fun startForegroundWithTypes(includeMic: Boolean) {
        var fgsType = baseFgsType()
        if (includeMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && canRecordAudioNow()) {
            fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIF_ID, buildNotification("Raijin Tactical HUD // Ready"), fgsType)
        com.raijin.app.audio.MicState.serviceHasMicType = (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
    }

    /**
     * Re-runs startForeground with the microphone type. Only safe from a
     * foreground start path (MainActivity): Android 12+ forbids starting a
     * mic-type FGS from boot/background contexts. Covers services that came
     * up via the boot fallback on API 31-33.
     */
    private fun promoteMicFgsTypeIfEligible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !canRecordAudioNow()) return
        try {
            startForegroundWithTypes(includeMic = true)
        } catch (e: Exception) {
            Log.w(TAG, "Mic FGS type promotion skipped: ${e.message}")
        }
    }

    // ── OB-3: Obsidian note flow ────────────────────────────────────────────

    /** Read-only, on-phone answer to "what is inside my Obsidian folder". Nothing is sent anywhere. */
    private suspend fun handleVaultQuery(traceId: String) {
        val fs = com.raijin.app.obsidian.ObsidianVaultStore(this).openFs()
        if (fs == null) {
            val msg = "Pick your vault folder in Raijin settings first, boss."
            _islandState.value = IslandState.Failed(msg)
            tts.speak(msg)
            return
        }
        val summary = try {
            val index = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.raijin.app.obsidian.VaultIndex.scan(fs)
            }
            io.raijin.brain.VaultQueryParser.summarize(index.folders, index.notes.keys.toList())
        } catch (e: Exception) {
            "I could not read the vault folder: ${e.javaClass.simpleName}."
        }
        _islandState.value = IslandState.Done(summary)
        tts.speak(summary)
        delay(6000L)
        if (_islandState.value is IslandState.Done) _islandState.value = IslandState.Idle
    }

    /**
     * "write a note …" — mirror of the SK-3 consent pattern:
     * 1. vault root must be owner-configured (asked + verified, never guessed);
     * 2. read-only vault scan;
     * 3. pre-send consent showing EXACTLY what leaves (NoteDraftConsent lines);
     * 4. cloud draft;
     * 5. island review: original words vs proposals (placement, append/create,
     *    resolved/unresolved links);
     * 6. writes only on yes, through WRITE_VAULT_NOTE (create-only / append).
     */
    private suspend fun handleNoteRequest(
        req: io.raijin.brain.NoteTriggerParser.NoteRequest,
        rawUtterance: String,
        traceId: String,
    ) {
        val cloud = authoringCloud
        if (cloud == null) {
            _islandState.value = IslandState.Failed("No cloud brain configured")
            tts.speak("I need a configured cloud brain to draft notes, boss.")
            return
        }

        // 1. Vault folder: picked by the owner in Settings (Android folder picker),
        //    never typed, spoken or guessed. Unset or access removed -> say so and stop.
        val vaultStore = com.raijin.app.obsidian.ObsidianVaultStore(this)
        val vaultFs = vaultStore.openFs()
        if (vaultFs == null) {
            val msg = "Pick your vault folder in Raijin settings first. Nothing was written."
            _islandState.value = IslandState.Failed(msg)
            tts.speak(msg)
            return
        }

        // 2. Read-only scan of the real vault structure.
        val index = try {
            com.raijin.app.obsidian.VaultIndex.scan(vaultFs)
        } catch (e: Exception) {
            val msg = "I could not read the vault folder: ${e.javaClass.simpleName}."
            _islandState.value = IslandState.Failed(msg)
            tts.speak(msg)
            return
        }

        // 3. Pre-send consent: the exact outbound set, verbatim.
        val endpoint = runCatching {
            java.net.URI(OpenAiConfigStore(this).load().baseUrl).host
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "your configured cloud"
        // What leaves is the extracted note text (from the hypothesis the
        // resolver chose), disclosed verbatim — not the raw trigger phrase.
        val outbound = io.raijin.brain.NoteDraftConsent.plan(
            provider = "CloudBrain",
            recipient = endpoint,
            utterance = req.body,
            folders = index.folders,
            titles = index.notes.keys.sorted(),
        )
        val sendConsent = try {
            askOwnerForSkill(
                outbound.disclosureLines().joinToString(" ") + " Say yes to send.",
                traceId,
                "Send",
            )
        } catch (e: com.raijin.app.skill.SkillAskBridge.AskCancelledException) { null }
        if (sendConsent == null || !io.raijin.brain.skills.ToolEgressPolicy.isConsent(sendConsent)) {
            _islandState.value = IslandState.Done("Nothing sent")
            tts.speak("Okay, nothing was sent.")
            return
        }

        // 4. Cloud draft.
        _islandState.value = IslandState.Thinking("Drafting notes…")
        val response = try {
            cloud(io.raijin.brain.NoteDraftPrompt.build(outbound), null)
        } catch (e: Exception) {
            _islandState.value = IslandState.Failed("Draft failed: ${e.javaClass.simpleName}")
            tts.speak("The draft request failed, boss. Nothing was written.")
            return
        }
        val drafts = io.raijin.brain.NoteDraftParser.parse(response)
        if (drafts.isEmpty()) {
            _islandState.value = IslandState.Failed("No usable draft")
            tts.speak("The drafter returned nothing I could use. Nothing was written.")
            return
        }

        // 5. Proposal planning + review: original words next to the proposals.
        val snapshot = io.raijin.brain.NoteProposalPlanner.VaultSnapshot(
            folders = index.folders,
            notes = index.notes,
            dailyNoteFilenames = index.conventions.dailyNoteFilenames,
            wikilinksUsed = index.conventions.wikilinksUsed,
        )
        val proposals = io.raijin.brain.NoteProposalPlanner.plan(snapshot, drafts)
        val review = buildString {
            append("You said: $rawUtterance. ")
            proposals.forEachIndexed { i, p ->
                append("Note ${i + 1}: '${p.title}' -> ${p.targetPath} (")
                append(if (p.append) "append to the existing note" else "new note")
                append("). ${p.reason} ")
                if (p.resolvedLinks.isNotEmpty()) {
                    append("Links to existing notes: ${p.resolvedLinks.joinToString(", ")}. ")
                }
                if (p.unresolvedLinks.isNotEmpty()) {
                    append("Not linked, no such note: ${p.unresolvedLinks.joinToString(", ")}. ")
                }
            }
            append("Write ${proposals.size} note${if (proposals.size == 1) "" else "s"}?")
        }
        val writeConsent = try {
            askOwnerForSkill(review, traceId, "Write")
        } catch (e: com.raijin.app.skill.SkillAskBridge.AskCancelledException) { null }
        if (writeConsent == null || !io.raijin.brain.skills.ToolEgressPolicy.isConsent(writeConsent)) {
            _islandState.value = IslandState.Done("Discarded")
            tts.speak("Okay, discarded. Nothing was written.")
            return
        }

        // 6. Write on yes. Link rendering is fail-closed: unresolved targets
        //    become plain text.
        var written = 0
        val failures = mutableListOf<String>()
        for (p in proposals) {
            val content = io.raijin.brain.NoteBodyRenderer.render(
                p.body, p.resolvedLinks, p.unresolvedLinks,
            )
            val result = actuator.execute(
                io.raijin.contract.DispatchResult(
                    traceId = traceId,
                    actionType = io.raijin.contract.ActionType.WRITE_VAULT_NOTE,
                    args = io.raijin.contract.ActionArgs.VaultNote(
                        relativePath = p.targetPath,
                        content = content,
                        append = p.append,
                    ),
                    confidence = 1.0f,
                    risk = io.raijin.contract.ActionRisk.LOW,
                ),
            )
            when (result) {
                is io.raijin.contract.ActionResult.EXECUTED -> written++
                is io.raijin.contract.ActionResult.DENIED -> failures += "${p.title}: denied (${result.reason})"
                is io.raijin.contract.ActionResult.FAILED -> failures += "${p.title}: failed (${result.reason})"
            }
        }
        val summary = when {
            failures.isEmpty() -> "Wrote $written note${if (written == 1) "" else "s"} to your vault, boss."
            written == 0 -> "No notes were written. ${failures.first()}"
            else -> "Wrote $written, ${failures.size} failed. ${failures.first()}"
        }
        _islandState.value = if (failures.isEmpty()) IslandState.Done(summary) else IslandState.Failed(summary)
        tts.speak(summary)
        delay(4000L)
        if (_islandState.value is IslandState.Done) {
            _islandState.value = IslandState.Idle
            manageWakeLock(IslandState.Idle)
        }
    }

    // ── SK-3: voice skill authoring + ask channel ─────────────────────────

    /**
     * Ask the owner through the island + TTS and suspend for the answer
     * (spoken utterance, island confirm, cancel or timeout). Used for
     * ask_user steps, egress consent and authoring consent.
     */
    private suspend fun askOwnerForSkill(prompt: String, traceId: String, confirmLabel: String): String =
        skillAskBridge.ask(prompt) { p ->
            _islandState.value = IslandState.NeedsInput(
                label = "Skill question",
                question = p,
                confirmLabel = confirmLabel,
                cancelLabel = "Stop",
                traceId = traceId,
                actionId = "SKILL_ASK",
            )
            tts.speak(p)
        }

    /**
     * "make a skill: <spec>" - the owner approves the EXACT redacted text
     * before any cloud request (pre-send consent), hears the dry-run
     * preview, then approves the install. The original spec is preserved
     * in the run log; only the redacted text ever leaves the device.
     */
    private suspend fun handleSkillAuthoring(spec: String, traceId: String) {
        val cloud = authoringCloud
        if (cloud == null) {
            _islandState.value = IslandState.Failed("No cloud brain configured")
            tts.speak("I need a configured cloud brain to draft skills, boss.")
            return
        }
        val prepared = skillAuthoringService.prepareSpec(spec)
        val endpoint = runCatching {
            java.net.URI(OpenAiConfigStore(this).load().baseUrl).host
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "your configured cloud"
        val changedNote = if (prepared.changed) " I hid a phone number or email first." else ""

        val sendConsent = try {
            askOwnerForSkill(
                "To draft this skill, I'll send this to $endpoint: ${prepared.redacted}.$changedNote Say yes to send.",
                traceId,
                "Send",
            )
        } catch (e: com.raijin.app.skill.SkillAskBridge.AskCancelledException) { null }
        if (sendConsent == null || !io.raijin.brain.skills.ToolEgressPolicy.isConsent(sendConsent)) {
            _islandState.value = IslandState.Done("Nothing sent")
            tts.speak("Okay, nothing was sent.")
            return
        }

        _islandState.value = IslandState.Thinking("Drafting skill…")
        when (val outcome = skillAuthoringService.draft(prepared) { p, sys -> cloud(p, sys) }) {
            is com.raijin.app.skill.SkillAuthoringService.DraftOutcome.Error -> {
                val reason = outcome.reasons.firstOrNull() ?: "draft failed"
                _islandState.value = IslandState.Failed(reason)
                tts.speak("I couldn't draft that skill: $reason")
            }
            is com.raijin.app.skill.SkillAuthoringService.DraftOutcome.Ok -> {
                val previewLines = when (val preview = outcome.preview) {
                    is io.raijin.brain.skills.SkillRunResult.Completed -> preview.log
                    is io.raijin.brain.skills.SkillRunResult.FailedAt ->
                        preview.log + "fails at step ${preview.atStep}: ${preview.reason}"
                }
                // Original words replayed next to the generated draft so the
                // owner can compare request vs result BEFORE install, not
                // after from a log.
                val installConsent = try {
                    askOwnerForSkill(
                        "You asked: ${outcome.originalSpec}. " +
                            "Preview of ${outcome.manifest.name}: ${previewLines.joinToString("; ")}. " +
                            "It runs when you say: ${outcome.manifest.triggers.phrases.joinToString(", ")}. Install it?",
                        traceId,
                        "Install",
                    )
                } catch (e: com.raijin.app.skill.SkillAskBridge.AskCancelledException) { null }
                if (installConsent == null || !io.raijin.brain.skills.ToolEgressPolicy.isConsent(installConsent)) {
                    _islandState.value = IslandState.Done("Discarded")
                    tts.speak("Okay, discarded. Nothing was installed.")
                    return
                }
                when (val plan = skillInstallService.planInstall(outcome.payloadJson, "voice")) {
                    is SkillInstallService.PlanOutcome.Ready -> {
                        val stored = skillInstallService.confirmInstall(plan)
                        if (stored) {
                            // Preserve the owner's original words locally; the
                            // draft itself was based only on the redacted text.
                            database.skillRunLogDao().insert(
                                com.raijin.app.skill.SkillRunLogEntity(
                                    skill_id = outcome.manifest.id,
                                    skill_version = outcome.manifest.version,
                                    utterance = outcome.originalSpec,
                                    outcome = "authored",
                                    detail = "cloud draft at $endpoint; redacted before send",
                                    ran_at = System.currentTimeMillis(),
                                )
                            )
                            _islandState.value = IslandState.Done("${outcome.manifest.name} installed")
                            tts.speak("Skill ${outcome.manifest.name} is installed, boss.")
                        } else {
                            _islandState.value = IslandState.Failed("Install failed")
                            tts.speak("The install failed; nothing was stored.")
                        }
                    }
                    is SkillInstallService.PlanOutcome.Rejected -> {
                        val reason = plan.reasons.firstOrNull() ?: "rejected"
                        _islandState.value = IslandState.Failed(reason)
                        tts.speak("The draft was rejected: $reason")
                    }
                }
            }
        }
    }

    private fun onIslandTap() {
        val cur = _islandState.value
        when (IslandTapPolicy.decide(cur, tts.isSpeaking)) {
            IslandTapPolicy.Decision.STOP_SPEECH -> {
                tts.stop()
                if (cur is IslandState.Done || cur is IslandState.Failed || cur is IslandState.MorningBrief) {
                    _islandState.value = IslandState.Idle
                    manageWakeLock(IslandState.Idle)
                }
            }
            IslandTapPolicy.Decision.START_LISTENING -> startVoiceCapture()
            IslandTapPolicy.Decision.STOP_LISTENING -> {
                sttEngine.stop()
                _islandState.value = IslandState.Idle
                manageWakeLock(IslandState.Idle)
            }
            IslandTapPolicy.Decision.DISMISS -> {
                _islandState.value = IslandState.Idle
                manageWakeLock(IslandState.Idle)
            }
            IslandTapPolicy.Decision.IGNORE -> Unit
        }
    }

    private fun onIslandAction(traceId: String, actionId: String, payload: String) {
        serviceScope.launch {
            try {
                when (actionId) {
                    "SKILL_ASK" -> {
                        if (payload.equals("cancel", ignoreCase = true)) skillAskBridge.cancel() else skillAskBridge.confirm()
                    }
                    ACTION_PROACTIVE_SUGGESTION -> onProactiveSuggestionAction(traceId, payload)
                    // TM-7: payload carries the one-shot undo token.
                    "UNDO" -> brain.resolveUndo(payload, accepted = true)
                    "CONFIRM" -> brain.resolveActionConfirmation(traceId, payload.equals("true", ignoreCase = true) || payload.equals("confirm", ignoreCase = true))
                    "CHOICE" -> brain.resolveChoice(traceId, payload)
                    "CANCEL" -> brain.cancelCurrentAction(traceId)
                    else -> brain.resolveActionConfirmation(traceId, payload.equals("true", ignoreCase = true) || payload.equals("confirm", ignoreCase = true))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed resolving action $actionId: ${e.message}")
            }
        }
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private fun mapVisualStateToUi(vs: IslandVisualState): IslandState = when (vs) {
        is IslandVisualState.Idle -> IslandState.Idle
        is IslandVisualState.Listening -> IslandState.Listening
        is IslandVisualState.Resolving -> IslandState.Resolving(
            label = vs.label ?: "Analyzing telemetry…",
            traceLines = vs.stepTrace.map { TraceLine(it) },
        )
        is IslandVisualState.Thinking -> IslandState.Thinking(label = vs.taskLabel)
        is IslandVisualState.Acting -> IslandState.Acting(label = vs.actionSummary)
        is IslandVisualState.NeedsInput -> IslandState.NeedsInput(
            label = vs.prompt,
            question = vs.prompt,
            traceId = vs.traceId,
            actionId = "CONFIRM",
            confirmLabel = vs.options.firstOrNull()?.label ?: "Execute",
            cancelLabel = "Abort",
            isDangerous = vs.risk == ActionRisk.HIGH,
            // CT-8: a countdown confirms itself; only the cancel control shows.
            confirmEnabled = vs.kind != InputKind.COUNTDOWN,
        )
        is IslandVisualState.Done ->
            // UI-5: long answers get the expandable card, not a one-line flash.
            if (vs.feedbackLine.length > 60) {
                IslandState.LongAnswer(text = vs.feedbackLine)
            } else {
                IslandState.Done(label = vs.feedbackLine)
            }
        is IslandVisualState.Failed -> IslandState.Failed(reason = vs.errorReason)
        is IslandVisualState.Undoable -> IslandState.Undoable(
            label = vs.feedbackLine,
            undoToken = vs.undoToken,
            windowMs = vs.windowMs,
            undoLabel = vs.undoLabel,
        )
        is IslandVisualState.Brief -> IslandState.MorningBrief(lines = vs.lines)
    }

    // ── wake lock management ───────────────────────────────────────────────

    private fun manageWakeLock(state: IslandState) {
        when (state) {
            is IslandState.Idle,
            is IslandState.Done,
            is IslandState.Undoable,
            is IslandState.LongAnswer,
            is IslandState.Failed -> releaseWakeLock()
            else                  -> acquireWakeLock()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).also {
            it.acquire(60_000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    // ── notification ───────────────────────────────────────────────────────

    private fun buildNotification(text: String = "Ready"): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RaijinService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val typeIntent = PendingIntent.getActivity(
            this, 4,
            Intent(this, TypeToRaijinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val voiceIntent = PendingIntent.getService(
            this, 3,
            Intent(this, RaijinService::class.java).setAction(ACTION_VOICE_CAPTURE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, RaijinApp.CHANNEL_ISLAND)
            .setContentTitle("Raijin Tactical HUD // Online")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(tapIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "Talk", voiceIntent)
            .addAction(android.R.drawable.ic_menu_edit, "Type", typeIntent)
            .addAction(android.R.drawable.ic_delete, "Stand Down", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
