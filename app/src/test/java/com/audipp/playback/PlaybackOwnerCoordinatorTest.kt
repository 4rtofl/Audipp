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
    fun timeoutsDoNotPermitNewStartWhileOldStartIsUnresolved() = fixture(timeoutMs = 100) {
        val first = submit("first", TargetKind.PROVIDER_REMOTE)
        await(PlaybackOwnerPhase.START_PENDING)
        await(PlaybackOwnerPhase.STOP_PENDING)
        val stop = lastStop
        submit("second", TargetKind.PROVIDER_REMOTE)
        coordinator.onStopped(stop)
        await(PlaybackOwnerPhase.STOP_UNCONFIRMED)
        coordinator.confirmManualStop(first.selection.transitionId)
        assertEquals(listOf("first"), starts)
        coordinator.onStartAborted(first.selection.transitionId)
        await(PlaybackOwnerPhase.START_PENDING)
        assertEquals(listOf("first", "second"), starts)
    }

    @Test
    fun manualContinuationKeepsUnverifiedStopVisible() = fixture {
        val first = start("first", TargetKind.PROVIDER_REMOTE)
        submit("second", TargetKind.RENDERER)
        coordinator.onStopUnconfirmed(lastStop)
        assertEquals(PlaybackOwnerPhase.STOP_UNCONFIRMED, state.phase)
        assertEquals(listOf("first"), starts)
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
        val effects = mutableListOf<PlaybackOwnerEffect>()
        private val resolver = SourceResolver(
            emptyMap(), { currentContext }, PlaybackPermissionChecker { _, _ -> permission },
        )
        val coordinator = PlaybackOwnerCoordinator(scope, timeoutMs, resolver, { queueRevision }) { effects += it }
        val state get() = coordinator.state.value
        val starts get() = effects.filterIsInstance<PlaybackOwnerEffect.Start>().map { it.request.selection.transitionId.value }
        val lastStop get() = effects.filterIsInstance<PlaybackOwnerEffect.Stop>().last().token

        suspend fun await(phase: PlaybackOwnerPhase) {
            withTimeout(5_000) { coordinator.state.first { it.phase == phase } }
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
