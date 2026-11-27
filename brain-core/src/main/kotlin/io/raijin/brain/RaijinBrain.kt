package io.raijin.brain

import java.time.ZonedDateTime
import io.raijin.contract.ActionArgs
import io.raijin.contract.ActionResult
import io.raijin.contract.ActionRisk
import io.raijin.contract.ActionTarget
import io.raijin.contract.ActionType
import io.raijin.contract.ActuatorPort
import io.raijin.contract.ActiveContext
import io.raijin.contract.CapabilitySnapshot
import io.raijin.contract.CloudReasoningPort
import io.raijin.contract.DispatchResult
import io.raijin.contract.EntityCandidate
import io.raijin.contract.InputKind
import io.raijin.contract.InputOption
import io.raijin.contract.IslandVisualState
import io.raijin.contract.MessageChannel
import io.raijin.contract.MusicCommand
import io.raijin.contract.MusicFeedback
import io.raijin.contract.ProcessingMode
import io.raijin.contract.RaijinBrainBridge
import io.raijin.contract.RaijinMemoryPort
import io.raijin.contract.UndoAction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.yield

class RaijinBrain(
    private val memory: RaijinMemoryPort,
    private val actuator: ActuatorPort,
    private val cloudReasoning: CloudReasoningPort? = null,
    private val actuatorTimeoutMillis: Long = 30_000L,
    private val callCountdownMillis: Long = 2_000L,
) : RaijinBrainBridge {
    private val _islandState = MutableStateFlow<IslandVisualState>(IslandVisualState.Idle)
    override val islandState: StateFlow<IslandVisualState> = _islandState.asStateFlow()

    private val _capabilityState = MutableStateFlow(CapabilitySnapshot())
    override val capabilityState: StateFlow<CapabilitySnapshot> = _capabilityState.asStateFlow()

    private var activeContext: ActiveContext = ActiveContext()
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val actionLock = Any()
    private var actingTrace: String? = null

    /** TM-7: one-shot undo offer, keyed by token; valid until expiresAtMs. */
    private data class PendingUndo(val action: UndoAction, val expiresAtMs: Long)

    private val pendingUndo = ConcurrentHashMap<String, PendingUndo>()

    /** AP-2: most recent app open, kept briefly so an explicit correction can teach an alias. */
    private var lastOpenedApp: LastOpenedApp? = null

    /**
     * BR-9: in-memory conversational turns for per-request cloud context.
     * Memory-only (never persisted); wiped by "clear history".
     */
    private val sessionTurns = SessionTurnLog()

    init {
        require(actuatorTimeoutMillis > 0) { "actuator timeout must be positive" }
        require(callCountdownMillis > 0) { "call countdown must be positive" }
    }

    private val speechAlternatives = ConcurrentHashMap<String, List<String>>()

    /** Keep all n-best transcripts local; only the primary text may reach cloud reasoning. */
    suspend fun processAlternatives(alternatives: List<String>, traceId: String): DispatchResult {
        val clean = alternatives.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        require(clean.isNotEmpty()) { "speech alternatives must not be empty" }
        speechAlternatives[traceId] = clean
        return try { processUtterance(clean.first(), traceId) }
        finally { speechAlternatives.remove(traceId) }
    }

    override suspend fun processUtterance(rawText: String, traceId: String): DispatchResult {
        require(rawText.isNotBlank()) { "utterance must not be blank" }
        require(traceId.isNotBlank()) { "traceId must not be blank" }

        // BR-9: clear-on-demand. Local only; never reaches the parser or cloud.
        if (SessionHistoryClear.matches(rawText)) {
            sessionTurns.clear()
            val reply = "Conversation history cleared."
            emit(IslandVisualState.Done(feedbackLine = reply))
            return DispatchResult(
                traceId = traceId,
                actionType = ActionType.NONE,
                confidence = 1.0f,
                risk = ActionRisk.LOW,
                vocalResponse = reply,
            )
        }

        // CT-7: an explicit wrong-contact correction ("wrong number", "not
        // him", "I meant ...") while a contact confirmation is pending records
        // a permanent correction. Must run before supersedeOtherRequests,
        // which clears pending. A plain "no"/cancel never lands here as a
        // correction: only explicit correction phrases match.
        if (CorrectionIntent.looksLikeCorrection(rawText)) {
            val contactRequest = synchronized(actionLock) {
                pending.values.firstOrNull {
                    it.stage == PendingStage.CONFIRMATION &&
                        (it.actionType == ActionType.CALL_CONTACT ||
                            it.actionType == ActionType.SEND_MESSAGE)
                }
            }
            if (contactRequest != null) {
                synchronized(actionLock) { pending.remove(contactRequest.traceId, contactRequest) }
                recordRejectedContact(contactRequest)
                val label = contactRequest.result?.targets
                    ?.filterIsInstance<ActionTarget.Contact>()
                    ?.firstOrNull()?.displayName
                val ack = if (label != null) {
                    "Noted. I won't pick $label for that name again."
                } else {
                    "Noted, boss."
                }
                emit(IslandVisualState.Done(ack))
                return DispatchResult(
                    traceId = traceId,
                    actionType = contactRequest.actionType,
                    args = contactRequest.args,
                    confidence = 1.0f,
                    risk = contactRequest.risk,
                    vocalResponse = ack,
                )
            }
            // No pending contact confirmation: not a correction after all;
            // fall through to normal processing.
        }

        // AP-2: an explicit app correction with a restated app ("wrong app, I
        // meant X") shortly after an app open teaches a permanent alias and
        // opens the intended app. Like CT-7, only explicit correction phrases
        // match; a plain "no" is a cancel, never a learning signal.
        val restatedApp = AppCorrectionIntent.parseRestated(rawText)
        if (restatedApp != null) {
            val lastApp = synchronized(actionLock) {
                lastOpenedApp?.takeIf {
                    System.currentTimeMillis() - it.atMillis <= APP_CORRECTION_WINDOW_MS
                }.also { lastOpenedApp = null }
            }
            if (lastApp != null) {
                try {
                    memory.learnAppAlias(lastApp.spoken, restatedApp)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // best-effort learning; the open below still proceeds
                }
                emit(IslandVisualState.Listening)
                emit(IslandVisualState.Resolving(label = "Resolving request"))
                val result = DispatchResult(
                    traceId = traceId,
                    actionType = ActionType.OPEN_APP,
                    targets = listOf(
                        ActionTarget.App(
                            ref = restatedApp.lowercase().replace(" ", "-"),
                            displayName = restatedApp,
                        ),
                    ),
                    confidence = 1.0f,
                    risk = ActionRisk.LOW,
                    vocalResponse = "Opening $restatedApp. I'll treat \"${lastApp.spoken}\" as $restatedApp from now on.",
                )
                execute(result)
                synchronized(actionLock) {
                    lastOpenedApp = LastOpenedApp(restatedApp, System.currentTimeMillis())
                }
                return result
            }
            // No recent app open: not an app correction; fall through.
        }

        // AP-3: an outstanding number-region clarification consumes the next
        // utterance as its answer (voice-first: the prompt asks, the user
        // replies with a country or the full international number).
        val numberRequest = synchronized(actionLock) {
            pending.values.firstOrNull { it.stage == PendingStage.NUMBER_REGION }
        }
        if (numberRequest != null) {
            handleNumberRegionAnswer(numberRequest, rawText.trim())
            return DispatchResult(
                traceId = traceId,
                actionType = ActionType.NONE,
                confidence = 0.0f,
                risk = ActionRisk.LOW,
                vocalResponse = null,
            )
        }

        synchronized(actionLock) {
            if (actingTrace != null) {
                _islandState.value = IslandVisualState.Acting("I'm finishing your previous action. Please try again when it's done.")
                return DispatchResult(traceId, ActionType.NONE, confidence = 0.0f,
                    vocalResponse = "I'm finishing another action. Please try again.")
            }
            supersedeOtherRequests(traceId)
        }
        return track(traceId, onCancelled = {
            DispatchResult(traceId, ActionType.NONE, confidence = 0.0f,
                vocalResponse = "Request superseded.")
        }) {
            emit(IslandVisualState.Listening)
            emit(IslandVisualState.Resolving(label = "Resolving request"))

            when (val intent = ReflexParser.parse(rawText)) {
                is ParsedIntent.OpenAppSettings -> {
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.OPEN_APP_SETTINGS,
                        targets = listOf(
                            ActionTarget.App(
                                ref = intent.app.lowercase().replace(" ", "-"),
                                displayName = intent.app,
                            ),
                        ),
                        confidence = 0.9f,
                        risk = ActionRisk.LOW,
                        vocalResponse = "Opening ${intent.app} settings.",
                    )
                    execute(result)
                    result
                }

                is ParsedIntent.OpenMapSearch -> {
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.OPEN_DEEP_LINK,
                        args = ActionArgs.DeepLink(app = "maps", target = intent.query),
                        confidence = 0.95f,
                        risk = ActionRisk.LOW,
                        vocalResponse = "Searching Maps for ${intent.query}.",
                    )
                    execute(result)
                    result
                }

                is ParsedIntent.OpenWhatsAppChat -> gateOrResolve(
                    traceId = traceId,
                    name = intent.name,
                    actionType = ActionType.OPEN_DEEP_LINK,
                    args = ActionArgs.DeepLink(app = "whatsapp", target = intent.name),
                    risk = ActionRisk.LOW,
                    vocalResponse = "Opening WhatsApp chat with ${intent.name}.",
                )

                is ParsedIntent.OpenApp -> {
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.OPEN_APP,
                        targets = listOf(
                            ActionTarget.App(
                                ref = intent.app.lowercase().replace(" ", "-"),
                                displayName = intent.app,
                                spokenAlternatives = speechAlternatives[traceId].orEmpty().mapNotNull {
                                    (ReflexParser.parse(it) as? ParsedIntent.OpenApp)?.app
                                },
                            ),
                        ),
                        confidence = 0.99f,
                        risk = ActionRisk.LOW,
                        vocalResponse = "Opening ${intent.app}.",
                    )
                    execute(result)
                    synchronized(actionLock) {
                        lastOpenedApp = LastOpenedApp(intent.app, System.currentTimeMillis())
                    }
                    result
                }

                is ParsedIntent.Call -> gateOrResolve(
                    traceId = traceId,
                    name = intent.name,
                    actionType = ActionType.CALL_CONTACT,
                    args = ActionArgs.Call(speaker = intent.speaker),
                    risk = ActionRisk.MEDIUM,
                    vocalResponse = "Calling ${intent.name}.",
                )

                is ParsedIntent.Message -> gateOrResolve(
                    traceId = traceId,
                    name = intent.name,
                    actionType = ActionType.SEND_MESSAGE,
                    args = ActionArgs.Message(
                        text = intent.text,
                        channel = intent.channel,
                    ),
                    risk = ActionRisk.HIGH,
                    vocalResponse = "Sending a message to ${intent.name}.",
                )

                is ParsedIntent.Music -> {
                    val vocal = when (intent.command) {
                        MusicCommand.PLAY_QUERY -> "Playing ${intent.query}."
                        MusicCommand.PAUSE -> "Pausing music."
                        MusicCommand.RESUME, MusicCommand.PLAY -> "Resuming music."
                        MusicCommand.NEXT -> "Skipping track."
                        MusicCommand.PREVIOUS -> "Playing previous track."
                        MusicCommand.REPEAT -> "Turning repeat on."
                        MusicCommand.VOLUME_UP -> "Turning the volume up."
                        MusicCommand.VOLUME_DOWN -> "Turning the volume down."
                        MusicCommand.NOW_PLAYING -> "Let me check what's playing."
                        MusicCommand.PLAY_SOMETHING -> "Picking something for you."
                        MusicCommand.PLAY_VIDEO ->
                            if (intent.query.isNullOrBlank()) "Opening YouTube." else "Looking up ${intent.query}."
                    }
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.MUSIC_CONTROL,
                        args = ActionArgs.Music(command = intent.command, query = intent.query),
                        confidence = 0.95f,
                        risk = ActionRisk.LOW,
                        vocalResponse = vocal,
                    )
                    execute(result)
                    result
                }

                is ParsedIntent.TrackFeedback -> {
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.MUSIC_FEEDBACK,
                        args = ActionArgs.Feedback(sentiment = intent.sentiment),
                        confidence = 0.95f,
                        risk = ActionRisk.LOW,
                        vocalResponse = "Noting that.",
                    )
                    execute(result)
                    result
                }

                is ParsedIntent.Reminder -> {
                    val recurringNote = when (intent.recurrence) {
                        HinglishTimeParser.Recurrence.DAILY -> " Roz isi time yaad dilaunga."
                        HinglishTimeParser.Recurrence.WEEKLY -> " Har hafte isi din yaad dilaunga."
                        HinglishTimeParser.Recurrence.NONE -> ""
                    }
                    // TM-9: a conditional reminder is cancelled early if the
                    // watched contact replies; otherwise it fires at the deadline.
                    val conditionalNote = intent.conditionWatchContact?.let {
                        " I'll watch for $it's reply."
                    } ?: ""
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.CREATE_REMINDER,
                        args = ActionArgs.Reminder(
                            title = intent.title,
                            triggerAtEpochMillis = intent.triggerAtEpochMillis,
                            timeZoneId = java.util.TimeZone.getDefault().id,
                            recurrenceRule = when (intent.recurrence) {
                                HinglishTimeParser.Recurrence.DAILY -> "DAILY"
                                HinglishTimeParser.Recurrence.WEEKLY -> "WEEKLY"
                                HinglishTimeParser.Recurrence.NONE -> null
                            },
                            condition = intent.conditionWatchContact?.let { "if $it doesn't reply" },
                            conditionWatch = intent.conditionWatchContact?.let {
                                ActionArgs.ConditionWatch(contactName = it)
                            },
                        ),
                        confidence = 0.95f,
                        risk = ActionRisk.LOW,
                        vocalResponse = "Reminder set for ${intent.title}.$recurringNote$conditionalNote See it under Reminders in the app.",
                    )
                    execute(result)
                    result
                }

                is ParsedIntent.Alarm -> {
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.SET_ALARM,
                        args = ActionArgs.Alarm(hour = intent.hour, minute = intent.minute),
                        confidence = 0.95f,
                        risk = ActionRisk.LOW,
                        vocalResponse = "Alarm set for ${formatClock(intent.hour, intent.minute)}.",
                    )
                    execute(result)
                    result
                }

                is ParsedIntent.TimerIntent -> {
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.SET_TIMER,
                        args = ActionArgs.Timer(lengthSeconds = intent.lengthSeconds),
                        confidence = 0.95f,
                        risk = ActionRisk.LOW,
                        vocalResponse = "Timer set for ${formatDuration(intent.lengthSeconds)}.",
                    )
                    execute(result)
                    result
                }

                is ParsedIntent.CancelReminder -> {
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.CANCEL_REMINDER,
                        args = ActionArgs.ReminderQuery(
                            hour = intent.hour,
                            minute = intent.minute,
                            titleFragment = intent.titleFragment,
                        ),
                        confidence = 0.9f,
                        risk = ActionRisk.LOW,
                        vocalResponse = null, // actuator speaks the outcome
                    )
                    execute(result)
                    result
                }

                is ParsedIntent.ListReminders -> {
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.LIST_REMINDERS,
                        confidence = 0.9f,
                        risk = ActionRisk.LOW,
                        vocalResponse = null, // actuator speaks the list
                    )
                    execute(result)
                    result
                }

                is ParsedIntent.NotificationQuery -> {
                    // NT-5: actuator answers locally from the notification buffer.
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.READ_NOTIFICATIONS,
                        args = ActionArgs.NotificationQuery(appFilter = intent.appFilter),
                        confidence = 0.9f,
                        risk = ActionRisk.LOW,
                        vocalResponse = null, // actuator speaks the answer
                    )
                    execute(result)
                    result
                }

                is ParsedIntent.DismissAlarm -> {
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.DISMISS_ALARM,
                        args = ActionArgs.Alarm(hour = intent.hour, minute = intent.minute),
                        confidence = 0.9f,
                        risk = ActionRisk.LOW,
                        vocalResponse = "Asking the Clock app to dismiss that alarm.",
                    )
                    execute(result)
                    result
                }

                is ParsedIntent.CleanStorage -> {
                    val result = DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.DELETE_FILES,
                        confidence = 0.95f,
                        risk = ActionRisk.HIGH,
                        vocalResponse = "Storage audit complete. Review and approve before cleaning.",
                    )
                    pending[traceId] = PendingRequest(
                        traceId = traceId,
                        actionType = ActionType.DELETE_FILES,
                        args = ActionArgs.None,
                        risk = ActionRisk.HIGH,
                        vocalResponse = result.vocalResponse ?: "Storage audit complete.",
                        candidates = emptyList(),
                        stage = PendingStage.CONFIRMATION,
                        result = result,
                    )
                    emit(
                        IslandVisualState.NeedsInput(
                            prompt = "Storage Audit: Clean approved cache & temporary files?",
                            traceId = traceId,
                            kind = InputKind.CONFIRMATION,
                            options = listOf(
                                InputOption("confirm", "Clean Approved"),
                                InputOption("cancel", "Keep All"),
                            ),
                            risk = ActionRisk.HIGH,
                        ),
                    )
                    result
                }

                is ParsedIntent.Chat -> {
                    // BR-1: instant local answer; nothing for the actuator to run.
                    emit(IslandVisualState.Done(feedbackLine = intent.reply))
                    sessionTurns.record(
                        SessionTurn(rawText, intent.reply, System.currentTimeMillis()),
                    )
                    DispatchResult(
                        traceId = traceId,
                        actionType = ActionType.CHAT_REPLY,
                        confidence = 1.0f,
                        risk = ActionRisk.LOW,
                        vocalResponse = intent.reply,
                    )
                }

                ParsedIntent.Unknown -> {
                    if (cloudReasoning?.isConfigured == true) {
                        emit(
                            IslandVisualState.Thinking(
                                taskLabel = "Consulting Cloud AI",
                                mode = ProcessingMode.CLOUD_GENERAL,
                            ),
                        )
                        val answer = try {
                            // BR-9: per-request context — follow-ups carry up to
                            // the last 3 session turns; anything else sends the
                            // current message unchanged. Approved owner wording
                            // lives in the PR body / STATUS.md.
                            cloudReasoning.complete(
                                prompt = SessionContextPrompt.build(rawText, sessionTurns),
                                systemPrompt = Persona.systemPrompt,
                            )
                        } catch (e: Exception) {
                            emit(IslandVisualState.Failed(errorReason = "Cloud request failed: ${e.message}", recoverable = true))
                            return@track DispatchResult(
                                traceId = traceId,
                                actionType = ActionType.NONE,
                                confidence = 0.0f,
                                vocalResponse = "Cloud reasoning encountered an error.",
                            )
                        }
                        emit(IslandVisualState.Done(feedbackLine = answer))
                        sessionTurns.record(
                            SessionTurn(rawText, answer, System.currentTimeMillis()),
                        )
                        DispatchResult(
                            traceId = traceId,
                            actionType = ActionType.NONE,
                            confidence = 1.0f,
                            vocalResponse = answer,
                        )
                    } else {
                        emit(
                            IslandVisualState.Failed(
                                errorReason = "I didn't understand that. Try \"call <name>\", \"message <name>: <text>\", \"open <app>\", or connect an OpenAI-compatible cloud endpoint.",
                                recoverable = true,
                            ),
                        )
                        DispatchResult(
                            traceId = traceId,
                            actionType = ActionType.NONE,
                            confidence = 0.0f,
                            vocalResponse = "I didn't understand that. Try a call, message or open-app command.",
                        )
                    }
                }
            }
        }
    }

    override suspend fun resolveActionConfirmation(traceId: String, confirmed: Boolean) {
        val request = pending[traceId] ?: return
        if (request.stage == PendingStage.PERMISSION) {
            if (!pending.remove(traceId, request)) return
            if (!confirmed) {
                emit(IslandVisualState.Done("Cancelled."))
                return
            }
            gateOrResolve(traceId, requireNotNull(request.name), request.actionType, request.args, request.risk, request.vocalResponse)
            return
        }
        if (request.stage == PendingStage.NUMBER_REGION) {
            // The answer must be spoken/typed; the only control shown is
            // Cancel (the affirmative control is hidden for option-less
            // clarifications). Any press that still arrives cancels.
            if (pending.remove(traceId, request)) {
                emit(IslandVisualState.Done("Cancelled."))
            }
            return
        }
        if (request.stage != PendingStage.CONFIRMATION || request.result == null) return

        // A plain "no" is a cancellation, never a wrong-contact signal: it
        // must not teach Raijin that a correct mapping is wrong (CT-7).
        if (!confirmed) {
            if (pending.remove(traceId, request)) emit(IslandVisualState.Done("Cancelled."))
            return
        }
        synchronized(actionLock) {
            if (actingTrace != null || pending[traceId] !== request) return
            actingTrace = traceId // reserve before removing pending: no confirm-to-Acting gap
            pending.remove(traceId, request)
        }
        learnConfirmedAliases(request)
        try {
            track(traceId, onCancelled = { Unit }) {
                execute(request.result)
            }
        } finally {
            synchronized(actionLock) {
                if (actingTrace == traceId) actingTrace = null
            }
        }
    }

    /**
     * CT-8: after the countdown window, confirm the call on the user's behalf
     * unless someone already resolved the pending request. Mirrors the
     * confirmed branch of [resolveActionConfirmation]: same acting-trace
     * reservation, same one-shot removal, same alias learning.
     */
    private suspend fun autoConfirmCallAfterCountdown(result: DispatchResult) {
        delay(callCountdownMillis)
        val request = pending[result.traceId] ?: return
        if (request.stage != PendingStage.CONFIRMATION || request.result == null) return
        synchronized(actionLock) {
            if (actingTrace != null || pending[result.traceId] !== request) return
            actingTrace = result.traceId // reserve before removing pending
            pending.remove(result.traceId, request)
        }
        learnConfirmedAliases(request)
        try {
            track(result.traceId, onCancelled = { Unit }) { execute(request.result) }
        } finally {
            synchronized(actionLock) {
                if (actingTrace == result.traceId) actingTrace = null
            }
        }
    }

    override suspend fun resolveChoice(traceId: String, optionId: String) {
        val request = pending[traceId] ?: return
        if (request.stage != PendingStage.CLARIFICATION) return
        val chosen = request.candidates.singleOrNull { it.entityId == optionId }
        if (chosen == null) {
            pending.remove(traceId)
            emit(
                IslandVisualState.Failed(
                    errorReason = "That choice is no longer available.",
                    recoverable = true,
                ),
            )
            return
        }
        val result = request.resultFor(chosen)
        val updated = request.copy(
            stage = PendingStage.CONFIRMATION,
            result = result,
        )
        pending[traceId] = updated
        if (!maybeAskNumberRegion(updated, result)) emitConfirmation(result)
    }

    override suspend fun resolveClarification(traceId: String, answer: String) {
        require(answer.isNotBlank()) { "clarification answer must not be blank" }
        val request = pending[traceId] ?: return
        if (request.stage == PendingStage.NUMBER_REGION) {
            handleNumberRegionAnswer(request, answer.trim())
            return
        }
        if (request.stage != PendingStage.CLARIFICATION) return

        val matches = try {
            memory.resolveEntity(answer.trim(), limit = 5)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            pending.remove(traceId, request)
            emit(IslandVisualState.Failed("Couldn't look up that contact. Please try again.", true))
            return
        }
        if (matches.size != 1) {
            pending.remove(traceId)
            emit(
                IslandVisualState.Failed(
                    errorReason = "I still can't resolve that to one contact.",
                    recoverable = true,
                ),
            )
            return
        }

        val result = request.resultFor(matches.single())
        val updated = request.copy(
            stage = PendingStage.CONFIRMATION,
            result = result,
            learnNames = (request.learnNames + answer.trim()).distinct(),
        )
        pending[traceId] = updated
        if (!maybeAskNumberRegion(updated, result)) emitConfirmation(result)
    }

    override suspend fun cancelCurrentAction(traceId: String) {
        val hadPending = pending.remove(traceId) != null
        val activeJob = activeJobs[traceId]
        if (!hadPending && activeJob == null) return

        activeJob?.cancel(CancellationException("cancelled by caller"))
        synchronized(actionLock) {
            if (actingTrace == traceId) actingTrace = null
        }
        emit(IslandVisualState.Done("Cancelled."))
    }

    override suspend fun resolveUndo(undoToken: String, accepted: Boolean) {
        val undo = pendingUndo.remove(undoToken) ?: return
        if (!accepted || System.currentTimeMillis() > undo.expiresAtMs) {
            emit(IslandVisualState.Idle)
            return
        }
        when (val outcome = actuator.undo(undo.action)) {
            is ActionResult.EXECUTED ->
                emit(IslandVisualState.Done(outcome.message ?: "Undone."))
            is ActionResult.DENIED ->
                emit(IslandVisualState.Failed(outcome.reason, recoverable = true))
            is ActionResult.FAILED ->
                emit(IslandVisualState.Failed(outcome.reason, recoverable = true))
        }
    }

    override fun submitContextUpdate(context: ActiveContext) {
        activeContext = context
    }

    override fun submitCapabilitySnapshot(snapshot: CapabilitySnapshot) {
        _capabilityState.value = snapshot
    }

    private fun missingCapabilities(type: ActionType): List<String> {
        val capabilities = _capabilityState.value
        return buildList {
            if (type == ActionType.CALL_CONTACT || type == ActionType.SEND_MESSAGE ||
                type == ActionType.OPEN_DEEP_LINK
            ) {
                if (!capabilities.contactsAvailable) add("contacts permission")
            }
            if (type == ActionType.CALL_CONTACT && !capabilities.phoneCallAvailable) {
                add("phone call permission")
            }
        }
    }

    private suspend fun gateOrResolve(
        traceId: String,
        name: String,
        actionType: ActionType,
        args: ActionArgs,
        risk: ActionRisk,
        vocalResponse: String,
    ): DispatchResult {
        val missing = missingCapabilities(actionType)
        if (missing.isNotEmpty()) {
            pending[traceId] = PendingRequest(
                traceId = traceId,
                actionType = actionType,
                args = args,
                risk = risk,
                vocalResponse = vocalResponse,
                candidates = emptyList(),
                stage = PendingStage.PERMISSION,
                result = null,
                name = name,
            )
            emit(IslandVisualState.NeedsInput(
                prompt = "Permission needed: " + missing.joinToString(" and ") + ".",
                traceId = traceId,
                kind = InputKind.PERMISSION,
                risk = risk,
            ))
            return DispatchResult(traceId = traceId, actionType = actionType, args = args,
                confidence = 0.0f, risk = risk, vocalResponse = vocalResponse)
        }
        return resolveContactAction(traceId, name, actionType, args, risk, vocalResponse)
    }

    private fun supersedeOtherRequests(traceId: String) {
        // Only unconfirmed work may be invalidated. Confirmed actions are protected.
        pending.clear()
        activeJobs.entries.filter { it.key != traceId }.forEach { (id, job) ->
            activeJobs.remove(id, job)
            job.cancel(CancellationException("superseded by a new utterance"))
        }
    }

    private suspend fun resolveContactAction(
        traceId: String,
        name: String,
        actionType: ActionType,
        args: ActionArgs,
        risk: ActionRisk,
        vocalResponse: String,
    ): DispatchResult {
        val candidates = try {
            val alternatives = speechAlternatives[traceId].orEmpty().mapNotNull { text ->
                when (val parsed = ReflexParser.parse(text)) {
                    is ParsedIntent.Call -> parsed.name.takeIf { actionType == ActionType.CALL_CONTACT }
                    is ParsedIntent.Message -> parsed.name.takeIf { actionType == ActionType.SEND_MESSAGE }
                    else -> null
                }
            }
            memory.resolveEntityAlternatives((listOf(name) + alternatives).distinct(), limit = 5)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(IslandVisualState.Failed("Couldn't look up contacts. Please try again.", true))
            return DispatchResult(traceId, actionType, args = args, confidence = 0.0f,
                risk = risk, vocalResponse = "Contact lookup failed.")
        }
        val provisional = DispatchResult(
            traceId = traceId,
            actionType = actionType,
            args = args,
            confidence = if (candidates.isEmpty()) 0.0f else candidates.maxOf { it.confidence },
            risk = risk,
            vocalResponse = vocalResponse,
        )

        if (candidates.isEmpty()) {
            emit(
                IslandVisualState.Failed(
                    errorReason = "I couldn't find $name.",
                    recoverable = true,
                ),
            )
            return provisional
        }

        val request = PendingRequest(
            traceId = traceId,
            actionType = actionType,
            args = args,
            risk = risk,
            vocalResponse = vocalResponse,
            candidates = candidates,
            stage = if (candidates.size == 1) PendingStage.CONFIRMATION else PendingStage.CLARIFICATION,
            learnNames = listOf(name),
            result = if (candidates.size == 1) {
                provisional.copy(
                    targets = listOf(candidates.single().toTarget()),
                    confidence = candidates.single().confidence,
                )
            } else {
                null
            },
        )

        pending[traceId] = request
        if (request.stage == PendingStage.CLARIFICATION) {
            emit(
                IslandVisualState.NeedsInput(
                    prompt = "Which $name?",
                    traceId = traceId,
                    kind = InputKind.CLARIFICATION,
                    options = candidates.take(4).map {
                        InputOption(id = it.entityId, label = it.canonicalName)
                    },
                    risk = risk,
                ),
            )
            return provisional
        }

        val result = requireNotNull(request.result)
        if (!maybeAskNumberRegion(request, result)) emitConfirmation(result)
        return result
    }

    /**
     * AP-3: before confirming a WhatsApp chat open, make sure the resolved
     * number can be normalized. A national number the device region can't
     * place is ambiguous: ask which country it is (or for the full number
     * with its country code) instead of confirming a guessed identity.
     * Returns true when a clarification was asked (no confirmation emitted).
     */
    private suspend fun maybeAskNumberRegion(request: PendingRequest, result: DispatchResult): Boolean {
        if (request.actionType != ActionType.OPEN_DEEP_LINK) return false
        if ((request.args as? ActionArgs.DeepLink)?.app != "whatsapp") return false
        val contact = result.targets.filterIsInstance<ActionTarget.Contact>().firstOrNull() ?: return false
        val phone = contact.ref.removePrefix("contact:")
        val normalized = DeepLinks.normalizeWhatsAppNumber(phone, _capabilityState.value.deviceRegionIso)
        if (normalized != null) return false
        val label = contact.displayName ?: request.name ?: "that contact"
        pending[request.traceId] = request.copy(stage = PendingStage.NUMBER_REGION, result = result)
        emit(
            IslandVisualState.NeedsInput(
                prompt = "$label's number is saved without a usable country code. " +
                    "Say the country it belongs to, or the full number with the country code.",
                traceId = request.traceId,
                kind = InputKind.CLARIFICATION,
                // No options: the answer is spoken/typed free text. The UI
                // hides the affirmative control for option-less
                // clarifications and leaves a single Cancel.
                options = emptyList(),
                risk = request.risk,
            ),
        )
        return true
    }

    /**
     * AP-3: consume the user's answer to a number-region question. The answer
     * is explicit evidence from the user: a full international number, a
     * country name, or a national number to try against the device region.
     * Anything else, or a number that still doesn't validate, fails honestly
     * with guidance - no guessed identity is ever used.
     */
    private suspend fun handleNumberRegionAnswer(request: PendingRequest, answer: String) {
        val traceId = request.traceId
        if (answer.lowercase() in NUMBER_REGION_CANCEL_WORDS) {
            if (pending.remove(traceId, request)) emit(IslandVisualState.Done("Cancelled."))
            return
        }
        val result = request.result
        val contact = result?.targets?.filterIsInstance<ActionTarget.Contact>()?.firstOrNull()
        if (result == null || contact == null) {
            pending.remove(traceId, request)
            emit(IslandVisualState.Failed("That request expired. Please try again.", recoverable = true))
            return
        }
        val label = contact.displayName ?: request.name ?: "that contact"
        val storedPhone = contact.ref.removePrefix("contact:")

        val compact = answer.replace(Regex("[\\s().-]"), "")
        val explicit = when {
            // Only "+" is unambiguous. Regional dialing prefixes (00, 011,
            // ...) go through region interpretation below, never a global
            // rewrite.
            compact.startsWith("+") -> compact
            // The user actively stated the full number in answer to the
            // question, so digits-only of international length is explicit
            // here (unlike a stored contact number). Still fully validated.
            compact.all { it.isDigit() } && compact.length in 11..15 -> "+$compact"
            else -> null
        }
        if (explicit != null) {
            val normalized = DeepLinks.normalizeWhatsAppNumber(explicit, null)
            if (normalized == null) {
                pending.remove(traceId, request)
                emit(
                    IslandVisualState.Failed(
                        errorReason = "That number doesn't look complete. " +
                            "Say it with the country code, like +44 7911 123456.",
                        recoverable = true,
                    ),
                )
                return
            }
            // Do not rewrite the contact in memory; only this open uses the
            // user-stated number, shown in the confirmation before it fires.
            val explicitResult = result.copy(
                targets = listOf(contact.copy(ref = "contact:+${normalized.e164Digits}")),
            )
            pending[traceId] = request.copy(stage = PendingStage.CONFIRMATION, result = explicitResult)
            emitConfirmation(explicitResult)
            return
        }

        val spokenRegion = DeepLinks.regionForCountryName(answer)
        val region = spokenRegion ?: _capabilityState.value.deviceRegionIso
        if (spokenRegion == null && !compact.all { it.isDigit() }) {
            pending.remove(traceId, request)
            emit(
                IslandVisualState.Failed(
                    errorReason = "I didn't catch a country or a full number. " +
                        "Try again and say something like \"India\" or \"+91 98765 43210\".",
                    recoverable = true,
                ),
            )
            return
        }
        // A bare-digits answer retried against the device region, or a spoken
        // country: both are region-inferred and shown as assumptions in the
        // confirmation.
        val numberToNormalize = if (spokenRegion != null) storedPhone
        else if (compact.all { it.isDigit() }) answer
        else storedPhone
        val normalized = DeepLinks.normalizeWhatsAppNumber(numberToNormalize, region)
        if (normalized == null) {
            pending.remove(traceId, request)
            val why = if (spokenRegion != null) {
                "I can't make $label's saved number work for ${DeepLinks.regionDisplayName(spokenRegion)}. " +
                    "Say the full number with its country code instead."
            } else {
                "I still can't tell which country applies. " +
                    "Say the country, or the full number with its country code."
            }
            emit(IslandVisualState.Failed(errorReason = why, recoverable = true))
            return
        }
        val retryResult = when {
            spokenRegion != null ->
                // The country came from the user's own answer, so the
                // normalized number is explicit evidence - carried on the
                // target and shown in the confirmation without an assumption.
                result.copy(targets = listOf(contact.copy(ref = "contact:+${normalized.e164Digits}")))
            compact.all { it.isDigit() } ->
                // The user restated the number; use their digits, still
                // flagged as region-inferred in the confirmation.
                result.copy(targets = listOf(contact.copy(ref = "contact:$answer")))
            else -> result
        }
        pending[traceId] = request.copy(stage = PendingStage.CONFIRMATION, result = retryResult)
        emitConfirmation(retryResult)
    }


    private suspend fun emitConfirmation(result: DispatchResult) {
        val label = when (val target = result.targets.firstOrNull()) {
            is ActionTarget.Contact -> target.displayName ?: target.ref
            else -> "this action"
        }
        if (result.actionType == ActionType.CALL_CONTACT) {
            // CT-8: calls never wait on a manual confirm. The countdown is the
            // gate: untouched, the call goes out after the window; cancelled
            // (island abort, a spoken cancel, or a superseding utterance), it
            // never dials. Messages still always show the draft first.
            emit(
                IslandVisualState.NeedsInput(
                    prompt = "Calling $label… tap to cancel",
                    traceId = result.traceId,
                    kind = InputKind.COUNTDOWN,
                    risk = result.risk,
                ),
            )
            autoConfirmCallAfterCountdown(result)
            return
        }
        val prompt = when (result.actionType) {
            ActionType.SEND_MESSAGE -> "Send this message to $label?"
            ActionType.OPEN_DEEP_LINK -> {
                // AP-3: show the normalized number being opened, so a wrong
                // country code is visible before the chat opens. A region
                // completed from the device SIM/locale or a spoken country is
                // an assumption, stated as one - never presented as verified.
                val phone = result.targets.filterIsInstance<ActionTarget.Contact>()
                    .firstOrNull()?.ref?.removePrefix("contact:")
                val normalized = phone?.let {
                    DeepLinks.normalizeWhatsAppNumber(it, _capabilityState.value.deviceRegionIso)
                }
                when {
                    normalized == null -> "Open WhatsApp chat with $label?"
                    normalized.regionInferred -> {
                        val country = normalized.regionIso?.let(DeepLinks::regionDisplayName) ?: "that country"
                        "Open WhatsApp chat with $label (+${normalized.e164Digits})? " +
                            "I'm assuming $country - say no if that's wrong."
                    }
                    else -> "Open WhatsApp chat with $label (+${normalized.e164Digits})?"
                }
            }
            else -> "Continue?"
        }
        emit(
            IslandVisualState.NeedsInput(
                prompt = prompt,
                traceId = result.traceId,
                kind = InputKind.CONFIRMATION,
                risk = result.risk,
            ),
        )
    }

    private suspend fun execute(result: DispatchResult) {
        synchronized(actionLock) { actingTrace = result.traceId }
        try {
            emit(
                IslandVisualState.Acting(
                    actionSummary = when (result.actionType) {
                        ActionType.OPEN_APP -> "Opening app"
                        ActionType.CALL_CONTACT -> "Starting call"
                        ActionType.SEND_MESSAGE -> "Sending message"
                        ActionType.MUSIC_CONTROL -> "Controlling music"
                        ActionType.MUSIC_FEEDBACK -> "Noting your taste"
                        ActionType.CREATE_REMINDER -> "Setting reminder"
                        ActionType.DELETE_FILES -> "Cleaning storage"
                        else -> "Acting"
                    },
                ),
            )
            val actionResult = try {
                withTimeout(actuatorTimeoutMillis) { actuator.execute(result) }
            } catch (_: TimeoutCancellationException) {
                val message = if (result.risk == ActionRisk.LOW) {
                    "That took too long. Please try again."
                } else {
                    "I couldn't confirm that finished. Check before trying again."
                }
                emit(IslandVisualState.Failed(message, recoverable = true))
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emit(IslandVisualState.Failed(
                    errorReason = "The action could not be completed. Please try again.",
                    recoverable = true,
                ))
                return
            }
            when (actionResult) {
                is ActionResult.EXECUTED -> {
                    val feedback = actionResult.message ?: result.vocalResponse ?: "Done."
                    val undo = actionResult.undoAction
                    if (undo != null) {
                        // TM-7: surface the undo chip instead of a plain Done.
                        val token = UUID.randomUUID().toString()
                        pendingUndo[token] = PendingUndo(
                            action = undo,
                            expiresAtMs = System.currentTimeMillis() + actionResult.undoWindowMs,
                        )
                        emit(
                            IslandVisualState.Undoable(
                                feedbackLine = feedback,
                                undoToken = token,
                                windowMs = actionResult.undoWindowMs,
                            ),
                        )
                    } else {
                        emit(IslandVisualState.Done(feedbackLine = feedback))
                    }
                }
                is ActionResult.DENIED -> emit(
                    IslandVisualState.Failed(actionResult.reason, recoverable = true),
                )
                is ActionResult.FAILED -> emit(
                    IslandVisualState.Failed(actionResult.reason, recoverable = true),
                )
            }
        } finally {
            synchronized(actionLock) {
                if (actingTrace == result.traceId) actingTrace = null
            }
        }
    }

    /**
     * CT-7: when the owner rejects a contact confirmation, permanently record
     * the rejected spoken-form -> contact mapping so it is deprioritized on
     * every future resolution. Best-effort: a store failure never blocks the
     * cancellation.
     */
    private suspend fun recordRejectedContact(request: PendingRequest) {
        if (request.actionType != ActionType.CALL_CONTACT &&
            request.actionType != ActionType.SEND_MESSAGE
        ) {
            return
        }
        val target = request.result?.targets
            ?.filterIsInstance<ActionTarget.Contact>()
            ?.firstOrNull() ?: return
        val alias = (request.learnNames.firstOrNull() ?: request.name).orEmpty().trim()
        if (alias.isEmpty()) return
        try {
            memory.recordContactCorrection(alias, target.ref)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // best-effort recording; cancellation already happened
        }
    }

    /**
     * CT-7: after the owner confirms a contact pick, persist every spoken form
     * they used (original utterance plus any clarification answer) as an alias
     * of the confirmed contact, unless it already is the canonical name.
     * Learning is best-effort: a store failure never blocks the action.
     */
    private suspend fun learnConfirmedAliases(request: PendingRequest) {
        if (request.actionType != ActionType.CALL_CONTACT &&
            request.actionType != ActionType.SEND_MESSAGE
        ) {
            return
        }
        val chosenRef = request.result?.targets
            ?.filterIsInstance<ActionTarget.Contact>()?.firstOrNull()?.ref ?: return
        val chosen = request.candidates.firstOrNull { it.entityId == chosenRef } ?: return
        val canonicalNorm = normalizeSpokenForm(chosen.canonicalName)
        request.learnNames
            .map(::normalizeSpokenForm)
            .filter { it.isNotEmpty() && it != canonicalNorm }
            .distinct()
            .forEach { spoken ->
                try {
                    memory.learnEntityAlias(spoken, chosen.entityId, chosen.canonicalName)
                } catch (_: Exception) {
                    // best-effort learning; never block a confirmed action
                }
            }
    }

    private val NUMBER_REGION_CANCEL_WORDS = setOf(
        "cancel", "never mind", "nevermind", "forget it", "stop", "no", "nah",
    )

    private fun normalizeSpokenForm(text: String): String =
        text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private suspend fun emit(state: IslandVisualState) {
        _islandState.value = state
        yield()
    }

    private suspend fun <T> track(
        traceId: String,
        onCancelled: () -> T,
        block: suspend () -> T,
    ): T = supervisorScope {
        // A dedicated supervised child can be cancelled without cancelling the caller's Job.
        val task = async(start = CoroutineStart.LAZY) { block() }
        activeJobs[traceId] = task
        try {
            task.start()
            task.await()
        } catch (cancelled: CancellationException) {
            if (!task.isCancelled) throw cancelled // caller cancellation still propagates
            onCancelled()
        } finally {
            activeJobs.remove(traceId, task)
        }
    }

    private data class PendingRequest(
        val traceId: String,
        val actionType: ActionType,
        val args: ActionArgs,
        val risk: ActionRisk,
        val vocalResponse: String,
        val candidates: List<EntityCandidate>,
        val stage: PendingStage,
        val result: DispatchResult?,
        val name: String? = null,
        /** CT-7: spoken forms to persist as aliases once the user confirms a pick. */
        val learnNames: List<String> = emptyList(),
    ) {
        fun resultFor(candidate: EntityCandidate): DispatchResult = DispatchResult(
            traceId = traceId,
            actionType = actionType,
            targets = listOf(candidate.toTarget()),
            args = args,
            confidence = candidate.confidence,
            risk = risk,
            vocalResponse = vocalResponse,
        )
    }

    private enum class PendingStage {
        PERMISSION,
        CLARIFICATION,
        /** AP-3: waiting for the user to name a country or give the full international number. */
        NUMBER_REGION,
        CONFIRMATION,
    }
}

private fun EntityCandidate.toTarget(): ActionTarget.Contact =
    ActionTarget.Contact(ref = entityId, displayName = canonicalName)

private fun formatClock(hour: Int, minute: Int): String {
        val ampm = if (hour < 12) "AM" else "PM"
        val h12 = when (val h = hour % 12) { 0 -> 12; else -> h }
        return "%d:%02d %s".format(h12, minute, ampm)
    }

private fun formatDuration(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} hour(s)"
        seconds % 60 == 0 -> "${seconds / 60} minute(s)"
        else -> "$seconds second(s)"
    }


private sealed interface ParsedIntent {
    data class Call(
        val name: String,
        val speaker: Boolean,
    ) : ParsedIntent

    data class Message(
        val name: String,
        val text: String,
        val channel: MessageChannel = MessageChannel.AUTO,
    ) : ParsedIntent

    data class OpenApp(
        val app: String,
    ) : ParsedIntent

    /** AP-3: open the app's system settings page. */
    data class OpenAppSettings(
        val app: String,
    ) : ParsedIntent

    /** AP-3: search for a place inside Maps. */
    data class OpenMapSearch(
        val query: String,
    ) : ParsedIntent

    /** AP-3: open the WhatsApp chat screen with a contact. */
    data class OpenWhatsAppChat(
        val name: String,
    ) : ParsedIntent

    data class Music(
        val command: MusicCommand,
        val query: String? = null,
    ) : ParsedIntent

    /** MU-8: feedback on the currently playing track, fed to the taste model. */
    data class TrackFeedback(
        val sentiment: MusicFeedback,
    ) : ParsedIntent

    data class Reminder(
        val title: String,
        val triggerAtEpochMillis: Long,
        val recurrence: HinglishTimeParser.Recurrence = HinglishTimeParser.Recurrence.NONE,
        /** TM-9: contact whose reply cancels this reminder, when conditional. */
        val conditionWatchContact: String? = null,
    ) : ParsedIntent

    /** NT-5: "kya notifications aaye?" — answered locally from the buffer. */
    data class NotificationQuery(val appFilter: String? = null) : ParsedIntent

    data object CleanStorage : ParsedIntent

    /** TM-2: system Clock alarm. */
    data class Alarm(val hour: Int, val minute: Int) : ParsedIntent

    /** TM-3: system Clock countdown timer. */
    data class TimerIntent(val lengthSeconds: Int) : ParsedIntent

    /** TM-7: cancel pending reminder(s) matching a clock time or title. */
    data class CancelReminder(val hour: Int?, val minute: Int?, val titleFragment: String?) : ParsedIntent

    /** TM-7: read out pending reminders. */
    data object ListReminders : ParsedIntent

    /** TM-7: dismiss a matching upcoming alarm in the Clock app. */
    data class DismissAlarm(val hour: Int, val minute: Int) : ParsedIntent

    data object Unknown : ParsedIntent

    /** Small talk answered locally with a canned reply (PRD BR-1): no cloud, instant. */
    data class Chat(val reply: String) : ParsedIntent
}

private object ReflexParser {
    private val message = Regex(
        pattern = """^(?:message|send message to|sms|text|send sms to)\s+([^:]+):\s*(.+)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val open = Regex(
        pattern = """^(?:open|launch|start|run|kholo)\s+(?:the\s+)?(?:app\s+)?(.+)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val reminderInTime = Regex(
        pattern = """^remind\s+me\s+to\s+(.+?)\s+in\s+(\d+)\s*(minute|minutes|min|mins|hour|hours|hr|hrs|second|seconds|sec|secs)$""",
        option = RegexOption.IGNORE_CASE,
    )

    private val trailingFillers = listOf("please", "for me", "now", "app", "application")

    /** Strips trailing punctuation and whole-word fillers ("please", "for me", "now") from an app name. */
    private val openSettingsEn = Regex(
        """^open\s+(.+?)\s+settings$""",
        RegexOption.IGNORE_CASE,
    )
    private val openSettingsHi = Regex(
        """^(.+?)\s+(?:ki\s+)?settings\s*(?:kholo|khol do|open karo)$""",
        RegexOption.IGNORE_CASE,
    )

    // AP-3: deep links into known apps. Must be checked before the generic
    // open forms: "open whatsapp chat with Rahul" is not "open the app
    // named 'whatsapp chat with Rahul'".
    private val mapsSearchEn = listOf(
        Regex("""^(?:search|find)\s+(?:in\s+|on\s+)?(?:google\s+)?maps?\s+for\s+(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^open\s+(?:google\s+)?maps?\s+(?:and\s+)?(?:search|find)\s+(?:for\s+)?(.+)$""", RegexOption.IGNORE_CASE),
    )
    private val mapsSearchHi = listOf(
        Regex("""^(.+?)\s+(?:maps?|map)\s+(?:mein|me|par|pe)\s+(?:dhundo|dhundho|dikhao|search\s+karo)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:maps?|map)\s+(?:mein|me|par|pe)\s+(.+?)\s+(?:dhundo|dhundho|dikhao|search\s+karo)$""", RegexOption.IGNORE_CASE),
    )
    private val whatsappChatPatterns = listOf(
        Regex("""^open\s+whatsapp\s+chat\s+with\s+(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^open\s+chat\s+with\s+(.+?)\s+(?:on|in)\s+whatsapp$""", RegexOption.IGNORE_CASE),
        Regex("""^whatsapp\s+(?:par|pe)\s+(.+?)\s+(?:se|ka|ki)\s+chat\s+(?:kholo|khol do)$""", RegexOption.IGNORE_CASE),
    )

    /** AP-3: deep links into Maps search and WhatsApp chat. */
    private fun parseDeepLink(text: String): ParsedIntent? {
        for (pattern in whatsappChatPatterns) {
            pattern.matchEntire(text)?.let { match ->
                val name = match.groupValues[1].trim().trimEnd('.', '!', '?', ',').trim()
                if (name.isNotBlank()) return ParsedIntent.OpenWhatsAppChat(name)
            }
        }
        for (pattern in mapsSearchEn + mapsSearchHi) {
            pattern.matchEntire(text)?.let { match ->
                val query = match.groupValues[1].trim().trimEnd('.', '!', '?', ',').trim()
                if (query.isNotBlank()) return ParsedIntent.OpenMapSearch(query)
            }
        }
        return null
    }

    /** AP-3: "open whatsapp settings" / "whatsapp ki settings kholo". */
    private fun parseOpenAppSettings(text: String): ParsedIntent? {
        val raw = openSettingsEn.matchEntire(text)?.groupValues?.get(1)
            ?: openSettingsHi.matchEntire(text)?.groupValues?.get(1)
            ?: return null
        val app = cleanAppName(raw)
        return if (app.isNotBlank()) ParsedIntent.OpenAppSettings(app) else null
    }

    private fun cleanAppName(rawName: String): String {
        var app = rawName.trim()
        var changed = true
        while (changed) {
            changed = false
            val noPunct = app.trimEnd('.', '!', '?', ',', ';').trim()
            if (noPunct != app) {
                app = noPunct
                changed = true
            }
            for (filler in trailingFillers) {
                // Whole word only: "open Snow" must not lose its "now".
                if (app.endsWith(" $filler", ignoreCase = true)) {
                    app = app.dropLast(filler.length + 1).trim()
                    changed = true
                }
            }
        }
        return app
    }

    /**
     * Canned small talk (PRD BR-1). Replies stay short, calm and slightly dry
     * (PRD principle 10); Hindi/Hinglish inputs get Hinglish replies.
     */
    private val chatReplies: List<Pair<Regex, String>> = listOf(
        Regex("^(?:hi+|hello+|hey+|yo)(?: raijin)?$", RegexOption.IGNORE_CASE) to
            "Hey. What do you need?",
        Regex("^(?:namaste|namaskar|ram ram|salaam)(?: raijin)?$", RegexOption.IGNORE_CASE) to
            "Namaste. Boliye, kya karna hai?",
        Regex("^how are you(?: doing)?$", RegexOption.IGNORE_CASE) to
            "Running fine. What do you need?",
        Regex("^(?:kaise ho|kya haal hai|kya haal)(?: raijin)?$", RegexOption.IGNORE_CASE) to
            "Sab badhiya. Batao, kya karna hai?",
        Regex("^(?:thanks|thank you|thankyou)(?: raijin)?$", RegexOption.IGNORE_CASE) to
            "Anytime.",
        Regex("^(?:shukriya|dhanyavaad|dhanyawad)$", RegexOption.IGNORE_CASE) to
            "Koi baat nahi.",
        Regex("^good morning$", RegexOption.IGNORE_CASE) to
            "Good morning. What's first today?",
        Regex("^good (?:afternoon|evening)$", RegexOption.IGNORE_CASE) to
            "Good day. What do you need?",
        Regex("^good night$", RegexOption.IGNORE_CASE) to
            "Good night.",
        Regex("^(?:bye|bye bye|see you|alvida)$", RegexOption.IGNORE_CASE) to
            "Bye.",
        Regex("^who are you$", RegexOption.IGNORE_CASE) to
            "Raijin. Your on-phone assistant: calls, messages, apps, reminders, music.",
        Regex("^(?:what can you do|help)(?: me)?$", RegexOption.IGNORE_CASE) to
            "Try: call someone, message someone, open an app, set a reminder, or play music.",
    )

    fun parse(raw: String, now: ZonedDateTime = ZonedDateTime.now()): ParsedIntent {
        val text = raw.trim()

        message.matchEntire(text)?.let { match ->
            val name = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            val channel = if (text.startsWith("sms", ignoreCase = true) || text.startsWith("send sms", ignoreCase = true)) {
                MessageChannel.SMS
            } else {
                MessageChannel.AUTO
            }
            if (name.isNotBlank() && body.isNotBlank()) {
                return ParsedIntent.Message(name, body, channel)
            }
        }

        // AP-3: "<app> settings" must be checked before the generic open forms.
        parseOpenAppSettings(text)?.let { return it }

        // AP-3: deep links ("whatsapp chat with X", "search maps for X")
        // before generic open, or the app name swallows the whole phrase.
        parseDeepLink(text)?.let { return it }

        Regex("""^(.+?)\s+(?:kholo|khol do|open karo)$""", RegexOption.IGNORE_CASE)
            .matchEntire(text)?.let { match ->
                val app = cleanAppName(match.groupValues[1])
                if (app.isNotBlank()) return ParsedIntent.OpenApp(app)
            }
        open.matchEntire(text)?.let { match ->
            val app = cleanAppName(match.groupValues[1])
            if (app.isNotBlank()) return ParsedIntent.OpenApp(app)
        }


        if (text.startsWith("call ", ignoreCase = true)) {
            var rest = text.substring(5).trim()
            val speaker = rest.endsWith(" on speaker", ignoreCase = true)
            if (speaker) {
                rest = rest.dropLast(" on speaker".length).trim()
            }
            if (rest.isNotBlank()) return ParsedIntent.Call(rest, speaker)
        }

        // Music controls (MU-4: English + Hindi/Hinglish transport forms)
        if (text.equals("pause", ignoreCase = true) || text.equals("pause music", ignoreCase = true) || text.equals("stop music", ignoreCase = true) ||
            text.equals("ruko", ignoreCase = true) || text.equals("rok do", ignoreCase = true) ||
            text.equals("music ruko", ignoreCase = true) || text.equals("band karo", ignoreCase = true) ||
            text.equals("music band karo", ignoreCase = true) || text.equals("gaana band karo", ignoreCase = true)
        ) {
            return ParsedIntent.Music(MusicCommand.PAUSE)
        }
        if (text.equals("resume", ignoreCase = true) || text.equals("resume music", ignoreCase = true) ||
            text.equals("waps chalao", ignoreCase = true) || text.equals("dobara chalu karo", ignoreCase = true)
        ) {
            return ParsedIntent.Music(MusicCommand.RESUME)
        }
        if (text.equals("next", ignoreCase = true) || text.equals("next song", ignoreCase = true) || text.equals("skip", ignoreCase = true) || text.equals("skip song", ignoreCase = true) ||
            text.equals("agla", ignoreCase = true) || text.equals("agla gaana", ignoreCase = true) ||
            text.equals("aage karo", ignoreCase = true) || text.equals("next gaana", ignoreCase = true)
        ) {
            return ParsedIntent.Music(MusicCommand.NEXT)
        }
        if (text.equals("previous", ignoreCase = true) || text.equals("previous song", ignoreCase = true) ||
            text.equals("pichhla", ignoreCase = true) || text.equals("pichhla gaana", ignoreCase = true) ||
            text.equals("peeche wala gaana", ignoreCase = true) || text.equals("pichla gaana", ignoreCase = true)
        ) {
            return ParsedIntent.Music(MusicCommand.PREVIOUS)
        }
        if (text.equals("louder", ignoreCase = true) || text.equals("volume up", ignoreCase = true) ||
            text.equals("volume badhao", ignoreCase = true) || text.equals("awaaz badhao", ignoreCase = true) ||
            text.equals("aawaz badhao", ignoreCase = true) || text.equals("awaaz badha do", ignoreCase = true)
        ) {
            return ParsedIntent.Music(MusicCommand.VOLUME_UP)
        }
        if (text.equals("quieter", ignoreCase = true) || text.equals("volume down", ignoreCase = true) ||
            text.equals("volume ghatao", ignoreCase = true) || text.equals("awaaz kam karo", ignoreCase = true) ||
            text.equals("aawaz kam karo", ignoreCase = true) || text.equals("awaaz ghat do", ignoreCase = true)
        ) {
            return ParsedIntent.Music(MusicCommand.VOLUME_DOWN)
        }
        if (text.equals("repeat", ignoreCase = true) || text.equals("repeat karo", ignoreCase = true) ||
            text.equals("gaana repeat karo", ignoreCase = true) || text.equals("phir se chalao", ignoreCase = true)
        ) {
            return ParsedIntent.Music(MusicCommand.REPEAT)
        }
        if (text.equals("what song is this", ignoreCase = true) || text.equals("now playing", ignoreCase = true) ||
            text.equals("ye gaana kya hai", ignoreCase = true) || text.equals("yeh gaana kya hai", ignoreCase = true) ||
            text.equals("kya chal raha hai", ignoreCase = true) || text.equals("kaunsa gaana chal raha hai", ignoreCase = true)
        ) {
            return ParsedIntent.Music(MusicCommand.NOW_PLAYING)
        }
        // MU-7: "play something" - parsed before the generic "play " query
        // rule so "play something" never becomes a query for "something".
        if (text.equals("play something", ignoreCase = true) || text.equals("play anything", ignoreCase = true) ||
            text.equals("play something for me", ignoreCase = true) || text.equals("surprise me", ignoreCase = true) ||
            text.equals("kuch chalao", ignoreCase = true) || text.equals("kuch bhi chalao", ignoreCase = true) ||
            text.equals("kuch sunao", ignoreCase = true) || text.equals("kuch bhi sunao", ignoreCase = true)
        ) {
            return ParsedIntent.Music(MusicCommand.PLAY_SOMETHING)
        }

        // MU-8: feedback on the current track. Parsed before the "chalao"
        // play-query forms so "ye mat chalao" / "aisa hi chalao" never
        // become play queries.
        if (text.equals("pasand aaya", ignoreCase = true) || text.equals("pasand aya", ignoreCase = true) ||
            text.equals("accha laga", ignoreCase = true) || text.equals("achha laga", ignoreCase = true) ||
            text.equals("i like this", ignoreCase = true) || text.equals("i like this song", ignoreCase = true) ||
            text.equals("love this song", ignoreCase = true) || text.equals("gaana pasand aaya", ignoreCase = true)
        ) {
            return ParsedIntent.TrackFeedback(MusicFeedback.LIKED)
        }
        if (text.equals("ye mat chalao", ignoreCase = true) || text.equals("yeh mat chalao", ignoreCase = true) ||
            text.equals("ye gaana mat chalao", ignoreCase = true) || text.equals("yeh gaana mat chalao", ignoreCase = true) ||
            text.equals("don't play this", ignoreCase = true) || text.equals("do not play this", ignoreCase = true) ||
            text.equals("don't play this song", ignoreCase = true)
        ) {
            return ParsedIntent.TrackFeedback(MusicFeedback.DISLIKED)
        }
        if (text.equals("aur aisa", ignoreCase = true) || text.equals("aur aise", ignoreCase = true) ||
            text.equals("aisa hi chalao", ignoreCase = true) || text.equals("aur aisa chalao", ignoreCase = true) ||
            text.equals("more like this", ignoreCase = true) || text.equals("play more like this", ignoreCase = true)
        ) {
            return ParsedIntent.TrackFeedback(MusicFeedback.MORE_LIKE_THIS)
        }

        // "play other / another / different music": move on to something else.
        if (otherMusic.matches(text.trim())) {
            return ParsedIntent.Music(MusicCommand.NEXT)
        }
        // "play a video (of X)", "play X video", "youtube X": YouTube, not the music app.
        parseVideoRequest(text.trim())?.let { return it }

        if (text.startsWith("play ", ignoreCase = true)) {
            val query = text.substring(5).trim()
            if (query.equals("music", ignoreCase = true)) {
                return ParsedIntent.Music(MusicCommand.PLAY)
            }
            if (query.isNotBlank()) {
                return ParsedIntent.Music(MusicCommand.PLAY_QUERY, query)
            }
        }
        // Hindi play query: "<song/artist> chalao" / "gaana chalao" forms.
        if (text.equals("gaana chalao", ignoreCase = true) || text.equals("music chalao", ignoreCase = true)) {
            return ParsedIntent.Music(MusicCommand.PLAY)
        }
        if (text.endsWith(" chalao", ignoreCase = true)) {
            val query = text.dropLast(" chalao".length).trim()
            if (query.isNotBlank() && query.split(' ').size <= 5) {
                return ParsedIntent.Music(MusicCommand.PLAY_QUERY, query)
            }
        }

        // Storage cleaning
        if (text.equals("clean storage", ignoreCase = true) || text.equals("clean cache", ignoreCase = true) || text.equals("clean junk", ignoreCase = true)) {
            return ParsedIntent.CleanStorage
        }

        // TM-2 alarms + TM-3 timers (checked before reminders; disjoint keywords)
        parseAlarm(text, now)?.let { return it }
        parseTimer(text, now)?.let { return it }
        // TM-7 cancel/list
        parseCancel(text, now)?.let { return it }
        parseListReminders(text)?.let { return it }
        parseNotificationQuery(text)?.let { return it }

        // Reminders: "remind me to call Mom in 10 minutes"
        reminderInTime.matchEntire(text)?.let { match ->
            val title = match.groupValues[1].trim()
            val amount = match.groupValues[2].toLongOrNull() ?: 1L
            val unit = match.groupValues[3].lowercase()
            val durationMs = when {
                unit.startsWith("sec") -> amount * 1000L
                unit.startsWith("hour") || unit.startsWith("hr") -> amount * 3600_000L
                else -> amount * 60_000L
            }
            if (title.isNotBlank()) {
                return ParsedIntent.Reminder(title, System.currentTimeMillis() + durationMs)
            }
        }

        // TM-4: general reminder phrases routed through the deterministic
        // Hinglish/English time parser ("remind me to call maa kal subah 6 baje",
        // "mujhe dawai yaad dilana raat 9 baje", "set a reminder to pay rent parso").
        parseGeneralReminder(text, now)?.let { return it }

        // Small talk: local canned replies, never the cloud (PRD BR-1)
        val chatText = text.trimEnd('.', '!', '?', ',').trim()
        for ((pattern, reply) in chatReplies) {
            if (pattern.matches(chatText)) return ParsedIntent.Chat(reply)
        }

        return ParsedIntent.Unknown
    }

    // ------------------------------------------------------------------
    // TM-2 alarms / TM-3 timers
    // ------------------------------------------------------------------

    private val alarmSuffix = Regex(
        """^(.+?)\s+ka\s+alarm\s*(?:lagao|laga do|laga dena|set karo|set kar do)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val alarmPrefix = Regex(
        """^(?:alarm\s+(?:lagao|laga do|set karo|set kar do)\s+|set\s+(?:an?\s+)?alarm\s+(?:for|at)\s+)(.+?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val wakeMe = Regex(
        """^wake\s+me(?:\s+up)?(?:\s+at)?\s+(.+?)$""",
        RegexOption.IGNORE_CASE,
    )

    private val bareClock = Regex("""^(\d{1,2}):(\d{2})\s*(am|pm|a\.m\.|p\.m\.)?$""", RegexOption.IGNORE_CASE)

    private val otherMusic = Regex(
        """^(?:play\s+)?(?:some\s+)?(?:other|another|different|new)\s+(?:music|song|songs|track|gaana)(?:\s+please)?$|^(?:kuch\s+aur|dusra|doosra|dusri|koi\s+aur)\s+(?:gaana|song|music)?\s*(?:chalao|lagao|sunao)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val videoLead = Regex(
        """^(?:play|watch|show)\s+(?:me\s+)?(?:a\s+|the\s+|some\s+)?videos?(?:\s+(?:of|on|for|about))?\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val videoTrail = Regex(
        """^(?:play|watch)\s+(.+?)\s+videos?$|^(.+?)\s+(?:ka\s+)?video\s+(?:chalao|lagao|dikhao)$""",
        RegexOption.IGNORE_CASE,
    )
    private val youtubeLead = Regex(
        """^(?:youtube|yt)\s+(?:pe\s+|par\s+|on\s+)?(.+?)(?:\s+(?:chalao|lagao|play))?$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseVideoRequest(text: String): ParsedIntent? {
        videoLead.matchEntire(text)?.let { m ->
            val q = m.groupValues[1].trim()
            return ParsedIntent.Music(MusicCommand.PLAY_VIDEO, q.ifBlank { null })
        }
        videoTrail.matchEntire(text)?.let { m ->
            val q = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            if (q.isNotBlank()) return ParsedIntent.Music(MusicCommand.PLAY_VIDEO, q)
        }
        youtubeLead.matchEntire(text)?.let { m ->
            val q = m.groupValues[1].trim()
            if (q.isNotBlank() && q.lowercase() !in setOf("app", "music", "music app", "studio", "kids", "tv")) {
                return ParsedIntent.Music(MusicCommand.PLAY_VIDEO, q)
            }
        }
        return null
    }

    private fun parseAlarm(text: String, now: ZonedDateTime): ParsedIntent? {
        val timePart = alarmSuffix.matchEntire(text)?.groupValues?.get(1)
            ?: alarmPrefix.matchEntire(text)?.groupValues?.get(1)
            ?: wakeMe.matchEntire(text)?.groupValues?.get(1)
            ?: return null

        // Bare "6:30" (HinglishTimeParser needs am/pm or baje): resolve AM/PM
        // by next occurrence - an alarm is always for the future.
        bareClock.matchEntire(timePart.trim())?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (min > 59) return null
            val meridiem = m.groupValues[3].lowercase().replace(".", "")
            if (meridiem.isNotEmpty()) {
                if (h !in 1..12) return null
                val h24 = (h % 12) + if (meridiem == "pm") 12 else 0
                return ParsedIntent.Alarm(h24, min)
            }
            if (h in 13..23) return ParsedIntent.Alarm(h, min)
            if (h !in 1..12) return null
            val h12 = h % 12
            val amHour = h12
            val pmHour = h12 + 12
            val nowMin = now.hour * 60 + now.minute
            val chosen = when {
                amHour * 60 + min > nowMin -> amHour
                pmHour * 60 + min > nowMin -> pmHour
                else -> amHour // tomorrow morning
            }
            return ParsedIntent.Alarm(chosen, min)
        }

        val parsed = HinglishTimeParser.parse(timePart, now) ?: return null
        val t = parsed.triggerAt
        return ParsedIntent.Alarm(t.hour, t.minute)
    }

    private val timerEn = Regex(
        """^(?:set\s+(?:a\s+)?timer\s+for\s+|timer\s+for\s+)(.+?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val timerHi = Regex(
        """^(.+?)\s+ka\s+timer\s*(?:lagao|laga do|laga dena|set karo|set kar do)?$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseTimer(text: String, now: ZonedDateTime): ParsedIntent? {
        val durPart = timerEn.matchEntire(text)?.groupValues?.get(1)
            ?: timerHi.matchEntire(text)?.groupValues?.get(1)
            ?: return null
        val durationMs = parseDurationMs(durPart, now) ?: return null
        val seconds = (durationMs / 1000L).toInt()
        if (seconds <= 0 || seconds > 86_400) return null
        return ParsedIntent.TimerIntent(seconds)
    }

    /** Bare durations ("5 minutes", "aadha ghanta") via the shared parser's relative forms. */
    private fun parseDurationMs(text: String, now: ZonedDateTime): Long? {
        val candidates = listOf(text, "in $text", "$text baad")
        for (c in candidates) {
            val parsed = HinglishTimeParser.parse(c, now) ?: continue
            if (parsed.recurrence != HinglishTimeParser.Recurrence.NONE) continue
            val delta = parsed.triggerAt.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()
            if (delta > 0 && delta <= 86_400_000L) return delta
        }
        return null
    }

    // ------------------------------------------------------------------
    // TM-7 cancel / list
    // ------------------------------------------------------------------

    private val cancelReminderTime = Regex(
        """^cancel\s+(?:my\s+)?(\d{1,2})(?::(\d{2}))?\s*(?:(am|pm))?(?:\s*o'clock)?\s*(?:baje\s*(?:ka\s*)?)?reminder$""",
        RegexOption.IGNORE_CASE,
    )
    private val cancelReminderTitle = Regex(
        """^(?:cancel|delete|remove)\s+(?:my\s+|the\s+)?reminder\s+(?:to|about|for)\s+(.+?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val cancelAlarmTime = Regex(
        """^cancel\s+(?:my\s+)?(\d{1,2})(?::(\d{2}))?\s*(?:(am|pm))?(?:\s*o'clock)?(?:\s*baje\s*(?:ka\s*)?)?alarm$""",
        RegexOption.IGNORE_CASE,
    )
    private val listReminders = Regex(
        """^(?:what reminders do i have|list my reminders|show my reminders|mere reminders batao|reminders batao)\??$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseCancel(text: String, now: ZonedDateTime): ParsedIntent? {
        cancelReminderTime.matchEntire(text)?.let { m ->
            val h = resolveQueryHour(m.groupValues[1].toIntOrNull() ?: return null, m.groupValues[3], now) ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: 0
            return ParsedIntent.CancelReminder(h, min, null)
        }
        cancelReminderTitle.matchEntire(text)?.let { m ->
            val frag = m.groupValues[1].trim()
            if (frag.isNotBlank()) return ParsedIntent.CancelReminder(null, null, frag)
        }
        cancelAlarmTime.matchEntire(text)?.let { m ->
            val h = resolveQueryHour(m.groupValues[1].toIntOrNull() ?: return null, m.groupValues[3], now) ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: 0
            return ParsedIntent.DismissAlarm(h, min)
        }
        return null
    }

    /** "6 o'clock reminder" - ambiguous AM/PM resolved like alarms: next occurrence. */
    private fun resolveQueryHour(h: Int, meridiem: String?, now: ZonedDateTime): Int? {
        val mer = meridiem?.lowercase()?.takeIf { it.isNotEmpty() }
        if (mer != null) {
            if (h !in 1..12) return null
            return (h % 12) + if (mer == "pm") 12 else 0
        }
        if (h in 13..23) return h
        if (h !in 1..12) return null
        val h12 = h % 12
        return if (h12 * 60 > now.hour * 60 + now.minute) h12 else h12 + 12
    }

    private fun parseListReminders(text: String): ParsedIntent? =
        if (listReminders.matches(text)) ParsedIntent.ListReminders else null

    // ------------------------------------------------------------------
    // NT-5 notification questions (answered locally from the buffer)
    // ------------------------------------------------------------------

    private val notificationQueryGeneral = Regex(
        """^(?:kya notifications? aaye|notifications? aaye kya|mere notifications? batao|what notifications? (?:came|did i get|have i got)|any new notifications?|check my notifications?)\??$""",
        RegexOption.IGNORE_CASE,
    )
    private val notificationQueryAppHindi = Regex(
        """^([a-zA-Z][a-zA-Z0-9 ]{0,24}?)\s+pe\s+(?:kisne bheja|kya aaya)\??$""",
        RegexOption.IGNORE_CASE,
    )
    private val notificationQueryAppEnglish = Regex(
        """^(?:what came on|any) ([a-zA-Z][a-zA-Z0-9 ]{0,24}?)(?:\s+messages?)?\??$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Anchored full-string patterns only, so they cannot shadow reminders or
     * other commands. Generic words ("new", "messages") after "any" are a
     * general query, not an app filter.
     */
    private val genericQueryWords = setOf("new", "message", "messages", "notification", "notifications")

    private fun parseNotificationQuery(text: String): ParsedIntent? {
        if (notificationQueryGeneral.matches(text)) return ParsedIntent.NotificationQuery(null)
        notificationQueryAppHindi.matchEntire(text)?.let { m ->
            val app = m.groupValues[1].trim()
            if (app.isNotBlank()) return ParsedIntent.NotificationQuery(app)
        }
        notificationQueryAppEnglish.matchEntire(text)?.let { m ->
            val app = m.groupValues[1].trim()
            if (app.isNotBlank() && app.lowercase() !in genericQueryWords) {
                return ParsedIntent.NotificationQuery(app)
            }
            return ParsedIntent.NotificationQuery(null)
        }
        return null
    }

    // ------------------------------------------------------------------
    // TM-4 general reminder parsing
    // ------------------------------------------------------------------

    private val reminderPrefixes = listOf(
        "remind me to ", "remind me ", "set a reminder to ", "set a reminder for ",
        "set reminder to ", "set reminder for ", "reminder set karo ", "reminder lagao ",
    )
    private val hindiReminderMarkers = listOf(" yaad dilana", " yaad dila dena", " yaad dila do")

    /** TM-9: "if <name> doesn't reply by <time>" inside a reminder request. */
    private val conditionalReplyRegex = Regex(
        """if\s+([A-Za-z][A-Za-z .'-]{0,40}?)\s+(?:doesn't|does not|didn't|did not)\s+reply\s+(?:by|till|before)\s+(.+)""",
        setOf(RegexOption.IGNORE_CASE),
    )

    /** Removes the words that carried the date-time meaning from the title. */
    private fun stripTimeFragment(title: String, fragment: String): String {
        var t = title
        for (word in fragment.split(' ')) {
            if (word.isBlank()) continue
            t = t.replace(Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE), " ")
        }
        var cleaned = t.replace(Regex("\\s+"), " ").trim().trimEnd(',', '.')
        if (cleaned.endsWith(" ko", ignoreCase = true)) cleaned = cleaned.dropLast(4).trimEnd()
        return cleaned
    }

    private fun parseGeneralReminder(rawText: String, now: ZonedDateTime): ParsedIntent? {
        // Transcripts say "9:00 a.m."; read it as "9:00 am" so the stated hour wins
        // over a time-of-day word's default and the title strip finds it.
        val text = HinglishTimeParser.normalizeMeridiem(rawText)
        var body: String? = null
        val lower = text.lowercase()
        for (prefix in reminderPrefixes) {
            if (lower.startsWith(prefix)) {
                body = text.substring(prefix.length).trim()
                break
            }
        }
        if (body == null && lower.startsWith("mujhe ")) {
            for (marker in hindiReminderMarkers) {
                val idx = lower.indexOf(marker)
                if (idx > "mujhe ".length) {
                    // "mujhe <title> yaad dilana <time>"
                    val title = text.substring("mujhe ".length, idx).trim()
                    val timePart = text.substring(idx + marker.length).trim()
                    if (title.isNotBlank() && timePart.isNotBlank()) {
                        val parsed = HinglishTimeParser.parse(timePart, now) ?: return null
                        return ParsedIntent.Reminder(title, parsed.triggerAt.toInstant().toEpochMilli(), parsed.recurrence)
                    }
                }
            }
            return null
        }
        if (body.isNullOrBlank()) return null

        // TM-9: "remind me if <name> doesn't reply by <time>" — the reply
        // cancels the reminder; the time is the fallback deadline.
        conditionalReplyRegex.find(body)?.let { m ->
            val contact = m.groupValues[1].trim()
            val timePart = m.groupValues[2].trim()
            val parsed = HinglishTimeParser.parse(timePart, now) ?: return null
            return ParsedIntent.Reminder(
                title = "$contact hasn't replied",
                triggerAtEpochMillis = parsed.triggerAt.toInstant().toEpochMilli(),
                recurrence = parsed.recurrence,
                conditionWatchContact = contact,
            )
        }

        val parsed = HinglishTimeParser.parse(body, now) ?: return null
        val title = stripTimeFragment(body, parsed.matchedFragment)
        if (title.isBlank()) return null
        return ParsedIntent.Reminder(title, parsed.triggerAt.toInstant().toEpochMilli(), parsed.recurrence)
    }
}


/** AP-2: a recent app open an explicit correction can refer back to. */
private data class LastOpenedApp(val spoken: String, val atMillis: Long)

private const val APP_CORRECTION_WINDOW_MS = 120_000L

/**
 * AP-2: explicit app corrections. The restated app name is mandatory - a
 * bare "wrong app" teaches nothing, and generic cancels ("no", "stop")
 * never match, mirroring CT-7's narrowness.
 */
internal object AppCorrectionIntent {
    private val PATTERNS = listOf(
        Regex("""(?i)^(?:no,?\s+)?(?:that's\s+)?wrong\s+app,?\s+(?:i\s+meant\s+|open\s+)?(.+)$"""),
        Regex("""(?i)^(?:no,?\s+)?not\s+that\s+app,?\s+(?:i\s+meant\s+|open\s+)?(.+)$"""),
        Regex("""(?i)^galat\s+app,?\s+(?:i\s+meant\s+)?(.+)$"""),
    )

    fun parseRestated(text: String): String? =
        PATTERNS.firstNotNullOfOrNull { it.matchEntire(text.trim())?.groupValues?.get(1)?.trim() }
            ?.takeIf { it.isNotBlank() }
}

/**
 * CT-7: explicit wrong-contact correction phrases. Deliberately narrow:
 * generic cancels ("no", "cancel", "don't", "stop") must never match, so an
 * ordinary rejection can never teach Raijin a false relationship.
 */
internal object CorrectionIntent {
    private val PATTERNS = listOf(
        """(?i)^(?:no,?\s+)?(?:that's\s+)?wrong\s+(?:number|person|contact|one|name)\b.*""".toRegex(),
        """(?i)^(?:no,?\s+)?not\s+(?:him|her|them|that\s+one|this\s+one)\b.*""".toRegex(),
        """(?i)^that'?s\s+not\s+(?:him|her|them)\b.*""".toRegex(),
        """(?i)^(?:no,?\s+)?i\s+meant\b.*""".toRegex(),
        """(?i)^galat\s+(?:number|contact|aadmi)\b.*""".toRegex(),
    )

    fun looksLikeCorrection(text: String): Boolean = PATTERNS.any { it.matches(text.trim()) }
}
