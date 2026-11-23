package io.raijin.brain

import io.raijin.contract.ActionArgs
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import io.raijin.contract.ActionResult
import io.raijin.contract.ActionTarget
import io.raijin.contract.ActionType
import io.raijin.contract.ActuatorPort
import io.raijin.contract.CapabilitySnapshot
import io.raijin.contract.CloudReasoningPort
import io.raijin.contract.EntityCandidate
import io.raijin.contract.InputKind
import io.raijin.contract.IslandVisualState
import io.raijin.contract.MemoryHit
import io.raijin.contract.MemoryQuery
import io.raijin.contract.MemoryWrite
import io.raijin.contract.MessageChannel
import io.raijin.contract.MusicCommand
import io.raijin.contract.MusicFeedback
import io.raijin.contract.RaijinMemoryPort
import io.raijin.contract.UndoAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaijinBrainTest {
    @Test
    fun openAppRunsCompleteReflexLoopWithoutConfirmation() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)
        val states = mutableListOf<IslandVisualState>()
        val collector = backgroundScope.launch {
            brain.islandState.take(5).collect { states += it }
        }
        runCurrent()

        val result = brain.processUtterance("open WhatsApp", "trace-open")
        collector.join()

        assertEquals(ActionType.OPEN_APP, result.actionType)
        assertEquals("trace-open", result.traceId)
        assertEquals(1, actuator.actions.size)
        assertIs<ActionTarget.App>(actuator.actions.single().targets.single())
        assertEquals(
            listOf(
                IslandVisualState.Idle::class,
                IslandVisualState.Listening::class,
                IslandVisualState.Resolving::class,
                IslandVisualState.Acting::class,
                IslandVisualState.Done::class,
            ),
            states.map { it::class },
        )
    }

    @Test
    fun callCarriesSpeakerFlagAndActsOnlyAfterExactTraceConfirmation() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory(
            "Maa" to listOf(EntityCandidate("contact:maa", "Maa", 0.99f)),
        )
        val brain = RaijinBrain(memory, actuator)

        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        val result = brain.processUtterance("call Maa on speaker", "trace-call")

        val needsInput = assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        assertEquals("trace-call", needsInput.traceId)
        assertTrue(actuator.actions.isEmpty())
        assertTrue(assertIs<ActionArgs.Call>(result.args).speaker)

        brain.resolveActionConfirmation("wrong-trace", true)
        assertTrue(actuator.actions.isEmpty())

        brain.resolveActionConfirmation("trace-call", true)

        assertEquals(1, actuator.actions.size)
        assertEquals(ActionType.CALL_CONTACT, actuator.actions.single().actionType)
        assertIs<IslandVisualState.Done>(brain.islandState.value)
    }

    @Test
    fun rejectingMessageConfirmationNeverCallsActuator() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory(
                "Rahul" to listOf(EntityCandidate("contact:rahul", "Rahul", 0.95f)),
            ),
            actuator,
        )

        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        val result = brain.processUtterance(
            "message Rahul: I am on the way",
            "trace-message",
        )

        assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        brain.resolveActionConfirmation("trace-message", false)

        assertTrue(actuator.actions.isEmpty())
        assertEquals(ActionType.SEND_MESSAGE, result.actionType)
        assertIs<IslandVisualState.Done>(brain.islandState.value)
    }

    @Test
    fun ambiguousContactUsesChoiceThenConfirmation() = runTest {
        val actuator = RecordingActuator()
        val candidates = listOf(
            EntityCandidate("contact:rahul-work", "Rahul Work", 0.88f),
            EntityCandidate("contact:rahul-home", "Rahul Home", 0.82f),
        )
        val brain = RaijinBrain(FakeMemory("Rahul" to candidates), actuator)
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))

        brain.processUtterance("call Rahul", "trace-ambiguous")

        val choiceInput = assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        assertEquals(InputKind.CLARIFICATION, choiceInput.kind)
        assertEquals(2, choiceInput.options.size)

        brain.resolveChoice("trace-ambiguous", "contact:rahul-home")

        val confirmInput = assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        assertEquals(InputKind.CONFIRMATION, confirmInput.kind)
        assertTrue(actuator.actions.isEmpty())

        brain.resolveActionConfirmation("trace-ambiguous", true)

        assertEquals(1, actuator.actions.size)
        val target = assertIs<ActionTarget.Contact>(actuator.actions.single().targets.single())
        assertEquals("contact:rahul-home", target.ref)
    }

    @Test
    fun freeTextClarificationCanResolveAmbiguity() = runTest {
        val actuator = RecordingActuator()
        val initialCandidates = listOf(
            EntityCandidate("contact:rahul-work", "Rahul Work", 0.88f),
            EntityCandidate("contact:rahul-home", "Rahul Home", 0.82f),
        )
        val memory = FakeMemory(
            "Rahul" to initialCandidates,
            "office" to listOf(EntityCandidate("contact:rahul-work", "Rahul Work", 0.94f)),
        )
        val brain = RaijinBrain(memory, actuator)
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))

        brain.processUtterance("call Rahul", "trace-free-text")

        val choiceInput = assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        assertEquals(InputKind.CLARIFICATION, choiceInput.kind)

        brain.resolveClarification("trace-free-text", "office")

        val confirmInput = assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        assertEquals(InputKind.CONFIRMATION, confirmInput.kind)
        assertEquals("Call Rahul Work?", confirmInput.prompt)

        brain.resolveActionConfirmation("trace-free-text", true)

        assertEquals(1, actuator.actions.size)
        val target = assertIs<ActionTarget.Contact>(actuator.actions.single().targets.single())
        assertEquals("contact:rahul-work", target.ref)
    }

    @Test
    fun capabilitySnapshotIsSuppliedByAppAndPublishedBack() = runTest {
        val brain = RaijinBrain(FakeMemory(), RecordingActuator())
        assertFalse(brain.capabilityState.value.phoneCallAvailable)

        brain.submitCapabilitySnapshot(
            CapabilitySnapshot(
                contactsAvailable = true,
                phoneCallAvailable = true,
                microphoneAvailable = true,
            ),
        )

        assertTrue(brain.capabilityState.value.phoneCallAvailable)
        assertTrue(brain.capabilityState.value.contactsAvailable)
    }

    @Test
    fun unsupportedRequestShowsReasoningTierButDoesNotInvokeAModelOrActuator() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val result = brain.processUtterance("summarize my taxes", "trace-unknown")

        assertEquals(ActionType.NONE, result.actionType)
        assertTrue(actuator.actions.isEmpty())
        val failed = assertIs<IslandVisualState.Failed>(brain.islandState.value)
        assertTrue(failed.recoverable)
    }

    @Test
    fun deniedActuatorResultBecomesRecoverableFailure() = runTest {
        val actuator = RecordingActuator(result = ActionResult.DENIED("Permission missing on device"))
        val brain = RaijinBrain(FakeMemory(), actuator)

        brain.processUtterance("open WhatsApp", "trace-denied")

        val failed = assertIs<IslandVisualState.Failed>(brain.islandState.value)
        assertEquals("Permission missing on device", failed.errorReason)
        assertTrue(failed.recoverable)
    }

    @Test
    fun cancellationCancelsInFlightActionAndEmitsDone() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val actuator = RecordingActuator(beforeResult = {
            entered.complete(Unit)
            release.await()
        })
        val brain = RaijinBrain(FakeMemory(), actuator)

        val task = launch {
            brain.processUtterance("open WhatsApp", "trace-cancel")
        }

        entered.await()
        assertEquals(
            IslandVisualState.Acting("Opening app"),
            brain.islandState.value,
        )

        brain.cancelCurrentAction("trace-cancel")
        release.complete(Unit)
        task.join()

        assertEquals(
            IslandVisualState.Done("Cancelled."),
            brain.islandState.value,
        )
    }

    @Test
    fun cancellationWithUnknownTraceDoesNotDisturbPendingAction() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory("Maa" to listOf(EntityCandidate("contact:maa", "Maa", 1.0f))),
            actuator,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))

        brain.processUtterance("call Maa", "trace-real")
        assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)

        brain.cancelCurrentAction("trace-unknown")
        assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)

        brain.resolveActionConfirmation("trace-real", true)
        assertEquals(1, actuator.actions.size)
        assertIs<IslandVisualState.Done>(brain.islandState.value)
    }

    @Test
    fun reachableStatesExcludeUnimplementedThinking() = runTest {
        val brain = RaijinBrain(
            FakeMemory(
                "Maa" to listOf(EntityCandidate("contact:maa", "Maa", 1.0f)),
                "Rahul" to listOf(
                    EntityCandidate("contact:rahul-1", "Rahul One", 0.9f),
                    EntityCandidate("contact:rahul-2", "Rahul Two", 0.8f),
                ),
            ),
            RecordingActuator(),
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))

        val observedStates = mutableSetOf<kotlin.reflect.KClass<out IslandVisualState>>()
        val collector = backgroundScope.launch {
            brain.islandState.collect { observedStates += it::class }
        }
        runCurrent()

        brain.processUtterance("open WhatsApp", "trace-open")
        brain.processUtterance("call Rahul", "trace-choice")
        brain.resolveChoice("trace-choice", "contact:rahul-1")
        brain.resolveActionConfirmation("trace-choice", false)
        brain.processUtterance("call Maa", "trace-call")
        brain.resolveActionConfirmation("trace-call", true)
        brain.processUtterance("not a real command", "trace-fail")
        collector.cancelAndJoin()

        assertTrue(observedStates.contains(IslandVisualState.Idle::class))
        assertTrue(observedStates.contains(IslandVisualState.Listening::class))
        assertTrue(observedStates.contains(IslandVisualState.Resolving::class))
        assertTrue(observedStates.contains(IslandVisualState.NeedsInput::class))
        assertTrue(observedStates.contains(IslandVisualState.Acting::class))
        assertTrue(observedStates.contains(IslandVisualState.Done::class))
        assertTrue(observedStates.contains(IslandVisualState.Failed::class))
        assertFalse(observedStates.contains(IslandVisualState.Thinking::class))
        assertFalse(observedStates.contains(IslandVisualState.Brief::class))
    }

    @Test
    fun duplicateConfirmationExecutesExactlyOnce() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory("Maa" to listOf(EntityCandidate("contact:maa", "Maa", 1f))),
            actuator,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        brain.processUtterance("call Maa", "trace-dup")
        brain.resolveActionConfirmation("trace-dup", true)
        brain.resolveActionConfirmation("trace-dup", true)
        assertEquals(1, actuator.actions.size)
    }

    @Test
    fun staleAndUnknownConfirmationHaveNoEffect() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory("Maa" to listOf(EntityCandidate("contact:maa", "Maa", 1f))),
            actuator,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        brain.processUtterance("call Maa", "trace-one")
        brain.resolveActionConfirmation("trace-unknown", true)
        assertTrue(actuator.actions.isEmpty())
        brain.resolveActionConfirmation("trace-one", false)
        brain.resolveActionConfirmation("trace-one", true)
        assertTrue(actuator.actions.isEmpty())
    }

    @Test
    fun unknownChoiceEmitsFailureWithoutActuation() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory(
                "Rahul" to listOf(
                    EntityCandidate("r1", "Rahul One", 0.9f),
                    EntityCandidate("r2", "Rahul Two", 0.8f),
                ),
            ),
            actuator,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        brain.processUtterance("call Rahul", "trace-choice")
        brain.resolveChoice("trace-choice", "missing-id")
        assertTrue(actuator.actions.isEmpty())
        assertIs<IslandVisualState.Failed>(brain.islandState.value)
    }

    @Test
    fun throwingActuatorProducesUserSafeFailure() = runTest {
        val brain = RaijinBrain(FakeMemory(), object : ActuatorPort {
            override suspend fun execute(action: io.raijin.contract.DispatchResult): ActionResult {
                error("raw database crash with sensitive detail")
            }
        })
        brain.processUtterance("open WhatsApp", "trace-throw")
        val failed = assertIs<IslandVisualState.Failed>(brain.islandState.value)
        assertFalse(failed.errorReason.contains("database crash"))
        assertTrue(failed.recoverable)
    }

    @Test
    fun callMissingBothCapabilitiesRequestsPermissionAndRechecks() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory("Maa" to listOf(EntityCandidate("maa", "Maa", 1f))),
            actuator,
        )
        val result = brain.processUtterance("call Maa", "trace-perm")
        assertEquals(ActionType.CALL_CONTACT, result.actionType)
        val needsInput = assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        assertEquals(InputKind.PERMISSION, needsInput.kind)
        assertTrue(needsInput.prompt.contains("contacts permission"))
        assertTrue(needsInput.prompt.contains("phone call permission"))
        assertTrue(actuator.actions.isEmpty())
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        brain.resolveActionConfirmation("trace-perm", true)
        val confirm = assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        assertEquals(InputKind.CONFIRMATION, confirm.kind)
        brain.resolveActionConfirmation("trace-perm", true)
        assertEquals(1, actuator.actions.size)
    }

    @Test
    fun callMissingPhoneOrContactsIndividuallyIsGated() = runTest {
        val brain = RaijinBrain(
            FakeMemory("Maa" to listOf(EntityCandidate("maa", "Maa", 1f))),
            RecordingActuator(),
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = false))
        brain.processUtterance("call Maa", "trace-phone-missing")
        var input = assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        assertTrue(input.prompt.contains("phone call permission"))
        assertFalse(input.prompt.contains("contacts permission"))
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = false, phoneCallAvailable = true))
        brain.processUtterance("call Maa", "trace-contacts-missing")
        input = assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        assertTrue(input.prompt.contains("contacts permission"))
        assertFalse(input.prompt.contains("phone call permission"))
    }

    @Test
    fun messageRequiresContactsButNotPhoneCapability() = runTest {
        val brain = RaijinBrain(
            FakeMemory("Rahul" to listOf(EntityCandidate("rahul", "Rahul", 1f))),
            RecordingActuator(),
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = false))
        brain.processUtterance("message Rahul: test", "trace-msg-perm")
        val input = assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        assertEquals(InputKind.CONFIRMATION, input.kind)
        assertEquals("Send this message to Rahul?", input.prompt)
    }

    @Test
    fun rejectingPermissionOrCancellingPendingEndsDone() = runTest {
        val brain = RaijinBrain(FakeMemory(), RecordingActuator())
        brain.processUtterance("call Maa", "trace-perm-cancel")
        assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        brain.resolveActionConfirmation("trace-perm-cancel", false)
        assertEquals(IslandVisualState.Done("Cancelled."), brain.islandState.value)
    }

    @Test
    fun supersedingUtteranceDropsPendingRequest() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory("Maa" to listOf(EntityCandidate("maa", "Maa", 1f))),
            actuator,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        brain.processUtterance("call Maa", "trace-first")
        assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        brain.processUtterance("open WhatsApp", "trace-second")
        brain.resolveActionConfirmation("trace-first", true)
        assertEquals(1, actuator.actions.size)
        assertEquals(ActionType.OPEN_APP, actuator.actions.single().actionType)
    }

    @Test
    fun newUtteranceRefusedDuringActuationAndOriginalReports() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val actuator = RecordingActuator(beforeResult = {
            entered.complete(Unit)
            release.await()
        })
        val brain = RaijinBrain(FakeMemory(), actuator)
        val original = launch { brain.processUtterance("open WhatsApp", "trace-orig") }
        entered.await()
        val refused = brain.processUtterance("open Slack", "trace-refused")
        assertEquals(ActionType.NONE, refused.actionType)
        val refusedActing = assertIs<IslandVisualState.Acting>(brain.islandState.value)
        assertTrue(refusedActing.actionSummary.contains("finishing your previous action"))
        release.complete(Unit)
        original.join()
        assertEquals(IslandVisualState.Done("Opening WhatsApp."), brain.islandState.value)
        assertEquals(1, actuator.actions.size)
    }

    @Test
    fun supersedingPendingDoesNotCancelSiblingCallerWork() = runTest {
        val brain = RaijinBrain(
            FakeMemory("Maa" to listOf(EntityCandidate("maa", "Maa", 1f))),
            RecordingActuator(),
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        val firstTask = async(start = CoroutineStart.UNDISPATCHED) {
            brain.processUtterance("call Maa", "trace-first")
        }
        val second = brain.processUtterance("open WhatsApp", "trace-second")
        assertEquals(ActionType.OPEN_APP, second.actionType)
        val firstResult = firstTask.await()
        assertEquals(ActionType.NONE, firstResult.actionType)
        assertEquals("Request superseded.", firstResult.vocalResponse)
    }

    @Test
    fun throwingMemoryProducesRecoverableFailure() = runTest {
        val brain = RaijinBrain(object : RaijinMemoryPort {
            override suspend fun search(query: MemoryQuery): List<MemoryHit> = emptyList()
            override suspend fun resolveEntity(nameOrAlias: String, limit: Int): List<EntityCandidate> {
                error("database failure with customer credentials")
            }
            override suspend fun remember(write: MemoryWrite): String = "unused"
            override suspend fun forget(memoryId: String): Boolean = false
        }, RecordingActuator())
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        val result = brain.processUtterance("call Maa", "trace-mem-throw")
        assertEquals(ActionType.CALL_CONTACT, result.actionType)
        val failed = assertIs<IslandVisualState.Failed>(brain.islandState.value)
        assertFalse(failed.errorReason.contains("customer credentials"))
        assertTrue(failed.recoverable)
    }

    @Test
    fun throwingMemoryInClarificationProducesFailure() = runTest {
        var fail = false
        val brain = RaijinBrain(object : RaijinMemoryPort {
            override suspend fun search(query: MemoryQuery): List<MemoryHit> = emptyList()
            override suspend fun resolveEntity(nameOrAlias: String, limit: Int): List<EntityCandidate> {
                if (fail) error("secondary lookup failure with internal table name")
                return listOf(EntityCandidate("r1", "Rahul One", 0.9f), EntityCandidate("r2", "Rahul Two", 0.8f))
            }
            override suspend fun remember(write: MemoryWrite): String = "unused"
            override suspend fun forget(memoryId: String): Boolean = false
        }, RecordingActuator())
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        brain.processUtterance("call Rahul", "trace-clar-throw")
        fail = true
        brain.resolveClarification("trace-clar-throw", "office")
        val failed = assertIs<IslandVisualState.Failed>(brain.islandState.value)
        assertFalse(failed.errorReason.contains("internal table name"))
        assertTrue(failed.recoverable)
    }

    @Test
    fun hungActuatorTimesOutAndNextUtteranceIsAccepted() = runTest {
        val entered = CompletableDeferred<Unit>()
        val hanging = CompletableDeferred<Unit>()
        val actuator = RecordingActuator(beforeResult = {
            entered.complete(Unit)
            hanging.await()
        })
        val brain = RaijinBrain(FakeMemory(), actuator, actuatorTimeoutMillis = 100)
        val caller = launch { brain.processUtterance("open WhatsApp", "trace-timeout") }
        entered.await()
        advanceTimeBy(101)
        runCurrent()
        caller.join()
        assertEquals(
            IslandVisualState.Failed("That took too long. Please try again.", recoverable = true),
            brain.islandState.value,
        )
        val next = brain.processUtterance("open WhatsApp", "trace-after-timeout")
        assertEquals(ActionType.OPEN_APP, next.actionType)
    }

    @Test
    fun confirmedActionReservationRejectsNewRequestBeforeActuatorStarts() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val actuator = RecordingActuator(beforeResult = {
            started.complete(Unit)
            release.await()
        })
        val brain = RaijinBrain(
            FakeMemory("Maa" to listOf(EntityCandidate("maa", "Maa", 1f))),
            actuator,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        brain.processUtterance("call Maa", "call-trace")
        val caller = launch { brain.resolveActionConfirmation("call-trace", true) }
        started.await()
        val refused = brain.processUtterance("open WhatsApp", "late-trace")
        assertEquals(ActionType.NONE, refused.actionType)
        assertTrue(assertIs<IslandVisualState.Acting>(brain.islandState.value).actionSummary.contains("finishing your previous action"))
        release.complete(Unit)
        caller.join()
    }

    @Test
    fun confirmedCallerCancellationReleasesReservedActingTrace() = runTest {
        val brain = RaijinBrain(
            FakeMemory("Maa" to listOf(EntityCandidate("maa", "Maa", 1f))),
            RecordingActuator(),
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        brain.processUtterance("call Maa", "call-trace")
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            brain.resolveActionConfirmation("call-trace", true)
        }
        caller.cancelAndJoin()
        val next = brain.processUtterance("open WhatsApp", "next-trace")
        assertEquals(ActionType.OPEN_APP, next.actionType)
    }

    @Test
    fun cancelledCallerBeforeActuatorExecutionReleasesReservation() = runTest {
        val brain = RaijinBrain(
            FakeMemory("Maa" to listOf(EntityCandidate("maa", "Maa", 1f))),
            RecordingActuator(),
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        brain.processUtterance("call Maa", "call-trace")
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            brain.resolveActionConfirmation("call-trace", true)
        }
        caller.cancelAndJoin()
        val next = brain.processUtterance("open WhatsApp", "next-trace")
        assertEquals(ActionType.OPEN_APP, next.actionType)
    }

    @Test
    fun mediumRiskActuatorTimeoutDescribesUnknownOutcome() = runTest {
        val entered = CompletableDeferred<Unit>()
        val hanging = CompletableDeferred<Unit>()
        val actuator = RecordingActuator(beforeResult = {
            entered.complete(Unit)
            hanging.await()
        })
        val brain = RaijinBrain(
            FakeMemory("Maa" to listOf(EntityCandidate("maa", "Maa", 1f))),
            actuator,
            actuatorTimeoutMillis = 30_000,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        brain.processUtterance("call Maa", "call-timeout")
        val caller = launch { brain.resolveActionConfirmation("call-timeout", true) }
        entered.await()
        advanceTimeBy(30_001)
        runCurrent()
        caller.join()
        assertEquals(
            "I couldn't confirm that finished. Check before trying again.",
            assertIs<IslandVisualState.Failed>(brain.islandState.value).errorReason,
        )
    }

    @Test
    fun musicCommandsDispatchImmediatelyWithoutConfirmation() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val playRes = brain.processUtterance("play Bohemian Rhapsody", "trace-play")
        assertEquals(ActionType.MUSIC_CONTROL, playRes.actionType)
        val playArgs = assertIs<ActionArgs.Music>(playRes.args)
        assertEquals(MusicCommand.PLAY_QUERY, playArgs.command)
        assertEquals("Bohemian Rhapsody", playArgs.query)
        assertEquals(1, actuator.actions.size)

        val pauseRes = brain.processUtterance("pause music", "trace-pause")
        assertEquals(ActionType.MUSIC_CONTROL, pauseRes.actionType)
        val pauseArgs = assertIs<ActionArgs.Music>(pauseRes.args)
        assertEquals(MusicCommand.PAUSE, pauseArgs.command)
        assertEquals(2, actuator.actions.size)

        val skipRes = brain.processUtterance("skip song", "trace-skip")
        assertEquals(ActionType.MUSIC_CONTROL, skipRes.actionType)
        val skipArgs = assertIs<ActionArgs.Music>(skipRes.args)
        assertEquals(MusicCommand.NEXT, skipArgs.command)
        assertEquals(3, actuator.actions.size)
    }

    @Test
    fun executedMessageOverridesVocalResponse() = runTest {
        // MU-2: the actuator can report the real outcome (e.g. the actual
        // track name) and it wins over the dispatch's pre-set vocal line.
        val actuator = RecordingActuator(result = ActionResult.EXECUTED("Now playing Kesariya by Arijit Singh."))
        val brain = RaijinBrain(FakeMemory(), actuator)

        brain.processUtterance("ye gaana kya hai", "trace-np")
        val done = assertIs<IslandVisualState.Done>(brain.islandState.value)
        assertEquals("Now playing Kesariya by Arijit Singh.", done.feedbackLine)
    }

    @Test
    fun mu4HindiAndVolumeCommandsRouteCorrectly() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        // Hindi/Hinglish transport forms
        assertEquals(
            MusicCommand.PAUSE,
            assertIs<ActionArgs.Music>(brain.processUtterance("gaana band karo", "t1").args).command,
        )
        assertEquals(
            MusicCommand.PAUSE,
            assertIs<ActionArgs.Music>(brain.processUtterance("ruko", "t2").args).command,
        )
        assertEquals(
            MusicCommand.NEXT,
            assertIs<ActionArgs.Music>(brain.processUtterance("agla gaana", "t3").args).command,
        )
        assertEquals(
            MusicCommand.PREVIOUS,
            assertIs<ActionArgs.Music>(brain.processUtterance("pichhla gaana", "t4").args).command,
        )

        // Volume commands (MU-4 louder/quieter)
        assertEquals(
            MusicCommand.VOLUME_UP,
            assertIs<ActionArgs.Music>(brain.processUtterance("volume badhao", "t5").args).command,
        )
        assertEquals(
            MusicCommand.VOLUME_DOWN,
            assertIs<ActionArgs.Music>(brain.processUtterance("awaaz kam karo", "t6").args).command,
        )
        assertEquals(
            MusicCommand.VOLUME_UP,
            assertIs<ActionArgs.Music>(brain.processUtterance("louder", "t7").args).command,
        )

        // Repeat + now-playing (MU-4)
        assertEquals(
            MusicCommand.REPEAT,
            assertIs<ActionArgs.Music>(brain.processUtterance("gaana repeat karo", "t8").args).command,
        )
        assertEquals(
            MusicCommand.NOW_PLAYING,
            assertIs<ActionArgs.Music>(brain.processUtterance("ye gaana kya hai", "t9").args).command,
        )

        // Hindi play query: "<artist> chalao"
        val queryRes = brain.processUtterance("arijit singh chalao", "t10")
        assertEquals(ActionType.MUSIC_CONTROL, queryRes.actionType)
        val queryArgs = assertIs<ActionArgs.Music>(queryRes.args)
        assertEquals(MusicCommand.PLAY_QUERY, queryArgs.command)
        assertEquals("arijit singh", queryArgs.query)

        // Bare "gaana chalao" = generic play
        assertEquals(
            MusicCommand.PLAY,
            assertIs<ActionArgs.Music>(brain.processUtterance("gaana chalao", "t11").args).command,
        )

        // All routed as MUSIC_CONTROL, dispatched without confirmation
        assertEquals(11, actuator.actions.size)
        assertTrue(actuator.actions.all { it.actionType == ActionType.MUSIC_CONTROL })
    }

    @Test
    fun mu4ParsingDoesNotShadowOtherRoutes() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        // "play" inside longer sentences that are not music commands stays untouched
        val reminderRes = brain.processUtterance("remind me to play cricket at 6 pm", "g1")
        assertEquals(ActionType.CREATE_REMINDER, reminderRes.actionType)

        // 6+ word "<...> chalao" is not treated as a play query (over-match guard)
        val chatRes = brain.processUtterance("aaj raat dinner ke liye kuchh accha gaana chalao", "g2")
        assertEquals(ActionType.NONE, chatRes.actionType)
    }

    @Test
    fun reminderCommandsDispatchImmediatelyWithoutConfirmation() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("remind me to take medicine in 10 minutes", "trace-reminder")
        assertEquals(ActionType.CREATE_REMINDER, res.actionType)
        val reminderArgs = assertIs<ActionArgs.Reminder>(res.args)
        assertEquals("take medicine", reminderArgs.title)
        assertTrue(reminderArgs.triggerAtEpochMillis > System.currentTimeMillis())
        assertEquals(1, actuator.actions.size)
    }

    @Test
    fun storageCleaningRequiresConfirmationBeforeExecution() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("clean cache", "trace-clean")
        assertEquals(ActionType.DELETE_FILES, res.actionType)
        // Guard: no deletion without explicit review & approval
        assertEquals(0, actuator.actions.size)

        // Confirm
        brain.resolveActionConfirmation("trace-clean", true)
        assertEquals(1, actuator.actions.size)
    }

    @Test
    fun smsIntentUsesSmsChannel() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory("Dad" to listOf(EntityCandidate("contact:dad", "Dad", 0.99f))),
            actuator,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))

        val res = brain.processUtterance("sms Dad: arriving now", "trace-sms")
        assertEquals(ActionType.SEND_MESSAGE, res.actionType)
        val msgArgs = assertIs<ActionArgs.Message>(res.args)
        assertEquals(MessageChannel.SMS, msgArgs.channel)
        assertEquals("arriving now", msgArgs.text)
    }

    @Test
    fun openEndedUtteranceEscalatesToCloudReasoningWhenConfigured() = runTest {
        val fakeCloud = object : CloudReasoningPort {
            override val isConfigured: Boolean = true
            override suspend fun complete(prompt: String, systemPrompt: String?, temperature: Float): String {
                return "Quantum entanglement is a physical phenomenon..."
            }
        }
        val brain = RaijinBrain(FakeMemory(), RecordingActuator(), cloudReasoning = fakeCloud)

        val res = brain.processUtterance("What is quantum entanglement?", "trace-cloud")
        assertEquals(ActionType.NONE, res.actionType)
        assertEquals(1.0f, res.confidence)
        assertEquals("Quantum entanglement is a physical phenomenon...", res.vocalResponse)
        val done = assertIs<IslandVisualState.Done>(brain.islandState.value)
        assertEquals("Quantum entanglement is a physical phenomenon...", done.feedbackLine)
    }

    @Test
    fun cloudReasoningFailureProducesRecoverableFailedState() = runTest {
        val failingCloud = object : CloudReasoningPort {
            override val isConfigured: Boolean = true
            override suspend fun complete(prompt: String, systemPrompt: String?, temperature: Float): String {
                throw java.io.IOException("Rate limit exceeded")
            }
        }
        val brain = RaijinBrain(FakeMemory(), RecordingActuator(), cloudReasoning = failingCloud)

        val res = brain.processUtterance("Draft a speech", "trace-fail-cloud")
        assertEquals(ActionType.NONE, res.actionType)
        val failed = assertIs<IslandVisualState.Failed>(brain.islandState.value)
        assertTrue(failed.errorReason.contains("Rate limit exceeded"))
        assertTrue(failed.recoverable)
    }

    @Test
    fun openAppStripsTrailingPunctuationAndFillers() = runTest {
        val cases = mapOf(
            "open WhatsApp please." to "WhatsApp",
            "Launch the Chrome for me!" to "Chrome",
            "open YouTube now" to "YouTube",
            "open Spotify please for me." to "Spotify",
        )
        for ((utterance, expectedApp) in cases) {
            val actuator = RecordingActuator()
            val brain = RaijinBrain(FakeMemory(), actuator)

            val result = brain.processUtterance(utterance, "trace-clean")

            assertEquals(ActionType.OPEN_APP, result.actionType, utterance)
            val target = assertIs<ActionTarget.App>(actuator.actions.single().targets.single())
            assertEquals(expectedApp, target.displayName, utterance)
        }
    }

    @Test
    fun openAppKeepsNamesThatMerelyEndWithAFillerWord() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        brain.processUtterance("open Snow", "trace-snow")

        val target = assertIs<ActionTarget.App>(actuator.actions.single().targets.single())
        assertEquals("Snow", target.displayName)
    }
    @Test
    fun greetingGetsInstantLocalReplyWithoutCloudOrActuator() = runTest {
        val actuator = RecordingActuator()
        val cloudThatMustNotBeCalled = object : CloudReasoningPort {
            override val isConfigured: Boolean = true
            override suspend fun complete(prompt: String, systemPrompt: String?, temperature: Float): String =
                error("cloud must not be consulted for small talk (PRD BR-1)")
        }
        val brain = RaijinBrain(FakeMemory(), actuator, cloudReasoning = cloudThatMustNotBeCalled)

        val res = brain.processUtterance("hi", "trace-hi")

        assertEquals(ActionType.CHAT_REPLY, res.actionType)
        assertEquals(1.0f, res.confidence)
        assertTrue(res.vocalResponse!!.isNotBlank())
        assertEquals(0, actuator.actions.size)
        assertIs<IslandVisualState.Done>(brain.islandState.value)
    }

    @Test
    fun hindiGreetingGetsHinglishReply() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("Namaste", "trace-namaste")

        assertEquals(ActionType.CHAT_REPLY, res.actionType)
        assertTrue(res.vocalResponse!!.startsWith("Namaste"))
        assertEquals(0, actuator.actions.size)
    }

    @Test
    fun smallTalkVariantsAllResolveLocally() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val phrases = listOf(
            "hello", "hey raijin", "how are you", "kaise ho", "thanks",
            "shukriya", "good morning", "good night", "bye", "who are you",
            "what can you do",
        )
        phrases.forEachIndexed { i, phrase ->
            val res = brain.processUtterance(phrase, "trace-chat-$i")
            assertEquals(ActionType.CHAT_REPLY, res.actionType, "phrase: $phrase")
            assertTrue(res.vocalResponse!!.isNotBlank(), "phrase: $phrase")
        }
        assertEquals(0, actuator.actions.size)
    }

    @Test
    fun unknownUtteranceStillFailsWhenCloudUnconfigured() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("tell me something obscure", "trace-unknown")

        assertEquals(ActionType.NONE, res.actionType)
        assertEquals(0.0f, res.confidence)
        assertIs<IslandVisualState.Failed>(brain.islandState.value)
    }
    // ---------------- TM-4: parser-backed reminders ----------------

private fun localDateTime(epochMs: Long): LocalDateTime =
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    
        @Test
        fun kalSubahReminderGetsRealTriggerTime() = runTest {
            val actuator = RecordingActuator()
            val brain = RaijinBrain(FakeMemory(), actuator)
    
            val res = brain.processUtterance("remind me to call maa kal subah 6 baje", "t1")
            assertEquals(ActionType.CREATE_REMINDER, res.actionType)
            val args = assertIs<ActionArgs.Reminder>(res.args)
            assertEquals("call maa", args.title)
            val trig = localDateTime(args.triggerAtEpochMillis)
            assertEquals(LocalDateTime.now().toLocalDate().plusDays(1), trig.toLocalDate())
            assertEquals(6, trig.hour)
            assertEquals(0, trig.minute)
            assertEquals(null, args.recurrenceRule)
            assertEquals(1, actuator.actions.size)
        }
    
        @Test
        fun hindiMujheYaadDilanaForm() = runTest {
            val actuator = RecordingActuator()
            val brain = RaijinBrain(FakeMemory(), actuator)
    
            val res = brain.processUtterance("mujhe dawai yaad dilana raat 9 baje", "t2")
            assertEquals(ActionType.CREATE_REMINDER, res.actionType)
            val args = assertIs<ActionArgs.Reminder>(res.args)
            assertEquals("dawai", args.title)
            val trig = localDateTime(args.triggerAtEpochMillis)
            assertEquals(21, trig.hour)
            assertTrue(args.triggerAtEpochMillis > System.currentTimeMillis() - 60_000)
        }
    
        @Test
        fun parsoReminderDefaultsToSubah() = runTest {
            val actuator = RecordingActuator()
            val brain = RaijinBrain(FakeMemory(), actuator)
    
            val res = brain.processUtterance("set a reminder to pay rent parso", "t3")
            assertEquals(ActionType.CREATE_REMINDER, res.actionType)
            val args = assertIs<ActionArgs.Reminder>(res.args)
            assertEquals("pay rent", args.title)
            val trig = localDateTime(args.triggerAtEpochMillis)
            assertEquals(LocalDateTime.now().toLocalDate().plusDays(2), trig.toLocalDate())
            assertEquals(7, trig.hour)
        }
    
        @Test
        fun dailyReminderCarriesRecurrenceRule() = runTest {
            val actuator = RecordingActuator()
            val brain = RaijinBrain(FakeMemory(), actuator)
    
            val res = brain.processUtterance("remind me to drink water roz 6 baje", "t4")
            assertEquals(ActionType.CREATE_REMINDER, res.actionType)
            val args = assertIs<ActionArgs.Reminder>(res.args)
            assertEquals("drink water", args.title)
            assertEquals("DAILY", args.recurrenceRule)
            assertTrue(res.vocalResponse?.contains("Roz") == true)
        }
    
        @Test
        fun weeklyReminderCarriesRecurrenceRule() = runTest {
            val actuator = RecordingActuator()
            val brain = RaijinBrain(FakeMemory(), actuator)
    
            val res = brain.processUtterance("remind me to call maa har somvar 8 baje", "t5")
            assertEquals(ActionType.CREATE_REMINDER, res.actionType)
            val args = assertIs<ActionArgs.Reminder>(res.args)
            assertEquals("call maa", args.title)
            assertEquals("WEEKLY", args.recurrenceRule)
            assertTrue(res.vocalResponse?.contains("Har hafte") == true)
        }
    
        @Test
        fun strictInMinutesFormStillWorks() = runTest {
            val actuator = RecordingActuator()
            val brain = RaijinBrain(FakeMemory(), actuator)
    
            val res = brain.processUtterance("remind me to take medicine in 10 minutes", "t6")
            assertEquals(ActionType.CREATE_REMINDER, res.actionType)
            val args = assertIs<ActionArgs.Reminder>(res.args)
            assertEquals("take medicine", args.title)
            val delta = args.triggerAtEpochMillis - System.currentTimeMillis()
            assertTrue(delta in 540_000..660_000, "expected ~10 minutes out, was ${delta}ms")
        }
    
        @Test
        fun reminderWithoutTitleFallsThroughToUnknown() = runTest {
            val actuator = RecordingActuator()
            val brain = RaijinBrain(FakeMemory(), actuator)
    
            // Only a time, nothing to remember: must not create a blank reminder.
            val res = brain.processUtterance("remind me kal subah", "t7")
            assertTrue(res.actionType != ActionType.CREATE_REMINDER || (res.args as? ActionArgs.Reminder)?.title?.isNotBlank() == true)
            assertEquals(0, actuator.actions.size)
        }


    // ---------------- TM-2/TM-3: alarms and timers ----------------

    @Test
    fun wakeMeAtClockTimeSetsAlarm() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("wake me at 6:30", "a1")
        assertEquals(ActionType.SET_ALARM, res.actionType)
        val args = assertIs<ActionArgs.Alarm>(res.args)
        // bare "6:30" defaults to the next occurrence; 6:30 AM or PM, minute is exact
        assertEquals(30, args.minute)
        assertTrue(args.hour == 6 || args.hour == 18)
        assertEquals(1, actuator.actions.size)
    }

    @Test
    fun setAlarmForHinglishClockTime() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("set an alarm for subah 6 baje", "a2")
        assertEquals(ActionType.SET_ALARM, res.actionType)
        val args = assertIs<ActionArgs.Alarm>(res.args)
        assertEquals(6, args.hour)
        assertEquals(0, args.minute)
    }

    @Test
    fun kaAlarmLagaoForm() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("subah 7 baje ka alarm lagao", "a3")
        assertEquals(ActionType.SET_ALARM, res.actionType)
        val args = assertIs<ActionArgs.Alarm>(res.args)
        assertEquals(7, args.hour)
        assertEquals(0, args.minute)
    }

    @Test
    fun timerForMinutesSetsTimer() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("set a timer for 5 minutes", "a4")
        assertEquals(ActionType.SET_TIMER, res.actionType)
        val args = assertIs<ActionArgs.Timer>(res.args)
        assertEquals(300, args.lengthSeconds)
        assertEquals(1, actuator.actions.size)
    }

    @Test
    fun timerHinglishKaTimerForm() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("20 minute ka timer lagao", "a5")
        assertEquals(ActionType.SET_TIMER, res.actionType)
        val args = assertIs<ActionArgs.Timer>(res.args)
        assertEquals(1200, args.lengthSeconds)
    }

    @Test
    fun timerAadhaGhanta() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("set a timer for aadha ghanta", "a6")
        assertEquals(ActionType.SET_TIMER, res.actionType)
        val args = assertIs<ActionArgs.Timer>(res.args)
        assertEquals(1800, args.lengthSeconds)
    }

    @Test
    fun alarmWordsDoNotSwallowReminders() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        // reminder path must stay intact
        val res = brain.processUtterance("remind me to call maa in 10 minutes", "a7")
        assertEquals(ActionType.CREATE_REMINDER, res.actionType)
        assertIs<ActionArgs.Reminder>(res.args)
    }

    @Test
    fun contractRejectsBadAlarmAndTimer() {
        assertFailsWith<IllegalArgumentException> { ActionArgs.Alarm(hour = 24, minute = 0) }
        assertFailsWith<IllegalArgumentException> { ActionArgs.Alarm(hour = 6, minute = 60) }
        assertFailsWith<IllegalArgumentException> { ActionArgs.Timer(lengthSeconds = 0) }
    }

    // ---------------- TM-7: cancel / list ----------------

    @Test
    fun cancelReminderByClockTime() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("cancel my 6 o'clock reminder", "c1")
        assertEquals(ActionType.CANCEL_REMINDER, res.actionType)
        val args = assertIs<ActionArgs.ReminderQuery>(res.args)
        assertTrue(args.hour == 6 || args.hour == 18)
        assertEquals(0, args.minute)
    }

    @Test
    fun cancelReminderByTitle() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("cancel the reminder to call maa", "c2")
        assertEquals(ActionType.CANCEL_REMINDER, res.actionType)
        val args = assertIs<ActionArgs.ReminderQuery>(res.args)
        assertEquals("call maa", args.titleFragment)
        assertEquals(null, args.hour)
    }

    @Test
    fun cancelAlarmRoutesToDismissAlarm() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("cancel my 6:30 alarm", "c3")
        assertEquals(ActionType.DISMISS_ALARM, res.actionType)
        val args = assertIs<ActionArgs.Alarm>(res.args)
        assertEquals(30, args.minute)
    }

    @Test
    fun whatRemindersDoIHaveLists() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("what reminders do I have?", "c4")
        assertEquals(ActionType.LIST_REMINDERS, res.actionType)
        assertEquals(1, actuator.actions.size)
    }

    @Test
    fun cancelDoesNotSwallowCreateReminder() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("remind me to call maa in 10 minutes", "c5")
        assertEquals(ActionType.CREATE_REMINDER, res.actionType)
    }

    @Test
    fun contractRejectsEmptyReminderQuery() {
        assertFailsWith<IllegalArgumentException> { ActionArgs.ReminderQuery() }
        assertFailsWith<IllegalArgumentException> { ActionArgs.ReminderQuery(hour = 25) }
    }

    // ---------------- AP-3: app settings ----------------

    @Test
    fun openWhatsappSettingsEn() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("open whatsapp settings", "s1")
        assertEquals(ActionType.OPEN_APP_SETTINGS, res.actionType)
        val target = res.targets.filterIsInstance<ActionTarget.App>().first()
        assertEquals("whatsapp", target.displayName)
        assertEquals(1, actuator.actions.size)
    }

    @Test
    fun openSettingsHinglish() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("whatsapp ki settings kholo", "s2")
        assertEquals(ActionType.OPEN_APP_SETTINGS, res.actionType)
        val target = res.targets.filterIsInstance<ActionTarget.App>().first()
        assertEquals("whatsapp", target.displayName)
    }

    @Test
    fun plainOpenAppStillWorks() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val res = brain.processUtterance("open whatsapp", "s3")
        assertEquals(ActionType.OPEN_APP, res.actionType)
    }

    // ── AP-3: deep links into known apps ──────────────────────────────────

    @Test
    fun mapsSearchDispatchesDeepLinkWithoutConfirmation() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val result = brain.processUtterance("search maps for coffee shops", "t-maps")

        assertEquals(ActionType.OPEN_DEEP_LINK, result.actionType)
        val args = result.args as ActionArgs.DeepLink
        assertEquals("maps", args.app)
        assertEquals("coffee shops", args.target)
        assertEquals(1, actuator.actions.size)
    }

    @Test
    fun openMapsAndFindDispatchesDeepLink() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)

        val result = brain.processUtterance("open maps and find petrol pump", "t-maps2")

        assertEquals(ActionType.OPEN_DEEP_LINK, result.actionType)
        assertEquals("petrol pump", (result.args as ActionArgs.DeepLink).target)
    }

    @Test
    fun whatsappChatResolvesContactThenOpensOnConfirm() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory(
                "Rahul" to listOf(EntityCandidate("contact:+919876543210", "Rahul", 0.95f)),
            ),
            actuator,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))

        val result = brain.processUtterance("open whatsapp chat with Rahul", "t-wa")

        assertEquals(ActionType.OPEN_DEEP_LINK, result.actionType)
        assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)
        assertTrue(actuator.actions.isEmpty())

        brain.resolveActionConfirmation("t-wa", true)
        assertEquals(1, actuator.actions.size)
        val args = actuator.actions.single().args as ActionArgs.DeepLink
        assertEquals("whatsapp", args.app)
        assertEquals("Rahul", args.target)
    }

    @Test
    fun whatsappChatPhraseIsNotParsedAsGenericAppOpen() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory(
                "Rahul" to listOf(EntityCandidate("contact:+919876543210", "Rahul", 0.95f)),
            ),
            actuator,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))

        val result = brain.processUtterance("open whatsapp chat with Rahul", "t-wa2")

        // Deep link, not an attempt to open an app literally named
        // "whatsapp chat with Rahul".
        assertEquals(ActionType.OPEN_DEEP_LINK, result.actionType)
    }

    @Test
    fun whatsappChatConfirmationShowsNormalizedNumberAndRegionAssumption() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory(
                "Rahul" to listOf(EntityCandidate("contact:9876543210", "Rahul", 0.95f)),
            ),
            actuator,
        )
        brain.submitCapabilitySnapshot(
            CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true, deviceRegionIso = "IN"),
        )

        brain.processUtterance("open whatsapp chat with Rahul", "t-wa3")

        val prompt = (brain.islandState.value as IslandVisualState.NeedsInput).prompt
        assertTrue(prompt.contains("+919876543210"), "prompt should show the normalized number, was: $prompt")
        // The device region is a suggestion, presented as an assumption -
        // never as verified metadata.
        assertTrue(prompt.contains("assuming India"), "prompt should state the region assumption, was: $prompt")
    }

    @Test
    fun whatsappChatConfirmationWithExplicitNumberHasNoAssumption() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory(
                "Rahul" to listOf(EntityCandidate("contact:+44 7911 123456", "Rahul", 0.95f)),
            ),
            actuator,
        )
        brain.submitCapabilitySnapshot(
            CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true, deviceRegionIso = "IN"),
        )

        brain.processUtterance("open whatsapp chat with Rahul", "t-wa3b")

        val prompt = (brain.islandState.value as IslandVisualState.NeedsInput).prompt
        assertTrue(prompt.contains("+447911123456"), "prompt should show the explicit number, was: $prompt")
        assertFalse(prompt.contains("assuming"), "explicit international numbers carry no assumption, was: $prompt")
    }

    @Test
    fun ambiguousNationalNumberAsksForCountryOrFullNumber() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory(
                "Rahul" to listOf(EntityCandidate("contact:9876543210", "Rahul", 0.95f)),
            ),
            actuator,
        )
        // No deviceRegionIso: the device cannot say which country applies.
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))

        brain.processUtterance("open whatsapp chat with Rahul", "t-wa4")

        // No confirmation offered, nothing executed: a clarification question.
        val state = brain.islandState.value
        assertIs<IslandVisualState.NeedsInput>(state)
        assertTrue(state.prompt.contains("Say the country"), "should ask for country or full number, was: ${state.prompt}")
        assertTrue(actuator.actions.isEmpty())
    }

    @Test
    fun ambiguousNumberResolvedBySpokenCountry() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory(
                "Rahul" to listOf(EntityCandidate("contact:9876543210", "Rahul", 0.95f)),
            ),
            actuator,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))

        brain.processUtterance("open whatsapp chat with Rahul", "t-wa5")
        assertIs<IslandVisualState.NeedsInput>(brain.islandState.value)

        // The user's spoken answer is explicit evidence: the confirmation
        // shows the resulting number with no device assumption.
        brain.processUtterance("India", "t-wa5b")
        val prompt = (brain.islandState.value as IslandVisualState.NeedsInput).prompt
        assertTrue(prompt.contains("+919876543210"), "was: $prompt")
        assertFalse(prompt.contains("assuming"), "user-stated country is not an assumption, was: $prompt")
    }

    @Test
    fun ambiguousNumberResolvedByFullInternationalNumber() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory(
                "Rahul" to listOf(EntityCandidate("contact:9876543210", "Rahul", 0.95f)),
            ),
            actuator,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))

        brain.processUtterance("open whatsapp chat with Rahul", "t-wa6")
        brain.processUtterance("+44 7911 123456", "t-wa6b")

        val prompt = (brain.islandState.value as IslandVisualState.NeedsInput).prompt
        assertTrue(prompt.contains("+447911123456"), "was: $prompt")
        assertFalse(prompt.contains("assuming"), "was: $prompt")
    }

    @Test
    fun ambiguousNumberWithUnknownCountryFailsHonestly() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(
            FakeMemory(
                "Rahul" to listOf(EntityCandidate("contact:9876543210", "Rahul", 0.95f)),
            ),
            actuator,
        )
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))

        brain.processUtterance("open whatsapp chat with Rahul", "t-wa7")
        brain.processUtterance("Narnia", "t-wa7b")

        assertIs<IslandVisualState.Failed>(brain.islandState.value)
        assertTrue(actuator.actions.isEmpty(), "no guessed identity may execute")
    }

    // ── AP-2: learned app aliases from explicit corrections ──────────────

    @Test
    fun appCorrectionLearnsAliasAndOpensIntendedApp() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory()
        val brain = RaijinBrain(memory, actuator)

        brain.processUtterance("open insta", "t-open")
        assertEquals(1, actuator.actions.size)

        val result = brain.processUtterance("wrong app, I meant Instagram", "t-fix")

        assertEquals(ActionType.OPEN_APP, result.actionType)
        assertEquals(listOf("insta" to "Instagram"), memory.learnedAppAliases)
        assertEquals(2, actuator.actions.size)
        val reopened = actuator.actions.last().targets.filterIsInstance<ActionTarget.App>().single()
        assertEquals("Instagram", reopened.displayName)
        assertTrue(result.vocalResponse?.contains("Instagram") == true)
    }

    @Test
    fun plainCancelAfterAppOpenTeachesNothing() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory()
        val brain = RaijinBrain(memory, actuator)

        brain.processUtterance("open insta", "t-open")
        brain.processUtterance("no", "t-cancel")

        assertTrue(memory.learnedAppAliases.isEmpty())
        assertEquals(1, actuator.actions.size)
    }

    @Test
    fun appCorrectionWithoutRecentOpenTeachesNothing() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory()
        val brain = RaijinBrain(memory, actuator)

        brain.processUtterance("wrong app, I meant Instagram", "t-fix")

        assertTrue(memory.learnedAppAliases.isEmpty())
        assertTrue(actuator.actions.none { it.actionType == ActionType.OPEN_APP })
    }

    @Test
    fun bareWrongAppWithoutRestatementTeachesNothing() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory()
        val brain = RaijinBrain(memory, actuator)

        brain.processUtterance("open insta", "t-open")
        brain.processUtterance("wrong app", "t-fix")

        assertTrue(memory.learnedAppAliases.isEmpty())
        assertEquals(1, actuator.actions.size)
    }

}

private class FakeMemory(
    vararg responses: Pair<String, List<EntityCandidate>>,
) : RaijinMemoryPort {
    private val byQuery = responses.toMap()
    val learnedAliases = mutableListOf<Triple<String, String, String>>()

    override suspend fun learnEntityAlias(spokenForm: String, entityId: String, displayName: String) {
        learnedAliases += Triple(spokenForm, entityId, displayName)
    }

    val corrections = mutableListOf<Pair<String, String>>()

    val learnedAppAliases = mutableListOf<Pair<String, String>>()

    override suspend fun learnAppAlias(spokenForm: String, intendedAppName: String) {
        learnedAppAliases += spokenForm to intendedAppName
    }

    override suspend fun recordContactCorrection(wrongAlias: String, entityId: String) {
        corrections += wrongAlias to entityId
    }

    override suspend fun search(query: MemoryQuery): List<MemoryHit> = emptyList()

    override suspend fun resolveEntity(
        nameOrAlias: String,
        limit: Int,
    ): List<EntityCandidate> = byQuery[nameOrAlias].orEmpty().take(limit)

    override suspend fun remember(write: MemoryWrite): String = "memory:test"

    override suspend fun forget(memoryId: String): Boolean = false
}

private class RecordingActuator(
    private val result: ActionResult = ActionResult.EXECUTED(),
    private val beforeResult: suspend () -> Unit = {},
) : ActuatorPort {
    val actions = mutableListOf<io.raijin.contract.DispatchResult>()

    override suspend fun execute(action: io.raijin.contract.DispatchResult): ActionResult {
        actions += action
        beforeResult()
        return result
}
}

class AppLaunchRegressionTest {
    @Test fun hindiSuffixOpensApp() = runTest {
        val a = RecordingActuator()
        val b = RaijinBrain(FakeMemory(), a)
        val r = b.processUtterance("whatsapp kholo", "open-hindi")
        assertEquals(ActionType.OPEN_APP, r.actionType)
        assertEquals("whatsapp", assertIs<ActionTarget.App>(r.targets.single()).displayName)
    }
    @Test fun appSuffixIsNotPartOfName() = runTest {
        val a = RecordingActuator()
        val b = RaijinBrain(FakeMemory(), a)
        val r = b.processUtterance("open whatsapp app please", "open-app-suffix")
        assertEquals(ActionType.OPEN_APP, r.actionType)
        assertEquals("whatsapp", assertIs<ActionTarget.App>(r.targets.single()).displayName)
    }
}

class SpeechAlternativesRegressionTest {
    @Test fun secondarySpeechNameCanResolveContactWithoutSendingAlternativeTextToCloud() = runTest {
        val a = RecordingActuator()
        val b = RaijinBrain(FakeMemory("Rahul" to listOf(EntityCandidate("contact:+919876543210", "Rahul", 0.99f))), a)
        b.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        val r = b.processAlternatives(listOf("call Raaul", "call Rahul"), "nbest-call")
        assertEquals(ActionType.CALL_CONTACT, r.actionType)
        assertEquals("contact:+919876543210", r.targets.single().let { assertIs<ActionTarget.Contact>(it).ref })
        assertEquals(0, a.actions.size) // still requires owner confirmation
    }
    @Test fun alternativeCannotSwitchPrimaryActionType() = runTest {
        val a = RecordingActuator()
        val b = RaijinBrain(FakeMemory(), a)
        val r = b.processAlternatives(listOf("open whatsapp", "call Rahul"), "nbest-type")
        assertEquals(ActionType.OPEN_APP, r.actionType)
        assertEquals(1, a.actions.size)
    }
}


class ContactAliasLearningTest {

    private fun callCapableBrain(memory: FakeMemory, actuator: RecordingActuator): RaijinBrain {
        val brain = RaijinBrain(memory, actuator)
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        return brain
    }

    @Test
    fun confirmedClarifiedPickLearnsSpokenFormAndClarificationAnswer() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory(
            "Rahul" to listOf(
                EntityCandidate("contact:rahul-work", "Rahul Work", 0.88f),
                EntityCandidate("contact:rahul-home", "Rahul Home", 0.82f),
            ),
            "office" to listOf(EntityCandidate("contact:rahul-work", "Rahul Work", 0.94f)),
        )
        val brain = callCapableBrain(memory, actuator)

        brain.processUtterance("call Rahul", "trace-learn-clar")
        brain.resolveClarification("trace-learn-clar", "office")
        brain.resolveActionConfirmation("trace-learn-clar", true)

        assertEquals(1, actuator.actions.size)
        val learned = memory.learnedAliases.map { it.first to it.second }
        assertTrue("rahul" to "contact:rahul-work" in learned)
        assertTrue("office" to "contact:rahul-work" in learned)
    }

    @Test
    fun confirmedChoiceLearnsOriginalMispronunciation() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory(
            "Rahul" to listOf(
                EntityCandidate("contact:rahul-work", "Rahul Work", 0.88f),
                EntityCandidate("contact:rahul-home", "Rahul Home", 0.82f),
            ),
        )
        val brain = callCapableBrain(memory, actuator)

        brain.processUtterance("call Rahul", "trace-learn-choice")
        brain.resolveChoice("trace-learn-choice", "contact:rahul-home")
        brain.resolveActionConfirmation("trace-learn-choice", true)

        assertEquals(1, actuator.actions.size)
        assertEquals(listOf(Triple("rahul", "contact:rahul-home", "Rahul Home")), memory.learnedAliases)
    }

    @Test
    fun exactCanonicalNameIsNotLearnedAgain() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory(
            "Rahul Work" to listOf(EntityCandidate("contact:rahul-work", "Rahul Work", 0.97f)),
        )
        val brain = callCapableBrain(memory, actuator)

        brain.processUtterance("call Rahul Work", "trace-learn-exact")
        brain.resolveActionConfirmation("trace-learn-exact", true)

        assertEquals(1, actuator.actions.size)
        assertTrue(memory.learnedAliases.isEmpty())
    }

    @Test
    fun cancelledConfirmationLearnsNothing() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory(
            "Rahul" to listOf(EntityCandidate("contact:rahul-work", "Rahul Work", 0.97f)),
        )
        val brain = callCapableBrain(memory, actuator)

        brain.processUtterance("call Rahul", "trace-learn-cancel")
        brain.resolveActionConfirmation("trace-learn-cancel", false)

        assertTrue(actuator.actions.isEmpty())
        assertTrue(memory.learnedAliases.isEmpty())
    }

    @Test
    fun learningFailureDoesNotBlockConfirmedAction() = runTest {
        val actuator = RecordingActuator()
        val memory = object : RaijinMemoryPort {
            override suspend fun search(query: MemoryQuery): List<MemoryHit> = emptyList()
            override suspend fun resolveEntity(nameOrAlias: String, limit: Int): List<EntityCandidate> =
                listOf(EntityCandidate("contact:rahul-work", "Rahul Work", 0.97f))
            override suspend fun learnEntityAlias(spokenForm: String, entityId: String, displayName: String) {
                error("alias store unavailable")
            }
            override suspend fun remember(write: MemoryWrite): String = "unused"
            override suspend fun forget(memoryId: String): Boolean = false
        }
        val brain = RaijinBrain(memory, actuator)
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))

        brain.processUtterance("call Rahul", "trace-learn-throw")
        brain.resolveActionConfirmation("trace-learn-throw", true)

        assertEquals(1, actuator.actions.size)
    }
}


class ContactCorrectionRecordingTest {

    private fun callCapableBrain(memory: FakeMemory, actuator: RecordingActuator): RaijinBrain {
        val brain = RaijinBrain(memory, actuator)
        brain.submitCapabilitySnapshot(CapabilitySnapshot(contactsAvailable = true, phoneCallAvailable = true))
        return brain
    }

    @Test
    fun plainRejectionCancelsWithoutRecordingCorrection() = runTest {
        // A plain "no" to "Call Rahul Work?" means cancel the action, NOT
        // "Rahul is the wrong contact". No correction may be recorded.
        val actuator = RecordingActuator()
        val memory = FakeMemory(
            "Rahul" to listOf(EntityCandidate("contact:rahul-work", "Rahul Work", 0.94f)),
        )
        val brain = callCapableBrain(memory, actuator)

        brain.processUtterance("call Rahul", "trace-reject")
        brain.resolveActionConfirmation("trace-reject", false)

        assertEquals(0, actuator.actions.size)
        assertTrue(memory.corrections.isEmpty())
        assertTrue(memory.learnedAliases.isEmpty())
    }

    @Test
    fun explicitCorrectionPhraseRecordsAndCancels() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory(
            "Rahul" to listOf(EntityCandidate("contact:rahul-work", "Rahul Work", 0.94f)),
        )
        val brain = callCapableBrain(memory, actuator)

        brain.processUtterance("call Rahul", "trace-call")
        brain.processUtterance("wrong number", "trace-correct")

        assertEquals(0, actuator.actions.size)
        assertEquals(
            listOf("rahul" to "contact:rahul-work"),
            memory.corrections.map { it.first.lowercase() to it.second },
        )
    }

    @Test
    fun iMeantPhraseAlsoCountsAsExplicitCorrection() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory(
            "Rahul" to listOf(EntityCandidate("contact:rahul-work", "Rahul Work", 0.94f)),
        )
        val brain = callCapableBrain(memory, actuator)

        brain.processUtterance("call Rahul", "trace-call2")
        brain.processUtterance("no, I meant Rahul home", "trace-correct2")

        assertEquals(
            listOf("rahul" to "contact:rahul-work"),
            memory.corrections.map { it.first.lowercase() to it.second },
        )
    }

    @Test
    fun correctionPhraseWithoutPendingContactIsHarmless() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory()
        val brain = callCapableBrain(memory, actuator)

        brain.processUtterance("wrong number", "trace-stray")

        assertTrue(memory.corrections.isEmpty())
        assertTrue(actuator.actions.isEmpty())
    }

    @Test
    fun choosingAmongCandidatesRecordsNoCorrection() = runTest {
        // Picking "Rahul Home" from a "Which Rahul?" clarification is normal
        // disambiguation, not a correction of "Rahul Work".
        val actuator = RecordingActuator()
        val memory = FakeMemory(
            "Rahul" to listOf(
                EntityCandidate("contact:rahul-work", "Rahul Work", 0.88f),
                EntityCandidate("contact:rahul-home", "Rahul Home", 0.82f),
            ),
        )
        val brain = callCapableBrain(memory, actuator)

        brain.processUtterance("call Rahul", "trace-choice")
        brain.resolveChoice("trace-choice", "contact:rahul-home")
        brain.resolveActionConfirmation("trace-choice", true)

        assertEquals(1, actuator.actions.size)
        assertTrue(memory.corrections.isEmpty())
    }

    @Test
    fun rejectedNonContactActionRecordsNoCorrection() = runTest {
        val actuator = RecordingActuator()
        val memory = FakeMemory()
        val brain = RaijinBrain(memory, actuator)
        brain.submitCapabilitySnapshot(CapabilitySnapshot(storageAvailable = true))

        brain.processUtterance("clean up my storage", "trace-reject-clean")
        brain.resolveActionConfirmation("trace-reject-clean", false)

        assertEquals(0, actuator.actions.size)
        assertTrue(memory.corrections.isEmpty())
    }

    @Test
    fun `MU-8 pasand aaya records a like feedback dispatch`() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)
        val res = brain.processUtterance("pasand aaya", "trace-like")
        assertEquals(ActionType.MUSIC_FEEDBACK, res.actionType)
        assertEquals(MusicFeedback.LIKED, assertIs<ActionArgs.Feedback>(res.args).sentiment)
        assertTrue(actuator.actions.any { it.actionType == ActionType.MUSIC_FEEDBACK })
    }

    @Test
    fun `MU-8 ye mat chalao is feedback, never a play query`() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)
        val res = brain.processUtterance("ye mat chalao", "trace-dislike")
        assertEquals(ActionType.MUSIC_FEEDBACK, res.actionType)
        assertEquals(MusicFeedback.DISLIKED, assertIs<ActionArgs.Feedback>(res.args).sentiment)
        assertTrue(actuator.actions.none { it.actionType == ActionType.MUSIC_CONTROL })
    }

    @Test
    fun `MU-8 aur aisa asks for more like the current track`() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)
        val res = brain.processUtterance("aur aisa", "trace-more")
        assertEquals(ActionType.MUSIC_FEEDBACK, res.actionType)
        assertEquals(MusicFeedback.MORE_LIKE_THIS, assertIs<ActionArgs.Feedback>(res.args).sentiment)
    }

    @Test
    fun `MU-8 aisa hi chalao is feedback, not a play query`() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)
        val res = brain.processUtterance("aisa hi chalao", "trace-more2")
        assertEquals(ActionType.MUSIC_FEEDBACK, res.actionType)
        assertEquals(MusicFeedback.MORE_LIKE_THIS, assertIs<ActionArgs.Feedback>(res.args).sentiment)
    }

    @Test
    fun `MU-8 ordinary play queries still parse as PLAY_QUERY`() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)
        val res = brain.processUtterance("arijit singh chalao", "trace-playquery")
        assertEquals(ActionType.MUSIC_CONTROL, res.actionType)
        assertEquals(MusicCommand.PLAY_QUERY, assertIs<ActionArgs.Music>(res.args).command)
    }

    @Test
    fun `MU-7 play something dispatches PLAY_SOMETHING, never a query for something`() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)
        val res = brain.processUtterance("play something", "trace-something")
        assertEquals(ActionType.MUSIC_CONTROL, res.actionType)
        val args = assertIs<ActionArgs.Music>(res.args)
        assertEquals(MusicCommand.PLAY_SOMETHING, args.command)
        assertNull(args.query)
    }

    @Test
    fun `MU-7 kuch chalao asks for a taste pick`() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)
        val res = brain.processUtterance("kuch chalao", "trace-kuch")
        assertEquals(MusicCommand.PLAY_SOMETHING, assertIs<ActionArgs.Music>(res.args).command)
    }

    @Test
    fun `MU-7 named play queries still parse as PLAY_QUERY`() = runTest {
        val actuator = RecordingActuator()
        val brain = RaijinBrain(FakeMemory(), actuator)
        val res = brain.processUtterance("play something by arijit", "trace-pq")
        val args = assertIs<ActionArgs.Music>(res.args)
        assertEquals(MusicCommand.PLAY_QUERY, args.command)
        assertEquals("something by arijit", args.query)
    }
}


class UndoChipTest {

    private class UndoRecordingActuator(
        private val executeResult: ActionResult,
        private val undoResult: ActionResult = ActionResult.EXECUTED("Undone."),
    ) : ActuatorPort {
        val undoCalls = mutableListOf<UndoAction>()

        override suspend fun execute(action: io.raijin.contract.DispatchResult): ActionResult = executeResult

        override suspend fun undo(action: UndoAction): ActionResult {
            undoCalls += action
            return undoResult
        }
    }

    @Test
    fun `TM-7 undo-offering EXECUTED emits Undoable instead of Done`() = runTest {
        val actuator = UndoRecordingActuator(
            ActionResult.EXECUTED(undoAction = UndoAction.DeleteReminder(42L)),
        )
        val brain = RaijinBrain(FakeMemory(), actuator)
        brain.processUtterance("remind me to play cricket at 6 pm", "undo-emit")
        val state = brain.islandState.value
        assertIs<IslandVisualState.Undoable>(state)
        assertEquals(ActionResult.DEFAULT_UNDO_WINDOW_MS, state.windowMs)
    }

    @Test
    fun `TM-7 accepting undo runs the actuator reversal and confirms`() = runTest {
        val actuator = UndoRecordingActuator(
            ActionResult.EXECUTED(undoAction = UndoAction.DeleteReminder(42L)),
        )
        val brain = RaijinBrain(FakeMemory(), actuator)
        brain.processUtterance("remind me to play cricket at 6 pm", "undo-accept")
        val token = assertIs<IslandVisualState.Undoable>(brain.islandState.value).undoToken
        brain.resolveUndo(token, accepted = true)
        assertEquals(listOf(UndoAction.DeleteReminder(42L)), actuator.undoCalls)
        assertIs<IslandVisualState.Done>(brain.islandState.value)
    }

    @Test
    fun `TM-7 dismissing undo runs nothing and returns to idle`() = runTest {
        val actuator = UndoRecordingActuator(
            ActionResult.EXECUTED(undoAction = UndoAction.DeleteReminder(42L)),
        )
        val brain = RaijinBrain(FakeMemory(), actuator)
        brain.processUtterance("remind me to play cricket at 6 pm", "undo-dismiss")
        val token = assertIs<IslandVisualState.Undoable>(brain.islandState.value).undoToken
        brain.resolveUndo(token, accepted = false)
        assertTrue(actuator.undoCalls.isEmpty())
        assertIs<IslandVisualState.Idle>(brain.islandState.value)
    }

    @Test
    fun `TM-7 undo token is one-shot`() = runTest {
        val actuator = UndoRecordingActuator(
            ActionResult.EXECUTED(undoAction = UndoAction.DeleteReminder(42L)),
        )
        val brain = RaijinBrain(FakeMemory(), actuator)
        brain.processUtterance("remind me to play cricket at 6 pm", "undo-once")
        val token = assertIs<IslandVisualState.Undoable>(brain.islandState.value).undoToken
        brain.resolveUndo(token, accepted = true)
        brain.resolveUndo(token, accepted = true)
        assertEquals(1, actuator.undoCalls.size)
        assertIs<IslandVisualState.Done>(brain.islandState.value)
    }

    @Test
    fun `TM-7 plain EXECUTED without undo offer still emits Done`() = runTest {
        val actuator = UndoRecordingActuator(ActionResult.EXECUTED())
        val brain = RaijinBrain(FakeMemory(), actuator)
        brain.processUtterance("remind me to play cricket at 6 pm", "no-undo")
        assertIs<IslandVisualState.Done>(brain.islandState.value)
        assertTrue(actuator.undoCalls.isEmpty())
    }

    @Test
    fun `TM-7 failed reversal surfaces an honest failure`() = runTest {
        val actuator = UndoRecordingActuator(
            executeResult = ActionResult.EXECUTED(undoAction = UndoAction.DeleteReminder(42L)),
            undoResult = ActionResult.FAILED("Reminder already gone"),
        )
        val brain = RaijinBrain(FakeMemory(), actuator)
        brain.processUtterance("remind me to play cricket at 6 pm", "undo-fail")
        val token = assertIs<IslandVisualState.Undoable>(brain.islandState.value).undoToken
        brain.resolveUndo(token, accepted = true)
        assertIs<IslandVisualState.Failed>(brain.islandState.value)
    }
}
