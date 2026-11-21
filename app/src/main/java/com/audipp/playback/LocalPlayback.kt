package com.audipp.playback

import com.audipp.domain.model.Availability
import com.audipp.domain.model.ErrorDomain
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.PlaybackError
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.PlaybackSnapshot
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.UserAction
import com.audipp.playback.source.AdapterKey
import com.audipp.playback.source.LocalDocumentSourceAdapter
import com.audipp.playback.source.PlaybackTarget
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.ResolutionFailure
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.SourceResolution
import com.audipp.playback.source.SourceResolver
import com.audipp.playback.source.TargetKind
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface LocalPlaybackEngine {
    fun prepare(entryId: QueueEntryId, transitionId: TransitionId, document: SourceDescriptor.LocalDocument)
    fun reset()
}

internal enum class LocalEnginePhase { BUFFERING, READY, ENDED }

// The service's application looper serializes commands and engine events, including Stop during IO.
internal class LocalPlayback(
    private val engine: LocalPlaybackEngine,
    access: suspend (String) -> Availability,
    timeoutMs: Long = 10_000,
) {
    private var context = ResolutionContext(
        PlaybackTarget("android-standard", TargetKind.DEVICE), 0, newTransition(),
    )
    private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot(0, PlaybackPhase.IDLE))
    val snapshot: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()
    private val resolver = SourceResolver(
        mapOf(AdapterKey(SourceKind.LOCAL, TargetKind.DEVICE) to LocalDocumentSourceAdapter(
            PlaybackCapabilities(canPlay = true, canPause = true, canReadPosition = true), access,
        )), { context }, timeoutMs = timeoutMs,
    )

    suspend fun start(entryId: QueueEntryId, variant: TrackVariant): PlaybackError? {
        stop()
        val transitionId = context.transitionId
        publish(PlaybackSnapshot(0, PlaybackPhase.RESOLVING, currentEntryId = entryId))
        try {
            repeat(2) {
                val attempt = context
                val result = resolver.resolve(ResolutionRequest(variant, attempt))
                val rejection = when (result) {
                    is SourceResolution.Unavailable -> result.reason
                    is SourceResolution.Resolved -> resolver.rejectionFor(result)
                }
                currentCoroutineContext().ensureActive()
                if (context.transitionId != transitionId) throw CancellationException("Superseded playback")
                if (context.routeRevision != attempt.routeRevision) return@repeat
                rejection?.let { return fail(it.toPlaybackError()) }
                result as SourceResolution.Resolved.Playback
                val descriptor = result.descriptor as? SourceDescriptor.LocalDocument
                    ?: return fail(ResolutionFailure.INVALID_DESCRIPTOR.toPlaybackError())
                // The grant may have been revoked after resolution; the engine also opens under OS checks.
                publish(snapshot.value.copy(phase = PlaybackPhase.BUFFERING,
                    effectiveSource = result.plan.source, executionPlan = result.plan))
                engine.prepare(entryId, attempt.transitionId, descriptor)
                return null
            }
            return fail(playbackError(ErrorDomain.AUDIO_ROUTE, "ROUTE_CHANGED_DURING_RESOLUTION",
                true, UserAction.RETRY))
        } catch (cancelled: CancellationException) {
            if (context.transitionId == transitionId) stop()
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (context.transitionId != transitionId) throw CancellationException("Superseded playback")
            return fail(when (failure) {
                is SecurityException -> ResolutionFailure.PERMISSION_REQUIRED.toPlaybackError()
                is IOException -> ResolutionFailure.SOURCE_IO.toPlaybackError()
                else -> playbackError(ErrorDomain.PLAYBACK_ENGINE, "LOCAL_ENGINE_FAILURE", true,
                    UserAction.RETRY)
            })
        }
    }

    fun stop() {
        context = context.copy(transitionId = newTransition())
        publish(PlaybackSnapshot(0, PlaybackPhase.IDLE))
        engine.reset()
    }

    fun fail(error: PlaybackError): PlaybackError {
        context = context.copy(transitionId = newTransition())
        publish(snapshot.value.copy(phase = PlaybackPhase.ERROR, executionPlan = null,
            effectiveSource = null, positionMs = null, durationMs = null, error = error))
        engine.reset()
        return error
    }

    fun onRouteRevisionChanged(revision: Long) {
        if (revision <= context.routeRevision) return
        context = context.copy(routeRevision = revision)
        val previous = snapshot.value
        if (previous.executionPlan != null) publish(previous.copy(executionPlan = null))
    }

    // A route change retires the plan while the prepared media item keeps playing under this transition.
    fun isCurrentTransition(transitionId: TransitionId?): Boolean =
        transitionId == context.transitionId && snapshot.value.effectiveSource != null

    fun onEngineState(
        transitionId: TransitionId,
        enginePhase: LocalEnginePhase,
        playRequested: Boolean,
        isPlaying: Boolean,
        positionMs: Long?,
        durationMs: Long?,
        canSeek: Boolean,
    ) {
        if (!isCurrentTransition(transitionId)) return
        val previous = snapshot.value
        val plan = previous.executionPlan
        val phase = when (enginePhase) {
            LocalEnginePhase.BUFFERING -> if (playRequested) PlaybackPhase.BUFFERING else PlaybackPhase.PAUSED
            LocalEnginePhase.READY -> if (isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED
            LocalEnginePhase.ENDED -> PlaybackPhase.ADVANCING
        }
        val duration = durationMs?.takeIf { it >= 0 }
        val position = positionMs?.takeIf { it >= 0 }?.let {
            if (duration == null) it else it.coerceAtMost(duration)
        }
        publish(previous.copy(phase = phase, positionMs = position, durationMs = duration,
            executionPlan = plan?.copy(capabilities = plan.capabilities.copy(canSeek = canSeek && duration != null))))
    }

    private fun publish(value: PlaybackSnapshot) {
        val previous = snapshot.value
        if (value.copy(revision = previous.revision) != previous) {
            mutableSnapshot.value = value.copy(revision = previous.revision + 1)
        }
    }
}

internal fun playbackError(domain: ErrorDomain, code: String, retryable: Boolean,
    action: UserAction? = null) = PlaybackError(domain, code, retryable, action, UUID.randomUUID().toString())

private fun newTransition() = TransitionId(UUID.randomUUID().toString())

internal fun ResolutionFailure.toPlaybackError(): PlaybackError = when (this) {
    ResolutionFailure.PERMISSION_REQUIRED -> playbackError(ErrorDomain.PERMISSION_DENIED,
        name, true, UserAction.REQUEST_PERMISSION)
    ResolutionFailure.PLAYBACK_UNSUPPORTED, ResolutionFailure.INVALID_DESCRIPTOR,
    ResolutionFailure.ADAPTER_UNAVAILABLE -> playbackError(ErrorDomain.FORMAT_UNSUPPORTED,
        name, false, UserAction.RESELECT_SOURCE)
    ResolutionFailure.SOURCE_IO, ResolutionFailure.SOURCE_MISSING,
    ResolutionFailure.SOURCE_UNAVAILABLE, ResolutionFailure.TIMEOUT ->
        playbackError(ErrorDomain.SOURCE_IO, name, true, UserAction.RESELECT_SOURCE)
    else -> playbackError(ErrorDomain.PLAYBACK_ENGINE, name, false)
}

// Keep document URIs out of IPC even when extras are addressed only to app controllers.
internal fun PlaybackSnapshot.withoutDocumentUri(): PlaybackSnapshot {
    val source = effectiveSource?.copy(localDocumentUri = null)
    return copy(effectiveSource = source, executionPlan = executionPlan?.copy(source = source!!))
}
