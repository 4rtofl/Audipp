package com.audipp.playback

import com.audipp.domain.model.ExecutionMode
import com.audipp.domain.model.PermissionState
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.TransitionId
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.ResolutionUse
import com.audipp.playback.source.SourceResolution
import com.audipp.playback.source.SourceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class PlaybackOwnerSelection(
    val queueId: String,
    val entryId: QueueEntryId,
    val queueRevision: Long,
    val transitionId: TransitionId,
    val targetId: String,
    val mode: ExecutionMode,
)

class PlaybackOwnerRequest(
    queueId: String,
    entryId: QueueEntryId,
    queueRevision: Long,
    val source: SourceResolution.Resolved,
) {
    init { require(queueId.isNotBlank() && queueRevision >= 0) }

    val selection = PlaybackOwnerSelection(
        queueId, entryId, queueRevision, source.plan.transitionId, source.plan.targetId, source.plan.mode,
    )
}

enum class PlaybackOwnerPhase {
    IDLE, CHECKING, START_PENDING, ACTIVE, STOP_PENDING, STOP_UNCONFIRMED, HANDOFF_REQUIRED, HANDOFF_DELEGATED,
}

data class PlaybackOwnership(
    val phase: PlaybackOwnerPhase = PlaybackOwnerPhase.IDLE,
    val owner: PlaybackOwnerSelection? = null,
    val pending: PlaybackOwnerSelection? = null,
    val lastUnverifiedStop: TransitionId? = null,
    val retirement: PlaybackRetirement? = null,
)

data class PlaybackRetirement(
    val stopObserved: Boolean,
    val startSettled: Boolean,
    val stopCommandsSettled: Boolean,
)

data class PlaybackStopToken(val transitionId: TransitionId, val attempt: Int)

sealed interface PlaybackOwnerEffect {
    data class Start(val request: PlaybackOwnerRequest) : PlaybackOwnerEffect
    data class CancelStart(val selection: PlaybackOwnerSelection) : PlaybackOwnerEffect
    data class Stop(val selection: PlaybackOwnerSelection, val token: PlaybackStopToken) : PlaybackOwnerEffect
    data class OpenHandoff(val request: PlaybackOwnerRequest) : PlaybackOwnerEffect
}

/**
 * Call on the playback owner's serial looper and use a scope on that same looper for deadlines.
 * Effects dispatch commands or checks; their callbacks must be posted back to the looper.
 * Current revision and permission are non-blocking reads of state updated on that looper.
 * Queue reads return expected failures as Result.failure and propagate cancellation.
 */
class PlaybackOwnerCoordinator(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val resolver: SourceResolver,
    private val readQueueRevision: suspend (String) -> Result<Long?>,
    private val currentQueueRevision: (String) -> Long?,
    private val currentPermission: (ResolutionRequest, ResolutionUse) -> PermissionState,
    private val emit: (PlaybackOwnerEffect) -> Unit,
) {
    init { require(timeoutMs > 0) }

    private class Owner(val selection: PlaybackOwnerSelection) {
        val handoff = selection.mode == ExecutionMode.EXTERNAL_HANDOFF
        var startSettled = handoff
        var playingObserved = false
        var stopToken: PlaybackStopToken? = null
        var stopObserved = false
        var stopCommandsSettled = handoff
        var stopUnconfirmed = false
        var retiring = false
    }

    private var owner: Owner? = null
    private var pending: PlaybackOwnerRequest? = null
    private var checking: Job? = null
    private var handoffApproved = false
    private var transitionDeadline: Job? = null
    private var stopDeadline: Job? = null
    private var lastUnverifiedStop: TransitionId? = null
    private val mutableState = MutableStateFlow(PlaybackOwnership())
    val state = mutableState.asStateFlow()

    fun submit(request: PlaybackOwnerRequest) {
        clearPending()
        pending = request
        advanceOrRetire()
    }

    /** Stop, deletion of the relevant item, and route changes discard the requested start. */
    fun invalidate() {
        clearPending()
        advanceOrRetire()
    }

    private fun onStartChecked(request: PlaybackOwnerRequest, valid: Boolean) {
        checking = null
        if (!valid) {
            clearPending()
            publish()
            return
        }

        val next = Owner(request.selection)
        if (next.handoff && !handoffApproved) {
            publish()
            return
        }
        clearPending()
        owner = next
        publish()
        if (next.handoff) {
            emit(PlaybackOwnerEffect.OpenHandoff(request))
        } else {
            transitionDeadline = deadline {
                if (owner === next && !next.startSettled && !next.retiring) retire(next)
            }
            emit(PlaybackOwnerEffect.Start(request))
        }
    }

    fun confirmHandoff(transitionId: TransitionId) {
        val request = pending ?: return
        if (request.selection.transitionId != transitionId ||
            state.value.phase != PlaybackOwnerPhase.HANDOFF_REQUIRED
        ) return
        handoffApproved = true
        checkStart(request)
    }

    /** Report observed playback, never the SDK's acceptance of a start request. */
    fun onPlaying(transitionId: TransitionId) {
        val current = owner?.takeIf { it.selection.transitionId == transitionId } ?: return
        if (current.handoff || current.playingObserved) return
        current.playingObserved = true
        current.startSettled = true
        transitionDeadline?.cancel()
        if (current.retiring) requestStop(current) else publish()
    }

    /** A terminal response or independent invalidation proving the request leaves no playback and cannot start it. */
    fun onStartAborted(transitionId: TransitionId) {
        val current = owner?.takeIf { it.selection.transitionId == transitionId } ?: return
        if (current.startSettled) return
        current.startSettled = true
        transitionDeadline?.cancel()
        if (!current.retiring || current.stopObserved) releaseOwner() else publish()
    }

    /** Report a correlated non-playing observation once all old stop/cancel commands are safe for later playback. */
    fun onStopped(token: PlaybackStopToken) {
        val current = owner?.takeIf { it.stopToken == token } ?: return
        current.stopObserved = true
        current.stopCommandsSettled = true
        if (current.startSettled) releaseOwner() else publish()
    }

    /** All old stop/cancel commands can no longer affect later playback, even if stop is unobserved. */
    fun onStopCommandsSettled(token: PlaybackStopToken) {
        val current = owner?.takeIf { it.stopToken == token } ?: return
        current.stopCommandsSettled = true
        publish()
    }

    fun onStopUnconfirmed(token: PlaybackStopToken) {
        val current = owner?.takeIf { it.stopToken == token } ?: return
        current.stopUnconfirmed = true
        publish()
    }

    fun confirmManualStop(transitionId: TransitionId) {
        val current = owner?.takeIf {
            it.selection.transitionId == transitionId && it.stopUnconfirmed &&
                it.startSettled && it.stopCommandsSettled
        } ?: return
        lastUnverifiedStop = transitionId
        releaseOwner()
    }

    private fun advanceOrRetire() {
        val current = owner
        val next = pending
        when {
            current != null && !current.retiring -> retire(current)
            current == null && next != null -> checkStart(next)
            else -> publish()
        }
    }

    private fun checkStart(request: PlaybackOwnerRequest) {
        val check = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val selection = request.selection
                val readRevision = withTimeoutOrNull(timeoutMs) {
                    readQueueRevision(selection.queueId).getOrElse {
                        if (it is CancellationException) throw it
                        null
                    }
                }
                ensureActive()
                val valid = readRevision == selection.queueRevision &&
                    currentQueueRevision(selection.queueId) == selection.queueRevision &&
                    resolver.rejectionAtDispatch(request.source, currentPermission) == null
                onStartChecked(request, valid)
            } finally {
                if (checking === coroutineContext[Job]) {
                    clearPending()
                    publish()
                }
            }
        }
        checking = check
        publish()
        check.start()
    }

    private fun clearPending() {
        checking?.cancel()
        checking = null
        pending = null
        handoffApproved = false
    }

    private fun retire(current: Owner) {
        transitionDeadline?.cancel()
        current.retiring = true
        if (current.handoff) {
            current.stopUnconfirmed = true
            publish()
        } else {
            requestStop(current, cancelStart = !current.startSettled)
        }
    }

    private fun requestStop(current: Owner, cancelStart: Boolean = false) {
        stopDeadline?.cancel()
        val token = PlaybackStopToken(current.selection.transitionId, (current.stopToken?.attempt ?: 0) + 1)
        current.stopToken = token
        current.stopObserved = false
        current.stopCommandsSettled = false
        current.stopUnconfirmed = false
        stopDeadline = deadline {
            clearPending()
            onStopUnconfirmed(token)
        }
        publish()
        if (cancelStart) emit(PlaybackOwnerEffect.CancelStart(current.selection))
        emit(PlaybackOwnerEffect.Stop(current.selection, token))
    }

    private fun releaseOwner() {
        stopDeadline?.cancel()
        owner = null
        advanceOrRetire()
    }

    private fun deadline(onTimeout: () -> Unit): Job = scope.launch {
        delay(timeoutMs)
        onTimeout()
    }

    private fun publish() {
        val current = owner
        val phase = when {
            current?.stopUnconfirmed == true -> PlaybackOwnerPhase.STOP_UNCONFIRMED
            current?.retiring == true -> PlaybackOwnerPhase.STOP_PENDING
            current?.handoff == true -> PlaybackOwnerPhase.HANDOFF_DELEGATED
            current?.playingObserved == true -> PlaybackOwnerPhase.ACTIVE
            current != null -> PlaybackOwnerPhase.START_PENDING
            checking != null -> PlaybackOwnerPhase.CHECKING
            pending != null -> PlaybackOwnerPhase.HANDOFF_REQUIRED
            else -> PlaybackOwnerPhase.IDLE
        }
        val retirement = current?.takeIf { it.retiring }?.let {
            PlaybackRetirement(it.stopObserved, it.startSettled, it.stopCommandsSettled)
        }
        mutableState.value = PlaybackOwnership(phase, current?.selection, pending?.selection, lastUnverifiedStop, retirement)
    }
}
