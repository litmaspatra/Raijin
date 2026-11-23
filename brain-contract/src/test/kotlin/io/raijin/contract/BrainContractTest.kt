package io.raijin.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class BrainContractTest {
    @Test
    fun capabilitySnapshotFailsClosedByDefault() {
        val snapshot = CapabilitySnapshot()

        assertFalse(snapshot.microphoneAvailable)
        assertFalse(snapshot.contactsAvailable)
        assertFalse(snapshot.storageAvailable)
        assertFalse(snapshot.notificationsAvailable)
        assertFalse(snapshot.calendarAvailable)
        assertFalse(snapshot.localCodingAvailable)
        assertFalse(snapshot.cloudCodingEnabled)
        assertFalse(snapshot.networkAvailable)
    }

    @Test
    fun dispatchRejectsOutOfRangeConfidence() {
        assertFailsWith<IllegalArgumentException> {
            DispatchResult(
                traceId = "trace-1",
                actionType = ActionType.OPEN_APP,
                confidence = 1.01f,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            DispatchResult(
                traceId = "trace-2",
                actionType = ActionType.OPEN_APP,
                confidence = -0.01f,
            )
        }
    }

    @Test
    fun sendMessageRequiresTypedMessageArgs() {
        assertFailsWith<IllegalArgumentException> {
            DispatchResult(
                traceId = "trace-message",
                actionType = ActionType.SEND_MESSAGE,
                targets = listOf(ActionTarget.Contact("contact:maa", "Maa")),
                args = ActionArgs.None,
                confidence = 0.99f,
            )
        }

        val result = DispatchResult(
            traceId = "trace-message",
            actionType = ActionType.SEND_MESSAGE,
            targets = listOf(ActionTarget.Contact("contact:maa", "Maa")),
            args = ActionArgs.Message(
                text = "Aaj aa raha hoon",
                channel = MessageChannel.WHATSAPP,
            ),
            confidence = 0.99f,
        )

        val args = assertIs<ActionArgs.Message>(result.args)
        assertEquals("Aaj aa raha hoon", args.text)
        assertEquals(MessageChannel.WHATSAPP, args.channel)
    }

    @Test
    fun reminderCarriesStructuredTimeAndTitle() {
        val result = DispatchResult(
            traceId = "trace-reminder",
            actionType = ActionType.CREATE_REMINDER,
            args = ActionArgs.Reminder(
                title = "Buy milk",
                triggerAtEpochMillis = 1_800_000_000_000,
                timeZoneId = "Asia/Kolkata",
            ),
            confidence = 0.95f,
        )

        val args = assertIs<ActionArgs.Reminder>(result.args)
        assertEquals("Buy milk", args.title)
        assertEquals("Asia/Kolkata", args.timeZoneId)
    }

    @Test
    fun dispatchCarriesTypedActionAndTypedTargets() {
        val result = DispatchResult(
            traceId = "trace-share",
            actionType = ActionType.SHARE_FILE,
            targets = listOf(
                ActionTarget.File(
                    ref = "content://documents/order.pdf",
                    displayName = "order.pdf",
                ),
                ActionTarget.Contact(
                    ref = "contact:rahul",
                    displayName = "Rahul",
                ),
            ),
            args = ActionArgs.Share(channel = ShareChannel.WHATSAPP),
            confidence = 0.98f,
        )

        assertEquals(ActionType.SHARE_FILE, result.actionType)
        assertIs<ActionTarget.File>(result.targets[0])
        assertIs<ActionTarget.Contact>(result.targets[1])
        assertIs<ActionArgs.Share>(result.args)
    }

    @Test
    fun needsInputRejectsDuplicateChoiceIds() {
        assertFailsWith<IllegalArgumentException> {
            IslandVisualState.NeedsInput(
                prompt = "Which Rahul?",
                traceId = "trace-choice",
                kind = InputKind.CLARIFICATION,
                options = listOf(
                    InputOption("rahul", "Rahul Sharma"),
                    InputOption("rahul", "Rahul Jain"),
                ),
            )
        }
    }

    @Test
    fun cloudConsentRequiresExplicitDisclosure() {
        assertFailsWith<IllegalArgumentException> {
            IslandVisualState.NeedsInput(
                prompt = "Send coding task to cloud?",
                traceId = "trace-cloud",
                kind = InputKind.CLOUD_CONSENT,
            )
        }

        val valid = IslandVisualState.NeedsInput(
            prompt = "Send coding task to cloud?",
            traceId = "trace-cloud",
            kind = InputKind.CLOUD_CONSENT,
            options = listOf(
                InputOption("cancel", "Cancel"),
                InputOption("send", "Send"),
            ),
            cloudDisclosure = CloudDisclosure(
                setOf(
                    CloudDataCategory.CODING_REQUEST,
                    CloudDataCategory.SOURCE_SNIPPET,
                ),
            ),
        )

        assertEquals(InputKind.CLOUD_CONSENT, valid.kind)
    }

    @Test
    fun cloudDisclosureCannotBeAttachedToOrdinaryConfirmation() {
        assertFailsWith<IllegalArgumentException> {
            IslandVisualState.NeedsInput(
                prompt = "Call Rahul?",
                traceId = "trace-call",
                kind = InputKind.CONFIRMATION,
                cloudDisclosure = CloudDisclosure(
                    setOf(CloudDataCategory.CODING_REQUEST),
                ),
            )
        }
    }

    @Test
    fun thinkingCannotMislabelFastLocalWork() {
        assertFailsWith<IllegalArgumentException> {
            IslandVisualState.Thinking(
                taskLabel = "Opening WhatsApp",
                mode = ProcessingMode.LOCAL_FAST,
            )
        }
    }

    @Test
    fun resolvingCarriesOptionalStepTrace() {
        val state = IslandVisualState.Resolving(
            label = "Resolving Rahul",
            stepTrace = listOf("CONTACT_LOOKUP", "ALIAS_MATCH"),
        )

        assertEquals(2, state.stepTrace.size)

        assertFailsWith<IllegalArgumentException> {
            IslandVisualState.Resolving(stepTrace = listOf(""))
        }
    }

    @Test
    fun activeContextValidatesBatteryPercent() {
        val context = ActiveContext(
            foregroundApp = "WhatsApp",
            device = DeviceContext(
                wifiConnected = true,
                bluetoothConnected = false,
                batteryPercent = 72,
            ),
        )

        assertEquals(72, context.device.batteryPercent)

        assertFailsWith<IllegalArgumentException> {
            DeviceContext(batteryPercent = 101)
        }
    }

    @Test
    fun memoryQueriesRejectUnboundedOrBlankRequests() {
        assertFailsWith<IllegalArgumentException> {
            MemoryQuery(text = "")
        }

        assertFailsWith<IllegalArgumentException> {
            MemoryQuery(text = "Rahul", limit = 101)
        }
    }

    @Test
    fun callRequiresTypedCallArgsAndCarriesSpeakerFlag() {
        assertFailsWith<IllegalArgumentException> {
            DispatchResult(
                traceId = "trace-call-invalid",
                actionType = ActionType.CALL_CONTACT,
                confidence = 0.9f,
            )
        }

        val result = DispatchResult(
            traceId = "trace-call",
            actionType = ActionType.CALL_CONTACT,
            args = ActionArgs.Call(speaker = true),
            confidence = 0.9f,
        )

        assertEquals(true, assertIs<ActionArgs.Call>(result.args).speaker)
    }

    @Test
    fun reminderCarriesOptionalConditionAndLocation() {
        val args = ActionArgs.Reminder(
            title = "Leave for court",
            triggerAtEpochMillis = 1_800_000_000_000,
            timeZoneId = "Asia/Kolkata",
            condition = "if rain is expected",
            location = "Tonk court",
        )

        assertEquals("if rain is expected", args.condition)
        assertEquals("Tonk court", args.location)
    }

    @Test
    fun briefRejectsBlankOrEmptyLines() {
        assertFailsWith<IllegalArgumentException> {
            IslandVisualState.Brief(emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            IslandVisualState.Brief(listOf(""))
        }

        val brief = IslandVisualState.Brief(listOf("Good morning", "Two reminders today"))
        assertEquals(2, brief.lines.size)
    }

    @Test
    fun actionResultReasonsMustBeNonBlank() {
        assertFailsWith<IllegalArgumentException> { ActionResult.DENIED(" ") }
        assertFailsWith<IllegalArgumentException> { ActionResult.FAILED("") }
    }
    @Test
    fun codingActionsRequireTypedCodingArgs() {
        assertFailsWith<IllegalArgumentException> {
            DispatchResult(
                traceId = "trace-code",
                actionType = ActionType.LOCAL_CODING,
                confidence = 0.9f,
            )
        }

        val local = DispatchResult(
            traceId = "trace-code-local",
            actionType = ActionType.LOCAL_CODING,
            args = ActionArgs.Coding("Fill the missing pathlib slot"),
            confidence = 0.9f,
        )
        val cloud = DispatchResult(
            traceId = "trace-code-cloud",
            actionType = ActionType.CLOUD_CODING,
            args = ActionArgs.Coding("Debug this unfamiliar Gradle failure"),
            confidence = 0.8f,
        )

        assertEquals("Fill the missing pathlib slot", assertIs<ActionArgs.Coding>(local.args).request)
        assertEquals("Debug this unfamiliar Gradle failure", assertIs<ActionArgs.Coding>(cloud.args).request)

        assertFailsWith<IllegalArgumentException> {
            ActionArgs.Coding("   ")
        }
    }

    @Test
    fun undoOfferDefaultsAndValidation() {
        // No undo: default window is inert and no offer is carried.
        val plain = ActionResult.EXECUTED()
        assertEquals(null, plain.undoAction)

        val offered = ActionResult.EXECUTED(undoAction = UndoAction.DeleteReminder(7L))
        assertEquals(ActionResult.DEFAULT_UNDO_WINDOW_MS, offered.undoWindowMs)

        // An offer with a non-positive window is rejected.
        assertFailsWith<IllegalArgumentException> {
            ActionResult.EXECUTED(undoAction = UndoAction.DeleteReminder(7L), undoWindowMs = 0L)
        }
    }

    @Test
    fun undoableVisualStateRequiresContentAndWindow() {
        val state = IslandVisualState.Undoable(
            feedbackLine = "Reminder set for cricket.",
            undoToken = "token-1",
            windowMs = 5_000L,
        )
        assertEquals("Undo", state.undoLabel)

        assertFailsWith<IllegalArgumentException> {
            IslandVisualState.Undoable(feedbackLine = " ", undoToken = "t", windowMs = 5_000L)
        }
        assertFailsWith<IllegalArgumentException> {
            IslandVisualState.Undoable(feedbackLine = "ok", undoToken = "", windowMs = 5_000L)
        }
        assertFailsWith<IllegalArgumentException> {
            IslandVisualState.Undoable(feedbackLine = "ok", undoToken = "t", windowMs = 0L)
        }
    }

}
