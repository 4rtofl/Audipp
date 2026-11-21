package com.audipp.playback

import com.audipp.domain.model.Availability
import com.audipp.domain.model.ExecutionMode
import com.audipp.domain.model.ExecutionPlan
import com.audipp.domain.model.PermissionState
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.VariantId
import com.audipp.playback.source.PlaybackPermissionChecker
import com.audipp.playback.source.PlaybackTarget
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.SourceResolution
import com.audipp.playback.source.SourceResolver
import com.audipp.playback.source.TargetKind
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOwnerCoordinatorTest {
    @Test
    fun localProviderAndRendererWaitForObservedStopAndPlaying() = fixture {
        start("local", TargetKind.DEVICE)
        val provider = submit("provider", TargetKind.PROVIDER_REMOTE)
        assertEquals(PlaybackOwnerPhase.STOP_PENDING, state.phase)
        assertEquals(listOf("local"), starts)
        coordinator.onStopped(lastStop)
        await(PlaybackOwnerPhase.START_PENDING)
        assertEquals(provider.selection, state.owner)
        coordinator.onPlaying(provider.selection.transitionId)
        assertEquals(PlaybackOwnerPhase.ACTIVE, state.phase)

        submit("renderer", TargetKind.RENDERER)
        assertEquals(listOf("local", "provider"), starts)
        coordinator.onStopped(lastStop)
        await(PlaybackOwnerPhase.START_PENDING)
        assertEquals(listOf("local", "provider", "renderer"), starts)
    }

    @Test
    fun lateStartInSameSdkRequiresNewStopObservation() = fixture {
        val old = submit("old", TargetKind.PROVIDER_REMOTE)
        await(PlaybackOwnerPhase.START_PENDING)
        submit("new", TargetKind.PROVIDER_REMOTE)
        val firstStop = lastStop
        assertTrue(effects.any { it is PlaybackOwnerEffect.CancelStart && it.selection == old.selection })

        coordinator.onStopped(firstStop)
        assertEquals(listOf("old"), starts)
        coordinator.onPlaying(old.selection.transitionId)
        val secondStop = lastStop
        assertEquals(firstStop.copy(attempt = firstStop.attempt + 1), secondStop)
        assertEquals(PlaybackOwnerPhase.STOP_PENDING, state.phase)
        coordinator.onStopped(firstStop)
        assertEquals(listOf("old"), starts)
        coordinator.onStopped(secondStop)
        await(PlaybackOwnerPhase.START_PENDING)
        assertEquals(listOf("old", "new"), starts)
    }

    @Test
    fun invalidationDropsWaiterAndLateSuccessDoesNotRestoreState() = fixture {
        val old = submit("old", TargetKind.DEVICE)
        await(PlaybackOwnerPhase.START_PENDING)
        submit("queued", TargetKind.RENDERER)
        coordinator.invalidate()
        assertNull(state.pending)
        val staleStop = lastStop
        coordinator.onPlaying(old.selection.transitionId)
        assertEquals(PlaybackOwnerPhase.STOP_PENDING, state.phase)
        assertTrue(lastStop != staleStop)
        coordinator.onStopped(lastStop)
        assertEquals(PlaybackOwnerPhase.IDLE, state.phase)
        assertEquals(listOf("old"), starts)
    }

    @Test
    fun confirmationOfNoStartAndObservedStopCanArriveInEitherOrder() = fixture {
        val first = submit("first", TargetKind.PROVIDER_REMOTE)
        await(PlaybackOwnerPhase.START_PENDING)
        val second = submit("second", TargetKind.PROVIDER_REMOTE)
        coordinator.onStopped(lastStop)
        assertEquals(listOf("first"), starts)
        coordinator.onStartAborted(first.selection.transitionId)
        await(PlaybackOwnerPhase.START_PENDING)

        submit("third", TargetKind.PROVIDER_REMOTE)
        coordinator.onStartAborted(second.selection.transitionId)
        assertEquals(listOf("first", "second"), starts)
        coordinator.onStopped(lastStop)
        await(PlaybackOwnerPhase.START_PENDING)
        assertEquals(listOf("first", "second", "third"), starts)
    }

    @Test
    fun sameSdkWaitsForStopAndCancelCommandsToFinish() = fixture {
        val first = submit("first", TargetKind.PROVIDER_REMOTE)
        await(PlaybackOwnerPhase.START_PENDING)
        submit("second", TargetKind.PROVIDER_REMOTE)
        val sdk = FakeSdk(coordinator, lastStop)
        sdk.acceptCommands()
        assertEquals(PlaybackRetirement(false, false, false), state.retirement)
        sdk.observeStopped()
        coordinator.onStartAborted(first.selection.transitionId)
        sdk.completeCancel()
        assertEquals(PlaybackRetirement(false, true, false), state.retirement)
        assertEquals(listOf("first"), starts)

        sdk.completeStop()
        await(PlaybackOwnerPhase.START_PENDING)
        assertEquals(listOf("first", "second"), starts)
    }

    @Test
    fun missingStartResponseRequiresIndependentInvalidationAfterTimeout() = fixture(timeoutMs = 100) {
        val first = submit("first", TargetKind.PROVIDER_REMOTE)
        await(PlaybackOwnerPhase.START_PENDING)
        await(PlaybackOwnerPhase.STOP_PENDING)
        val stop = lastStop
        submit("second", TargetKind.PROVIDER_REMOTE)
        coordinator.onStopped(stop)
        await(PlaybackOwnerPhase.STOP_UNCONFIRMED)
        assertNull(state.pending)
        assertEquals(PlaybackRetirement(true, false, true), state.retirement)
        coordinator.confirmManualStop(first.selection.transitionId)
        assertEquals(first.selection, state.owner)
        assertEquals(listOf("first"), starts)
        // The original response is lost. Independent invalidation supplies the terminal guarantee.
        coordinator.onStartAborted(first.selection.transitionId)
        await(PlaybackOwnerPhase.IDLE)
        assertEquals(listOf("first"), starts)
        start("retry", TargetKind.PROVIDER_REMOTE)
        assertEquals(listOf("first", "retry"), starts)
    }

    @Test
    fun lateStopDoesNotReviveExpiredHandoff() = fixture(timeoutMs = 100) {
        start("first", TargetKind.DEVICE)
        val handoff = submit("external", TargetKind.EXTERNAL_APP)
        await(PlaybackOwnerPhase.STOP_UNCONFIRMED)
        assertEquals(PlaybackRetirement(false, true, false), state.retirement)
        assertNull(state.pending)
        coordinator.onStopped(lastStop)
        assertEquals(PlaybackOwnerPhase.IDLE, state.phase)
        coordinator.confirmHandoff(handoff.selection.transitionId)
        assertTrue(effects.none { it is PlaybackOwnerEffect.OpenHandoff })
    }

    @Test
    fun manualContinuationKeepsUnverifiedStopVisible() = fixture {
        val first = start("first", TargetKind.PROVIDER_REMOTE)
        submit("second", TargetKind.RENDERER)
        coordinator.onStopUnconfirmed(lastStop)
        assertEquals(PlaybackOwnerPhase.STOP_UNCONFIRMED, state.phase)
        coordinator.confirmManualStop(first.selection.transitionId)
        assertEquals(PlaybackRetirement(false, true, false), state.retirement)
        assertEquals(first.selection, state.owner)
        assertEquals(listOf("first"), starts)
        coordinator.onStopCommandsSettled(lastStop)
        coordinator.confirmManualStop(first.selection.transitionId)
        await(PlaybackOwnerPhase.START_PENDING)
        assertEquals(first.selection.transitionId, state.lastUnverifiedStop)
    }

    @Test
    fun currentQueueRevisionAndProviderPermissionAreRecheckedBeforeStart() = fixture {
        val first = start("first", TargetKind.DEVICE)
        submit("revoked", TargetKind.PROVIDER_REMOTE)
        permission = PermissionState.DENIED
        coordinator.onStopped(lastStop)
        await(PlaybackOwnerPhase.IDLE)
        assertEquals(listOf(first.selection.transitionId.value), starts)

        permission = PermissionState.ALLOWED
        queueRevision++
        submit("stale", TargetKind.RENDERER)
        await(PlaybackOwnerPhase.IDLE)
        assertEquals(listOf("first"), starts)
    }

    @Test
    fun handoffRequiresExplicitActionAndRemainsUnobserved() = fixture {
        val handoff = submit("external", TargetKind.EXTERNAL_APP)
        await(PlaybackOwnerPhase.HANDOFF_REQUIRED)
        assertTrue(effects.isEmpty())
        coordinator.confirmHandoff(handoff.selection.transitionId)
        await(PlaybackOwnerPhase.HANDOFF_DELEGATED)
        assertTrue(effects.single() is PlaybackOwnerEffect.OpenHandoff)
        coordinator.onPlaying(handoff.selection.transitionId)
        assertEquals(PlaybackOwnerPhase.HANDOFF_DELEGATED, state.phase)
        submit("local", TargetKind.DEVICE)
        assertEquals(PlaybackOwnerPhase.STOP_UNCONFIRMED, state.phase)
        assertTrue(starts.isEmpty())
        coordinator.confirmManualStop(handoff.selection.transitionId)
        await(PlaybackOwnerPhase.START_PENDING)
        assertEquals(listOf("local"), starts)
    }

    @Test
    fun changedRouteFencesPendingStartBeforeDispatch() = fixture {
        start("first", TargetKind.DEVICE)
        submit("renderer", TargetKind.RENDERER)
        currentContext = currentContext.copy(routeRevision = 2)
        coordinator.onStopped(lastStop)
        await(PlaybackOwnerPhase.IDLE)
        assertEquals(listOf("first"), starts)
    }

    @Test
    fun routeChangeWhileReadingQueuePreventsStart() = fixture {
        start("first", TargetKind.DEVICE)
        submit("renderer", TargetKind.RENDERER)
        val read = suspendQueueRead()
        coordinator.onStopped(lastStop)
        read.entered.await()
        currentContext = currentContext.copy(routeRevision = 2)
        read.resume.complete(Unit)
        awaitCheckFinished()
        assertEquals(PlaybackOwnerPhase.IDLE, state.phase)
        assertEquals(listOf("first"), starts)
    }

    @Test
    fun permissionRevocationWhileReadingQueuePreventsStart() = fixture {
        start("first", TargetKind.DEVICE)
        submit("provider", TargetKind.PROVIDER_REMOTE)
        val read = suspendQueueRead()
        coordinator.onStopped(lastStop)
        read.entered.await()
        permission = PermissionState.DENIED
        read.resume.complete(Unit)
        awaitCheckFinished()
        assertEquals(PlaybackOwnerPhase.IDLE, state.phase)
        assertEquals(listOf("first"), starts)
    }

    @Test
    fun queueChangeWhileReadingQueuePreventsStartEvenWithMatchingReadResult() = fixture {
        start("first", TargetKind.DEVICE)
        submit("stale", TargetKind.DEVICE)
        val read = suspendQueueRead()
        coordinator.onStopped(lastStop)
        read.entered.await()
        queueRevision++
        read.resume.complete(Unit)
        awaitCheckFinished()
        assertEquals(PlaybackOwnerPhase.IDLE, state.phase)
        assertEquals(listOf("first"), starts)
    }

    @Test
    fun queueReadFailureEndsCheckAndAllowsRetry() = fixture {
        start("first", TargetKind.DEVICE)
        submit("failed", TargetKind.DEVICE)
        readRevision = { Result.failure(IOException()) }
        coordinator.onStopped(lastStop)
        awaitCheckFinished()
        assertEquals(PlaybackOwnerPhase.IDLE, state.phase)
        assertNull(state.pending)
        assertEquals(listOf("first"), starts)

        readRevision = { Result.success(queueRevision) }
        start("retry", TargetKind.DEVICE)
        assertEquals(listOf("first", "retry"), starts)
    }

    @Test
    fun queueReadCancellationPropagatesAndClearsCheck() = fixture {
        val readJob = CompletableDeferred<Job>()
        readRevision = {
            readJob.complete(kotlin.coroutines.coroutineContext[Job]!!)
            Result.failure(CancellationException())
        }
        submit("cancelled", TargetKind.DEVICE)
        awaitCheckFinished()
        assertTrue(readJob.await().isCancelled)
        assertEquals(PlaybackOwnerPhase.IDLE, state.phase)
        assertTrue(starts.isEmpty())
    }

    private class FakeSdk(
        private val coordinator: PlaybackOwnerCoordinator,
        private val token: PlaybackStopToken,
    ) {
        private var commandsAccepted = false
        private var stopped = false
        private var cancelCompleted = false
        private var stopCompleted = false

        fun acceptCommands() {
            commandsAccepted = true
        }

        fun observeStopped() {
            stopped = true
            notifyCoordinator()
        }

        fun completeCancel() {
            check(commandsAccepted)
            cancelCompleted = true
            notifyCoordinator()
        }

        fun completeStop() {
            check(commandsAccepted)
            stopCompleted = true
            notifyCoordinator()
        }

        private fun notifyCoordinator() {
            if (cancelCompleted && stopCompleted) {
                if (stopped) coordinator.onStopped(token)
                else coordinator.onStopCommandsSettled(token)
            }
        }
    }

    private fun fixture(timeoutMs: Long = 5_000, test: suspend Fixture.() -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            Fixture(scope, timeoutMs).test()
        } finally {
            scope.cancel()
        }
    }

    private class Fixture(scope: CoroutineScope, timeoutMs: Long) {
        lateinit var currentContext: ResolutionContext
        var permission = PermissionState.ALLOWED
        var queueRevision = 1L
        var readRevision: suspend () -> Result<Long?> = { Result.success(queueRevision) }
        val effects = mutableListOf<PlaybackOwnerEffect>()
        private val resolver = SourceResolver(
            emptyMap(), { currentContext }, PlaybackPermissionChecker { _, _ -> permission },
        )
        val coordinator = PlaybackOwnerCoordinator(
            scope, timeoutMs, resolver, { readRevision() }, { queueRevision }, { _, _ -> permission },
        ) { effects += it }
        val state get() = coordinator.state.value
        val starts get() = effects.filterIsInstance<PlaybackOwnerEffect.Start>().map { it.request.selection.transitionId.value }
        val lastStop get() = effects.filterIsInstance<PlaybackOwnerEffect.Stop>().last().token

        class QueueRead {
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
        }

        fun suspendQueueRead() = QueueRead().also { read ->
            readRevision = {
                val revision = queueRevision
                read.entered.complete(Unit)
                read.resume.await()
                Result.success(revision)
            }
        }

        suspend fun await(phase: PlaybackOwnerPhase) {
            withTimeout(5_000) { coordinator.state.first { it.phase == phase } }
        }

        suspend fun awaitCheckFinished() {
            withTimeout(5_000) { coordinator.state.first { it.phase != PlaybackOwnerPhase.CHECKING } }
        }

        suspend fun start(id: String, target: TargetKind): PlaybackOwnerRequest {
            val request = submit(id, target)
            await(PlaybackOwnerPhase.START_PENDING)
            coordinator.onPlaying(request.selection.transitionId)
            return request
        }

        fun submit(id: String, target: TargetKind): PlaybackOwnerRequest {
            val request = request(id, target)
            currentContext = request.source.request.context
            coordinator.submit(request)
            return request
        }

        private fun request(id: String, target: TargetKind): PlaybackOwnerRequest {
            val transition = TransitionId(id)
            val context = ResolutionContext(PlaybackTarget(target.name, target), 1, transition)
            val provider = target == TargetKind.PROVIDER_REMOTE || target == TargetKind.EXTERNAL_APP
            val source = if (provider) {
                SourceRef(SourceId(id), SourceKind.PROVIDER, "provider", id, availability = Availability.PLAYABLE)
            } else {
                SourceRef(SourceId(id), SourceKind.LOCAL, localDocumentUri = "content://audio/$id",
                    availability = Availability.PLAYABLE)
            }
            val variant = TrackVariant(VariantId(id), TrackId(id), source)
            val mode = when (target) {
                TargetKind.DEVICE -> ExecutionMode.LOCAL_DIRECT
                TargetKind.RENDERER -> ExecutionMode.NETWORK_REMOTE
                TargetKind.PROVIDER_REMOTE -> ExecutionMode.PARTNER_REMOTE
                TargetKind.EXTERNAL_APP -> ExecutionMode.EXTERNAL_HANDOFF
            }
            val descriptor = when (target) {
                TargetKind.DEVICE -> SourceDescriptor.LocalDocument(checkNotNull(source.localDocumentUri))
                TargetKind.RENDERER -> SourceDescriptor.RendererResource("https://media.test/$id", target.name, 1, true)
                TargetKind.PROVIDER_REMOTE -> SourceDescriptor.ControlSession(id)
                TargetKind.EXTERNAL_APP -> SourceDescriptor.Handoff("player://audio/$id")
            }
            val plan = ExecutionPlan(variant.variantId, source, target.name, mode,
                PlaybackCapabilities(canPlay = true), context.routeRevision, transition)
            val request = ResolutionRequest(variant, context)
            val resolved = if (provider) SourceResolution.Resolved.Auxiliary(request, plan, descriptor)
                else SourceResolution.Resolved.Playback(request, plan, descriptor)
            return PlaybackOwnerRequest("queue", QueueEntryId(id), 1, resolved)
        }
    }
}
