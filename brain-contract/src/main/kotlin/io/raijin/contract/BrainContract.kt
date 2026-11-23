package io.raijin.contract

import kotlinx.coroutines.flow.StateFlow

enum class ProcessingMode {
    LOCAL_FAST,
    LOCAL_CODING,
    CLOUD_CODING,
    LOCAL_REASONING,
    CLOUD_GENERAL,
}

enum class ActionRisk {
    LOW,
    MEDIUM,
    HIGH,
}

enum class ActionType {
    NONE,
    CALL_CONTACT,
    SEND_MESSAGE,
    OPEN_APP,
    /** AP-3: open a specific screen in a known app via deep link (Maps search, WhatsApp chat). */
    OPEN_DEEP_LINK,
    SHARE_FILE,
    SHARE_CONTACT,
    CREATE_REMINDER,
    CREATE_CALENDAR_EVENT,
    MUSIC_CONTROL,
    /** MU-8: like/dislike/"more like this" on the current track; updates the local taste model. */
    MUSIC_FEEDBACK,
    LOCAL_CODING,
    CLOUD_CODING,
    FIND_FILE,
    ORGANIZE_FILES,
    DELETE_FILES,
    READ_NOTIFICATIONS,
    DRAFT_DOCUMENT,
    BUILD_APP,
    SERVE_WEB_APP,
    /** Local or cloud-generated conversational reply; the actuator has nothing to execute. */
    CHAT_REPLY,
    /** TM-2: set an alarm in the system Clock app via AlarmClock.ACTION_SET_ALARM. */
    SET_ALARM,
    /** TM-3: set a countdown timer via AlarmClock.ACTION_SET_TIMER. */
    SET_TIMER,
    /** TM-7: cancel a pending reminder in Raijin's own engine. */
    CANCEL_REMINDER,
    /** TM-7: read out the pending reminders. */
    LIST_REMINDERS,
    /** TM-7: best-effort dismiss of a matching upcoming alarm in the Clock app. */
    DISMISS_ALARM,
    /** AP-3: open an app's system settings page (application details). */
    OPEN_APP_SETTINGS,
    /**
     * OB-1: write a note into the owner-configured Obsidian vault ONLY.
     * The actuator refuses anything outside the vault root; this is never a
     * generic file-write permission.
     */
    WRITE_VAULT_NOTE,
}

enum class MessageChannel {
    AUTO,
    WHATSAPP,
    SMS,
    EMAIL,
    ANDROID_SHARE,
}

enum class ShareChannel {
    AUTO,
    ANDROID_SHARE,
    WHATSAPP,
    SMS,
    EMAIL,
}

enum class MusicCommand {
    PLAY,
    PAUSE,
    RESUME,
    NEXT,
    PREVIOUS,
    PLAY_QUERY,
    /** MU-4: loop the current track where the active player supports it. */
    REPEAT,
    /** MU-4: raise/lower media volume one step (AudioManager, always works). */
    VOLUME_UP,
    VOLUME_DOWN,
    /** MU-4: answer "what song is this" from the active media session. */
    NOW_PLAYING,
    /** MU-7: "play something" - taste-weighted pick from the local library. */
    PLAY_SOMETHING,
}

/** MU-8: owner feedback on the currently playing track. */
enum class MusicFeedback {
    LIKED,
    DISLIKED,
    /** "aur aisa" - counted as a like by the model until MU-7 similarity lands. */
    MORE_LIKE_THIS,
}

enum class CloudDataCategory {
    CODING_REQUEST,
    SOURCE_SNIPPET,
    COMPILER_OUTPUT,
    RETRIEVED_CODE_REFERENCE,
}

enum class InputKind {
    CLARIFICATION,
    CONFIRMATION,
    CLOUD_CONSENT,
    PERMISSION,
}

data class InputOption(
    val id: String,
    val label: String,
) {
    init {
        require(id.isNotBlank()) { "option id must not be blank" }
        require(label.isNotBlank()) { "option label must not be blank" }
    }
}

sealed interface ActionTarget {
    data object None : ActionTarget

    data class Contact(
        val ref: String,
        val displayName: String? = null,
    ) : ActionTarget {
        init {
            require(ref.isNotBlank()) { "contact ref must not be blank" }
        }
    }

    data class File(
        val ref: String,
        val displayName: String,
    ) : ActionTarget {
        init {
            require(ref.isNotBlank()) { "file ref must not be blank" }
            require(displayName.isNotBlank()) { "file displayName must not be blank" }
        }
    }

    data class App(
        val ref: String,
        val displayName: String? = null,
        val spokenAlternatives: List<String> = emptyList(),
    ) : ActionTarget {
        init {
            require(ref.isNotBlank()) { "app ref must not be blank" }
        }
    }

    data class Reminder(val ref: String?) : ActionTarget
    data class CalendarEvent(val ref: String?) : ActionTarget
    data class Media(val ref: String?) : ActionTarget
    data class CodingTask(val ref: String?) : ActionTarget
}

sealed interface ActionArgs {
    data object None : ActionArgs

    /**
     * OB-1: a note write scoped to the owner-configured Obsidian vault.
     * [relativePath] is inside the vault root: no absolute paths, no "..",
     * must end in .md. [append] false = create-only (refuses to overwrite).
     */
    data class VaultNote(
        val relativePath: String,
        val content: String,
        val append: Boolean = false,
    ) : ActionArgs {
        init {
            require(relativePath.isNotBlank()) { "vault note path must not be blank" }
            require(!relativePath.startsWith("/")) { "vault note path must be relative to the vault root" }
            require(!relativePath.split('/').any { it == ".." || it.isEmpty() }) {
                "vault note path must not contain '..' or empty segments"
            }
            require(relativePath.endsWith(".md")) { "vault notes are markdown (.md)" }
            require(content.isNotBlank()) { "vault note content must not be blank" }
        }
    }

    data class Call(
        val speaker: Boolean = false,
    ) : ActionArgs

    /** AP-3: target screen in a known app ("maps" + search query, "whatsapp" + contact name). */
    data class DeepLink(
        val app: String,
        val target: String,
    ) : ActionArgs {
        init {
            require(app.isNotBlank()) { "deep link app must not be blank" }
            require(target.isNotBlank()) { "deep link target must not be blank" }
        }
    }

    data class Message(
        val text: String,
        val channel: MessageChannel = MessageChannel.AUTO,
    ) : ActionArgs {
        init {
            require(text.isNotBlank()) { "message text must not be blank" }
        }
    }

    data class Share(
        val channel: ShareChannel = ShareChannel.AUTO,
        val accompanyingText: String? = null,
    ) : ActionArgs {
        init {
            require(accompanyingText == null || accompanyingText.isNotBlank()) {
                "accompanying text must be null or non-blank"
            }
        }
    }

    data class Reminder(
        val title: String,
        val triggerAtEpochMillis: Long,
        val timeZoneId: String,
        val recurrenceRule: String? = null,
        val condition: String? = null,
        val location: String? = null,
    ) : ActionArgs {
        init {
            require(title.isNotBlank()) { "reminder title must not be blank" }
            require(triggerAtEpochMillis >= 0) { "trigger time must be non-negative" }
            require(timeZoneId.isNotBlank()) { "timeZoneId must not be blank" }
            require(condition == null || condition.isNotBlank()) {
                "condition must be null or non-blank"
            }
            require(location == null || location.isNotBlank()) {
                "location must be null or non-blank"
            }
        }
    }

    /** TM-7: match pending reminders by clock time and/or title fragment. All fields optional; at least one must be set. */
    data class ReminderQuery(
        val hour: Int? = null,
        val minute: Int? = null,
        val titleFragment: String? = null,
    ) : ActionArgs {
        init {
            require(hour != null || titleFragment != null) { "reminder query needs a time or title" }
            require(hour == null || hour in 0..23) { "query hour must be 0-23" }
            require(minute == null || minute in 0..59) { "query minute must be 0-59" }
        }
    }

    /** TM-2: wall-clock alarm handed to the system Clock app. */
    data class Alarm(
        val hour: Int,
        val minute: Int,
        val label: String? = null,
        /** Ask the clock app to skip its UI; OEM clocks may ignore it. */
        val skipUi: Boolean = true,
    ) : ActionArgs {
        init {
            require(hour in 0..23) { "alarm hour must be 0-23" }
            require(minute in 0..59) { "alarm minute must be 0-59" }
        }
    }

    /** TM-3: countdown timer handed to the system Clock app. */
    data class Timer(
        val lengthSeconds: Int,
        val label: String? = null,
    ) : ActionArgs {
        init {
            require(lengthSeconds in 1..86_400) { "timer length must be 1s-24h" }
        }
    }

    data class CalendarEvent(
        val title: String,
        val startAtEpochMillis: Long,
        val endAtEpochMillis: Long? = null,
        val timeZoneId: String,
        val recurrenceRule: String? = null,
        val location: String? = null,
        val notes: String? = null,
    ) : ActionArgs {
        init {
            require(title.isNotBlank()) { "calendar title must not be blank" }
            require(startAtEpochMillis >= 0) { "calendar start must be non-negative" }
            require(endAtEpochMillis == null || endAtEpochMillis >= startAtEpochMillis) {
                "calendar end must not be before start"
            }
            require(timeZoneId.isNotBlank()) { "timeZoneId must not be blank" }
        }
    }

    data class Music(
        val command: MusicCommand,
        val query: String? = null,
    ) : ActionArgs {
        init {
            require(command != MusicCommand.PLAY_QUERY || !query.isNullOrBlank()) {
                "PLAY_QUERY requires a non-blank query"
            }
        }
    }

    /** MU-8: feedback on the current track; the actuator resolves what is playing. */
    data class Feedback(
        val sentiment: MusicFeedback,
    ) : ActionArgs

    data class Coding(
        val request: String,
    ) : ActionArgs {
        init {
            require(request.isNotBlank()) { "coding request must not be blank" }
        }
    }
}

data class DispatchResult(
    val traceId: String,
    val actionType: ActionType,
    val targets: List<ActionTarget> = emptyList(),
    val args: ActionArgs = ActionArgs.None,
    val confidence: Float,
    val risk: ActionRisk = ActionRisk.LOW,
    val vocalResponse: String? = null,
) {
    init {
        require(traceId.isNotBlank()) { "traceId must not be blank" }
        require(confidence in 0.0f..1.0f) { "confidence must be between 0 and 1" }

        when (actionType) {
            ActionType.CALL_CONTACT -> require(args is ActionArgs.Call) {
                "CALL_CONTACT requires ActionArgs.Call"
            }

            ActionType.SEND_MESSAGE -> require(args is ActionArgs.Message) {
                "SEND_MESSAGE requires ActionArgs.Message"
            }

            ActionType.SHARE_FILE,
            ActionType.SHARE_CONTACT,
            -> require(args is ActionArgs.Share) {
                "$actionType requires ActionArgs.Share"
            }

            ActionType.CREATE_REMINDER -> require(args is ActionArgs.Reminder) {
                "CREATE_REMINDER requires ActionArgs.Reminder"
            }

            ActionType.CREATE_CALENDAR_EVENT -> require(args is ActionArgs.CalendarEvent) {
                "CREATE_CALENDAR_EVENT requires ActionArgs.CalendarEvent"
            }

            ActionType.MUSIC_CONTROL -> require(args is ActionArgs.Music) {
                "MUSIC_CONTROL requires ActionArgs.Music"
            }

            ActionType.MUSIC_FEEDBACK -> require(args is ActionArgs.Feedback) {
                "MUSIC_FEEDBACK requires ActionArgs.Feedback"
            }

            ActionType.LOCAL_CODING,
            ActionType.CLOUD_CODING,
            -> require(args is ActionArgs.Coding) {
                "$actionType requires ActionArgs.Coding"
            }

            else -> Unit
        }
    }
}

sealed interface ActionResult {
    /**
     * The action completed. [message], when present, overrides the dispatch's
     * pre-set vocal response - used when the real outcome is only known at
     * actuation time (e.g. MU-2 now-playing answers with the actual track).
     *
     * TM-7: [undoAction], when present, offers a one-shot undo for [undoWindowMs]
     * after completion. The brain surfaces it as [IslandVisualState.Undoable];
     * the actuator must be able to reverse the action via [ActuatorPort.undo].
     */
    data class EXECUTED(
        val message: String? = null,
        val undoAction: UndoAction? = null,
        val undoWindowMs: Long = DEFAULT_UNDO_WINDOW_MS,
    ) : ActionResult {
        init {
            require(undoAction == null || undoWindowMs > 0) {
                "undo window must be positive when an undo action is offered"
            }
        }
    }

    data class DENIED(
        val reason: String,
    ) : ActionResult {
        init {
            require(reason.isNotBlank()) { "denial reason must not be blank" }
        }
    }

    data class FAILED(
        val reason: String,
    ) : ActionResult {
        init {
            require(reason.isNotBlank()) { "failure reason must not be blank" }
        }
    }

    companion object {
        /** TM-7: PRD-mandated undo window after creation actions. */
        const val DEFAULT_UNDO_WINDOW_MS = 5_000L
    }
}

/**
 * TM-7: a reversible-effect handle returned by an actuator so the owner can
 * undo a just-completed action within its window. Pure data - the reversal
 * itself runs through [ActuatorPort.undo].
 */
sealed interface UndoAction {
    /** Delete (mark fired/cancelled) the reminder created with this row id. */
    data class DeleteReminder(val reminderId: Long) : UndoAction
}

interface ActuatorPort {
    suspend fun execute(action: DispatchResult): ActionResult

    /**
     * TM-7: reverse an action previously reported as [ActionResult.EXECUTED]
     * with an [ActionResult.EXECUTED.undoAction]. Default reports an honest
     * failure so existing actuators stay valid without undo support.
     */
    suspend fun undo(action: UndoAction): ActionResult =
        ActionResult.FAILED("This action cannot be undone.")
}

data class CapabilitySnapshot(
    val microphoneAvailable: Boolean = false,
    val contactsAvailable: Boolean = false,
    val storageAvailable: Boolean = false,
    val notificationsAvailable: Boolean = false,
    val calendarAvailable: Boolean = false,
    val exactAlarmAvailable: Boolean = false,
    val accessibilityAvailable: Boolean = false,
    val musicLibraryAvailable: Boolean = false,
    val localCodingAvailable: Boolean = false,
    val cloudCodingEnabled: Boolean = false,
    val networkAvailable: Boolean = false,
    val phoneCallAvailable: Boolean = false,
    /**
     * AP-3: device region (ISO 3166-1 alpha-2, uppercase) from SIM/locale,
     * used to normalize national phone numbers. Null when the device can't
     * say - ambiguous numbers must then be clarified, never guessed.
     */
    val deviceRegionIso: String? = null,
)

data class DeviceContext(
    val wifiConnected: Boolean? = null,
    val bluetoothConnected: Boolean? = null,
    val batteryPercent: Int? = null,
) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100) {
            "batteryPercent must be between 0 and 100"
        }
    }
}

data class ActiveContext(
    val foregroundApp: String? = null,
    val selectedFileRef: String? = null,
    val selectedContactRef: String? = null,
    val selectedMediaRef: String? = null,
    val lastSharedItemRef: String? = null,
    val currentConversationTargetRef: String? = null,
    val device: DeviceContext = DeviceContext(),
)

data class CloudDisclosure(
    val categories: Set<CloudDataCategory>,
) {
    init {
        require(categories.isNotEmpty()) {
            "cloud disclosure must list at least one allowed coding category"
        }
    }
}

sealed interface IslandVisualState {
    data object Idle : IslandVisualState
    data object Listening : IslandVisualState

    data class Resolving(
        val label: String? = null,
        val stepTrace: List<String> = emptyList(),
    ) : IslandVisualState {
        init {
            require(stepTrace.none { it.isBlank() }) { "stepTrace entries must not be blank" }
        }
    }

    data class Thinking(
        val taskLabel: String,
        val mode: ProcessingMode,
    ) : IslandVisualState {
        init {
            require(mode != ProcessingMode.LOCAL_FAST) {
                "Thinking is reserved for deliberative work, not LOCAL_FAST"
            }
        }
    }

    data class Acting(
        val actionSummary: String,
    ) : IslandVisualState

    data class NeedsInput(
        val prompt: String,
        val traceId: String,
        val kind: InputKind,
        val options: List<InputOption> = emptyList(),
        val risk: ActionRisk = ActionRisk.LOW,
        val cloudDisclosure: CloudDisclosure? = null,
    ) : IslandVisualState {
        init {
            require(prompt.isNotBlank()) { "prompt must not be blank" }
            require(traceId.isNotBlank()) { "traceId must not be blank" }
            require(options.map { it.id }.distinct().size == options.size) {
                "option ids must be unique"
            }
            require(kind == InputKind.CLOUD_CONSENT || cloudDisclosure == null) {
                "cloud disclosure is only valid for CLOUD_CONSENT"
            }
            require(kind != InputKind.CLOUD_CONSENT || cloudDisclosure != null) {
                "CLOUD_CONSENT requires a cloud disclosure"
            }
        }
    }

    data class Done(
        val feedbackLine: String,
    ) : IslandVisualState

    /**
     * TM-7: a completed action whose effect can still be undone. Behaves like
     * [Done] but carries a one-shot [undoToken] for [RaijinBrainBridge.resolveUndo]
     * and auto-hides after [windowMs].
     */
    data class Undoable(
        val feedbackLine: String,
        val undoToken: String,
        val windowMs: Long,
        val undoLabel: String = "Undo",
    ) : IslandVisualState {
        init {
            require(feedbackLine.isNotBlank()) { "feedbackLine must not be blank" }
            require(undoToken.isNotBlank()) { "undoToken must not be blank" }
            require(windowMs > 0) { "undo window must be positive" }
            require(undoLabel.isNotBlank()) { "undoLabel must not be blank" }
        }
    }

    data class Failed(
        val errorReason: String,
        val recoverable: Boolean,
    ) : IslandVisualState

    data class Brief(
        val lines: List<String>,
    ) : IslandVisualState {
        init {
            require(lines.isNotEmpty()) { "brief must contain at least one line" }
            require(lines.none { it.isBlank() }) { "brief lines must not be blank" }
        }
    }
}

interface RaijinBrainBridge {
    /** Conflated StateFlow: collectors may miss intermediate visual states. */
    val islandState: StateFlow<IslandVisualState>
    val capabilityState: StateFlow<CapabilitySnapshot>

    suspend fun processUtterance(rawText: String, traceId: String): DispatchResult

    suspend fun resolveActionConfirmation(traceId: String, confirmed: Boolean)

    suspend fun resolveChoice(traceId: String, optionId: String)

    suspend fun resolveClarification(traceId: String, answer: String)

    /**
     * TM-7: resolve a pending [IslandVisualState.Undoable]. [accepted] = true
     * runs the reversal through the actuator; false dismisses. An unknown or
     * expired token is a no-op that returns the island to idle.
     */
    suspend fun resolveUndo(undoToken: String, accepted: Boolean)

    suspend fun cancelCurrentAction(traceId: String)

    fun submitContextUpdate(context: ActiveContext)

    fun submitCapabilitySnapshot(snapshot: CapabilitySnapshot)
}

enum class PersonalMemoryKind {
    ENTITY,
    FACT,
    EPISODE,
    PREFERENCE,
}

data class MemoryQuery(
    val text: String,
    val kinds: Set<PersonalMemoryKind> = emptySet(),
    val limit: Int = 10,
) {
    init {
        require(text.isNotBlank()) { "memory query text must not be blank" }
        require(limit in 1..100) { "memory query limit must be between 1 and 100" }
    }
}

data class MemoryHit(
    val id: String,
    val kind: PersonalMemoryKind,
    val score: Float,
    val summary: String,
) {
    init {
        require(id.isNotBlank()) { "memory hit id must not be blank" }
        require(score in 0.0f..1.0f) { "memory hit score must be between 0 and 1" }
    }
}

data class EntityCandidate(
    val entityId: String,
    val canonicalName: String,
    val confidence: Float,
) {
    init {
        require(entityId.isNotBlank()) { "entityId must not be blank" }
        require(canonicalName.isNotBlank()) { "canonicalName must not be blank" }
        require(confidence in 0.0f..1.0f) { "confidence must be between 0 and 1" }
    }
}

data class MemoryWrite(
    val kind: PersonalMemoryKind,
    val text: String,
    val entityIds: Set<String> = emptySet(),
) {
    init {
        require(text.isNotBlank()) { "memory text must not be blank" }
    }
}

interface RaijinMemoryPort {
    suspend fun search(query: MemoryQuery): List<MemoryHit>

    suspend fun resolveEntity(nameOrAlias: String, limit: Int = 5): List<EntityCandidate>

    /**
     * CT-7: persist a confirmed "spoken form -> entity" mapping so the same
     * pronunciation resolves instantly next time. Default no-op for stores
     * that do not track contact aliases and for test doubles.
     */
    suspend fun learnEntityAlias(spokenForm: String, entityId: String, displayName: String) {}

    /**
     * CT-7: permanently record that the owner rejected the contact resolved
     * for the spoken form [wrongAlias], so future resolutions of that alias
     * deprioritize it. Default no-op for test doubles.
     */
    suspend fun recordContactCorrection(wrongAlias: String, entityId: String) {}

    /**
     * AP-2: persist an explicit owner correction "spoken form -> intended app
     * name" so the same spoken form resolves to the intended app next time.
     * Default no-op for stores that do not track app aliases.
     */
    suspend fun learnAppAlias(spokenForm: String, intendedAppName: String) {}

    /** VO-1: resolve names from every relevant n-best speech alternative. */
    suspend fun resolveEntityAlternatives(names: List<String>, limit: Int = 5): List<EntityCandidate> =
        names.flatMap { resolveEntity(it, limit) }.sortedByDescending { it.confidence }
            .distinctBy { it.entityId }.take(limit)

    suspend fun remember(write: MemoryWrite): String

    suspend fun forget(memoryId: String): Boolean
}


interface CloudReasoningPort {
    val isConfigured: Boolean

    suspend fun complete(
        prompt: String,
        systemPrompt: String? = null,
        temperature: Float = 0.7f,
    ): String
}

// ---- MU-6: local, interpretable music preference model ---------------------

/** What the owner did with a track. Weights live in brain-core's model. */
enum class MusicEventType {
    PLAYED,
    COMPLETED,
    /** Skipped within the first 30 seconds - a strong negative signal. */
    SKIPPED_EARLY,
    REPLAYED,
    /** Explicit voice like ("pasand aaya"). */
    LIKED,
    /** Explicit voice dislike ("ye mat chalao"). */
    DISLIKED,
}

enum class MusicSource {
    LOCAL_LIBRARY,
    STREAMING_APP,
    EXTERNAL_PLAYER,
    UNKNOWN,
}

/** One observed music event. Everything stays on-device. */
data class MusicEvent(
    val type: MusicEventType,
    val track: String,
    val artist: String,
    val language: String? = null,
    val genre: String? = null,
    val source: MusicSource = MusicSource.UNKNOWN,
    val atEpochMillis: Long,
    /** 0.0..1.0, only meaningful for COMPLETED. */
    val completionRatio: Float? = null,
) {
    init {
        require(track.isNotBlank()) { "event track must not be blank" }
        require(artist.isNotBlank()) { "event artist must not be blank" }
        require(atEpochMillis >= 0) { "event time must be non-negative" }
        require(completionRatio == null || completionRatio in 0.0f..1.0f) {
            "completionRatio must be between 0 and 1"
        }
        require(type != MusicEventType.COMPLETED || completionRatio != null) {
            "COMPLETED events carry a completionRatio"
        }
    }
}

enum class DayPart { MORNING, AFTERNOON, EVENING, NIGHT }

/**
 * One interpretable aggregate line: the score plus the plain-language
 * evidence behind it ("played 9 times, liked twice, mostly evenings").
 */
data class TasteSignal(
    val name: String,
    val score: Double,
    val plays: Int,
    val likes: Int,
    val dislikes: Int,
    val earlySkips: Int,
    val dominantDayPart: DayPart? = null,
    val weekendShare: Float = 0f,
) {
    init {
        require(name.isNotBlank()) { "signal name must not be blank" }
        require(weekendShare in 0.0f..1.0f) { "weekendShare must be between 0 and 1" }
    }
}

/**
 * MU-6: the app-side store of music events + aggregated taste. Local only;
 * nothing here may leave the device (mood classification with cloud help is
 * MU-9's separate L2-consented path, titles only).
 */
interface MusicTastePort {
    suspend fun recordMusicEvent(event: MusicEvent)

    suspend fun topArtists(limit: Int = 10, nowEpochMillis: Long? = null): List<TasteSignal>

    suspend fun topLanguages(limit: Int = 5, nowEpochMillis: Long? = null): List<TasteSignal>

    suspend fun topGenres(limit: Int = 5, nowEpochMillis: Long? = null): List<TasteSignal>

    /** Recent events for "aur aisa"-style similarity and MU-7 avoid-repeats. */
    suspend fun recentEvents(limit: Int = 50): List<MusicEvent>
}
