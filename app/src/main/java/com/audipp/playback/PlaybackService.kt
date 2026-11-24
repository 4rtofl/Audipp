package com.audipp.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audipp.MainActivity
import com.audipp.R
import com.audipp.audio.route.ObservedAudioOutputProvider
import com.audipp.audio.route.OutputRouteMonitor
import com.audipp.audio.route.OutputSafety
import com.audipp.audio.route.OutputSafetyState
import com.audipp.audio.route.ProfileApplication
import com.audipp.data.db.LibraryDao
import com.audipp.data.db.QueueDao
import com.audipp.data.db.libraryDatabase
import com.audipp.data.device.DeviceProfileStore
import com.audipp.data.preferences.providerPolicyStore
import com.audipp.domain.device.OutputBackend
import com.audipp.domain.model.ErrorDomain
import com.audipp.domain.model.ModelJson
import com.audipp.domain.model.PlaybackError
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.PlaybackQueueRef
import com.audipp.domain.model.PlaybackSnapshot
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.UserAction
import com.audipp.integrations.streaming.ProviderAccessGate
import com.audipp.playback.source.AndroidDocumentAccess
import com.audipp.playback.source.ResolutionFailure
import com.audipp.playback.source.SourceDescriptor
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var routeMonitor: OutputRouteMonitor
    internal val outputRoute get() = routeMonitor.snapshot
    private lateinit var safety: OutputSafety
    internal val outputSafety get() = safety.state
    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var playback: LocalPlayback
    private lateinit var historyJournal: PlaybackHistoryJournal
    private lateinit var audioManager: AudioManager
    private var preparation: Job? = null
    private lateinit var queues: QueueDao
    private lateinit var library: LibraryDao
    private lateinit var orchestrator: QueueOrchestrator
    private lateinit var checkpoints: PlaybackCheckpointJournal
    private var advancedFrom: TransitionId? = null
    // The sample taken just before an explicit stop, resent while the stopped queue is edited.
    private var stoppedSample: PlaybackCheckpointSample? = null
    private var seekCheckpoint = false
    private var queueWatch: Job? = null
    private var watchedQueue: String? = null
    private var playWhenPrepared = true
    private var unregisterModeListener: (() -> Unit)? = null
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateEngineSnapshot(clockChanged = true)
    }

    override fun onCreate() {
        super.onCreate()
        // Media3 decoder/provider exception logs can contain document URIs and causes.
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        audioManager = getSystemService(AudioManager::class.java)
        val selector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        safety = OutputSafety({ audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }, ::pauseForInterruption)
        routeMonitor = OutputRouteMonitor(this, safety) {
            playback.onRouteRevisionChanged(it)
            updateEngineSnapshot()
        }
        player = ExoPlayer.Builder(this)
            .setLooper(mainLooper)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory {
                ContentDataSource(this)
            })
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setAudioOutputProvider(ObservedAudioOutputProvider(this, routeMonitor))
            .build()
        historyJournal = PlaybackHistoryJournal(libraryDatabase, ProviderAccessGate(providerPolicyStore))
        playback = LocalPlayback(object : LocalPlaybackEngine {
            override fun prepare(entryId: QueueEntryId, transitionId: TransitionId,
                document: SourceDescriptor.LocalDocument, startPositionMs: Long?) {
                val item = MediaItem.Builder().setMediaId(entryId.value).setUri(document.uri)
                    .setTag(transitionId)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(getString(R.string.app_name))
                        .setIsBrowsable(false).setIsPlayable(true).build()).build()
                if (startPositionMs == null) player.setMediaItem(item) else player.setMediaItem(item, startPositionMs)
                val mode = audioManager.mode
                player.playWhenReady = playWhenPrepared && !mode.isPhoneCallMode() &&
                    mode != AudioManager.MODE_IN_COMMUNICATION && safety.canPlay()
                player.prepare()
            }

            override fun reset() {
                playWhenPrepared = true
                player.pause()
                player.stop()
                player.clearMediaItems()
            }
        }, AndroidDocumentAccess(contentResolver)::check,
            history = PlaybackHistoryRecorder(SystemClock::elapsedRealtime, write = historyJournal::enqueue))
        val database = libraryDatabase
        queues = database.queueDao()
        library = database.libraryDao()
        orchestrator = QueueOrchestrator(queues, library, playback.resolver)
        checkpoints = PlaybackCheckpointJournal(scope, queues, { checkpointSample(playback.snapshot.value) })
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (player.playWhenReady && when (player.playbackSuppressionReason) {
                    Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> true
                    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS ->
                        Build.VERSION.SDK_INT < 31 || audioManager.mode.isPhoneCallMode()
                    else -> false
                }) pauseForInterruption()
                updateEngineSnapshot()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                playback.invalidatePcm()
                val attemptEnded = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
                    reason == Player.DISCONTINUITY_REASON_REMOVE || reason == Player.DISCONTINUITY_REASON_SKIP
                playback.onHistoryDiscontinuity(
                    oldPosition.mediaItem?.localConfiguration?.tag as? TransitionId,
                    oldPosition.positionMs.takeUnless { it == C.TIME_UNSET }, attemptEnded,
                )
            }
        })
        val sessionPlayer = object : ForwardingSimpleBasePlayer(player) {
            override fun getState(): State {
                val state = super.getState()
                val error = state.playerError
                val commands = if (seekableDuration() == null) state.availableCommands.buildUpon()
                    .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).build() else state.availableCommands
                return state.buildUpon().setAvailableCommands(commands).setPlayerError(error?.let {
                    PlaybackException("LOCAL_PLAYBACK_FAILURE", null, it.errorCode)
                }).build()
            }

            override fun handleStop(): ListenableFuture<*> {
                stopPlayback()
                return Futures.immediateVoidFuture()
            }

            override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                if (!playWhenReady) {
                    if (playback.snapshot.value.phase == PlaybackPhase.RESOLVING) stopPlayback()
                    else pauseForInterruption()
                } else if (canRequestPlay() && safety.canPlay()) {
                    player.play()
                } else {
                    resumeRestored()
                }
                return Futures.immediateVoidFuture()
            }

            override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
                if (seekCommand != Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) {
                    return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                }
                val duration = seekableDuration()
                if (mediaItemIndex == player.currentMediaItemIndex && duration != null && positionMs in 0..duration) {
                    seekCheckpoint = true
                    playback.onSeekRequested()
                    player.seekTo(positionMs)
                }
                return Futures.immediateVoidFuture()
            }
        }
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, SessionCallback())
            .setSessionActivity(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).setAction(PlaybackConnection.ACTION_OPEN_NOW_PLAYING)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        val handler = Handler(player.applicationLooper)
        // Both actions are protected broadcasts sent by Android.
        ContextCompat.registerReceiver(this, clockReceiver, IntentFilter(Intent.ACTION_TIME_CHANGED).apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        if (Build.VERSION.SDK_INT >= 31) unregisterModeListener = watchCallMode(handler)
        scope.launch {
            combine(playback.snapshot, safety.state, routeMonitor.snapshot) { _, _, _ -> Unit }.collect {
                mediaSession?.let { session ->
                    session.connectedControllers.filter { it.uid == Process.myUid() }.forEach {
                        session.setSessionExtras(it, snapshotExtras())
                    }
                }
            }
        }
        scope.launch {
            var savedEntry: QueueEntryId? = null
            var previousPhase: PlaybackPhase? = null
            playback.snapshot.collect { snapshot ->
                if (snapshot.queue?.queueId != watchedQueue) watchQueue(snapshot.queue?.queueId)
                val paused = snapshot.phase == PlaybackPhase.PAUSED && previousPhase != PlaybackPhase.PAUSED
                previousPhase = snapshot.phase
                val sample = checkpointSample(snapshot)
                // Stop saves its own sample; restored and switching IDLE states are not confirmed events.
                if (sample == null || snapshot.phase == PlaybackPhase.IDLE || snapshot.positionMs == null) {
                    return@collect
                }
                if (snapshot.currentEntryId != savedEntry || paused || seekCheckpoint) {
                    savedEntry = snapshot.currentEntryId
                    seekCheckpoint = false
                    stoppedSample = null
                    checkpoints.saveConfirmed(sample)
                }
            }
        }
        scope.launch {
            checkpoints.lastWrite.collect {
                if (it?.outcome == CheckpointWriteOutcome.STALE_QUEUE &&
                    it.checkpoint.queueId == playback.queue?.queueId) reconcileQueue()
            }
        }
        scope.launch {
            val before = playback.snapshot.value.revision
            val restored = restorePlaybackCheckpoint(database, playback.resolver) { playback.resolutionContext }
                ?: return@launch
            // A command that arrived during the restore owns the state.
            if (playback.snapshot.value.revision != before || playback.queue != null) return@launch
            val queue = restored.queue.queue
            playback.restore(PlaybackQueueRef(queue.queueId, queue.revision), restored.entry?.entryId,
                restored.positionMs)
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (player.isPlaying) {
                    routeMonitor.refreshRoute()
                    updateEngineSnapshot()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        if (controllerInfo.uid == Process.myUid() || controllerInfo.isTrusted) mediaSession else null

    override fun onDestroy() {
        if (::historyJournal.isInitialized) {
            unregisterReceiver(clockReceiver)
            stopPlayback(saveStop = false)
            historyJournal.close()
        }
        if (::routeMonitor.isInitialized) routeMonitor.close()
        scope.cancel()
        unregisterModeListener?.invoke()
        mediaSession?.release()
        mediaSession = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun updateEngineSnapshot(clockChanged: Boolean = false) {
        val transition = currentTransition() ?: return
        if (!playback.isCurrentTransition(transition)) return
        player.playerError?.let {
            playback.onHistoryDiscontinuity(transition, player.currentPosition.takeUnless { it == C.TIME_UNSET },
                attemptEnded = false)
            playback.fail(it.toCommonError())
            return
        }
        val phase = when (player.playbackState) {
            Player.STATE_BUFFERING -> LocalEnginePhase.BUFFERING
            Player.STATE_READY -> LocalEnginePhase.READY
            Player.STATE_ENDED -> LocalEnginePhase.ENDED
            else -> return
        }
        playback.onEngineState(transition, phase, player.playWhenReady, player.isPlaying,
            player.currentPosition.takeUnless { it == C.TIME_UNSET },
            player.duration.takeUnless { it == C.TIME_UNSET }, seekableDuration() != null,
            player.playbackParameters.speed.toDouble(), System.currentTimeMillis(),
            routeMonitor.snapshot.value.route.routeId, clockChanged)
        if (phase == LocalEnginePhase.ENDED && advancedFrom != transition) {
            advancedFrom = transition
            advanceQueue(transition)
        }
    }

    // Each item is prepared after the previous one ends; a single PLAY_LOCAL item stays ADVANCING.
    private fun advanceQueue(transition: TransitionId) {
        val queue = playback.queue ?: return
        val entryId = playback.snapshot.value.currentEntryId ?: return
        preparation?.cancel()
        preparation = scope.launch {
            val result = orchestrator.advance(queue.queueId, entryId, QueueAdvanceTrigger.NATURAL_END,
                UnplayableItemPolicy.ASK, playback.resolutionContext)
            if (!playback.isCurrentTransition(transition)) return@launch
            when (result) {
                is QueueAdvanceResult.Ready -> {
                    val error = startQueueEntry(result.queueId, result.entry.entryId, result.queueRevision)
                    if (error != null && playback.isCurrentTransition(transition)) {
                        if (error.code == STALE_QUEUE) playback.stop() else playback.fail(error, result.entry.entryId)
                    }
                }
                is QueueAdvanceResult.RequiresUserAction ->
                    playback.fail(result.failure.reason.toPlaybackError(), result.failure.entryId)
                is QueueAdvanceResult.TimedOut ->
                    playback.fail(ResolutionFailure.TIMEOUT.toPlaybackError(), result.entryId)
                QueueAdvanceResult.Cancelled -> Unit
                QueueAdvanceResult.QueueEnded, QueueAdvanceResult.QueueChanged,
                is QueueAdvanceResult.NoPlayableItem -> playback.stop()
            }
        }
    }

    /** Rejections before the engine is touched leave the current playback as it is. */
    private suspend fun startQueueEntry(
        queueId: String,
        entryId: QueueEntryId,
        revision: Long,
        positionMs: Long? = null,
    ): PlaybackError? {
        val stored = queues.read(queueId)
        val entry = stored?.entries?.firstOrNull { it.entryId == entryId }
        if (stored?.queue?.revision != revision || entry == null) {
            return playbackError(ErrorDomain.DATABASE, STALE_QUEUE, true)
        }
        val variant = library.trackVariant(entry.variantId) ?: return ResolutionFailure.SOURCE_MISSING.toPlaybackError()
        stoppedSample = null
        return playback.start(entryId, variant, PlaybackQueueRef(queueId, revision), positionMs)
    }

    private fun resumeRestored() {
        val snapshot = playback.snapshot.value
        val queue = playback.queue ?: return
        val entryId = snapshot.currentEntryId ?: return
        if (snapshot.phase != PlaybackPhase.IDLE || audioManager.mode.isPhoneCallMode() || !safety.canPlay()) return
        preparation?.cancel()
        preparation = scope.launch {
            val error = startQueueEntry(queue.queueId, entryId, queue.revision, snapshot.positionMs)
            if (error != null && playback.snapshot.value.phase == PlaybackPhase.IDLE) playback.fail(error, entryId)
        }
    }

    private fun watchQueue(queueId: String?) {
        queueWatch?.cancel()
        watchedQueue = queueId
        if (queueId == null) return
        queueWatch = scope.launch {
            queues.observeRevision(queueId).collect { if (it != playback.queue?.revision) reconcileQueue() }
        }
    }

    /** Follows a committed edit: the current entry must remain, and checkpoints move to the new revision. */
    private fun reconcileQueue() {
        scope.launch {
            val queueId = playback.queue?.queueId ?: return@launch
            val stored = queues.read(queueId)
            if (playback.queue?.queueId != queueId) return@launch
            val entries = stored?.entries.orEmpty()
            val current = playback.snapshot.value.currentEntryId
            // The DAO checks only the saved checkpoint entry, which can lag behind the player.
            if (current != null && entries.none { it.entryId == current }) stopPlayback(saveStop = false)
            stored ?: return@launch
            playback.onQueueRevision(stored.queue.revision)
            val stopped = stoppedSample
            if (stopped == null) {
                checkpointSample(playback.snapshot.value)?.let(checkpoints::saveConfirmed)
            } else if (entries.any { it.entryId == stopped.snapshot.currentEntryId }) {
                stoppedSample = stopped.copy(queueRevision = stored.queue.revision)
                checkpoints.saveConfirmed(stoppedSample!!)
            } else {
                stoppedSample = null
            }
        }
    }

    private fun checkpointSample(snapshot: PlaybackSnapshot): PlaybackCheckpointSample? {
        val queue = snapshot.queue ?: return null
        if (snapshot.currentEntryId == null) return null
        return PlaybackCheckpointSample(queue.queueId, queue.revision, snapshot)
    }

    private fun currentTransition() = player.currentMediaItem?.localConfiguration?.tag as? TransitionId

    private fun seekableDuration(): Long? = player.duration.takeIf {
        it >= 0 && playback.isCurrentTransition(currentTransition()) &&
            player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && player.isCurrentMediaItemSeekable
    }

    private fun canRequestPlay() =
        playback.isCurrentTransition(currentTransition()) && !audioManager.mode.isPhoneCallMode()

    private fun retryOutputObservation(id: String): Boolean {
        if (!canRequestPlay() || !safety.retryObservation(id)) return false
        player.play()
        return true
    }

    internal suspend fun applyOutputProfile(profileId: String, presetId: String): ProfileApplication =
        withContext(Dispatchers.Main.immediate) {
            val revision = safety.revision
            val preset = withContext(Dispatchers.IO) {
                DeviceProfileStore.open(this@PlaybackService).use { store ->
                    store.profile(profileId)?.presets?.firstOrNull { it.id == presetId }
                }
            } ?: return@withContext ProfileApplication.UNSUPPORTED
            // The service owns only Media3 standard output; other backends cannot be installed here.
            safety.applyProfile(revision, preset) { it == OutputBackend.ANDROID_STANDARD }
        }

    private fun pauseForInterruption() {
        if (!scope.isActive) return
        if (playback.snapshot.value.phase == PlaybackPhase.RESOLVING) {
            playWhenPrepared = false
        } else if (player.playWhenReady) {
            player.pause()
        }
    }

    private fun stopPlayback(saveStop: Boolean = true) {
        updateEngineSnapshot()
        val sample = checkpointSample(playback.snapshot.value)?.takeIf { it.snapshot.phase != PlaybackPhase.IDLE }
        playback.stop()
        preparation?.cancel()
        if (saveStop && sample != null) {
            stoppedSample = sample
            checkpoints.saveConfirmed(sample)
        }
    }

    @RequiresApi(31)
    private fun watchCallMode(handler: Handler): () -> Unit {
        val listener = AudioManager.OnModeChangedListener { mode ->
            if (mode.isPhoneCallMode()) pauseForInterruption()
        }
        audioManager.addOnModeChangedListener({ task -> handler.post(task) }, listener)
        return { audioManager.removeOnModeChangedListener(listener) }
    }

    private fun snapshotExtras() = Bundle().apply {
        putString(PlaybackConnection.SNAPSHOT,
            ModelJson.encode(playback.snapshot.value.withoutDocumentUri()))
        putString(PlaybackConnection.SCHEDULE, ModelJson.encode(playback.schedule.value))
        currentTransition()?.takeIf(playback::isCurrentTransition)?.let {
            putString(PlaybackConnection.PREPARED_TRANSITION, it.value)
        }
        // Document URIs and device names stay in the service; only ids, declared format and port types leave it.
        playback.variant?.let {
            putString(PlaybackConnection.PREPARED_VARIANT, it.variantId.value)
            putString(PlaybackConnection.SOURCE_FORMAT, ModelJson.encode(it.format))
        }
        routeMonitor.snapshot.value.observedOutputs.value?.let { outputs ->
            putIntArray(PlaybackConnection.OBSERVED_OUTPUT_TYPES, outputs.map { it.type }.toIntArray())
        }
        val pending = safety.state.value as? OutputSafetyState.ConfirmationRequired
        if (pending != null) {
            putString(PlaybackConnection.OUTPUT_CONFIRMATION, ModelJson.encode(
                OutputConfirmationRequest(pending.id, pending.reason, pending.route?.type, pending.limit,
                    pending.physicalGainNote),
            ))
        }
    }

    internal inner class SessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo):
            MediaSession.ConnectionResult {
            val ownApp = controller.uid == Process.myUid()
            if (!ownApp && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val commands = SessionCommands.Builder()
                .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
                .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
            if (ownApp) {
                commands.add(PlaybackConnection.playLocalCommand)
                commands.add(PlaybackConnection.playQueueCommand)
                commands.add(PlaybackConnection.confirmOutputCommand)
                commands.add(PlaybackConnection.retryOutputObservationCommand)
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands.build())
                .setSessionExtras(if (ownApp) snapshotExtras() else Bundle.EMPTY)
                .setAvailablePlayerCommands(Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
                ).build()).build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.uid != Process.myUid()) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            val action = customCommand.customAction
            if (action == PlaybackConnection.CONFIRM_OUTPUT || action == PlaybackConnection.RETRY_OUTPUT_OBSERVATION) {
                val id = args.getString(PlaybackConnection.CONFIRMATION_ID)
                val result = when {
                    id.isNullOrBlank() -> SessionError.ERROR_BAD_VALUE
                    action == PlaybackConnection.RETRY_OUTPUT_OBSERVATION ->
                        if (retryOutputObservation(id)) SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE
                    canRequestPlay() && safety.confirm(id, args.getBoolean(PlaybackConnection.RESTORE_DIGITAL_VOLUME)) -> {
                        player.play()
                        SessionResult.RESULT_SUCCESS
                    }
                    else -> SessionError.ERROR_INVALID_STATE
                }
                return Futures.immediateFuture(SessionResult(result))
            }
            if (action == PlaybackConnection.PLAY_QUEUE) return playQueue(args)
            if (action != PlaybackConnection.PLAY_LOCAL) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            val request = try {
                val entryId = requireNotNull(args.getString(PlaybackConnection.ENTRY_ID))
                val document = requireNotNull(args.getString(PlaybackConnection.VARIANT))
                require(entryId.length <= 256 && document.length <= 16_384)
                QueueEntryId(entryId) to ModelJson.decode<TrackVariant>(document)
            } catch (_: IllegalArgumentException) {
                return Futures.immediateFuture(errorResult(playbackError(ErrorDomain.FORMAT_UNSUPPORTED,
                    "INVALID_LOCAL_REQUEST", false, UserAction.RESELECT_SOURCE), SessionError.ERROR_BAD_VALUE))
            }
            preparation?.cancel()
            stoppedSample = null
            val future = SettableFuture.create<SessionResult>()
            preparation = scope.launch {
                try {
                    updateEngineSnapshot()
                    val error = playback.start(request.first, request.second)
                    future.set(if (error == null) SessionResult(SessionResult.RESULT_SUCCESS) else errorResult(error))
                } catch (_: CancellationException) {
                    future.set(SessionResult(SessionError.INFO_CANCELLED))
                }
            }
            return future
        }

        private fun playQueue(args: Bundle): ListenableFuture<SessionResult> {
            val queueId = args.getString(PlaybackConnection.QUEUE_ID)
            val entryId = args.getString(PlaybackConnection.ENTRY_ID)
            val revision = args.getLong(PlaybackConnection.QUEUE_REVISION, -1)
            if (queueId.isNullOrBlank() || entryId.isNullOrBlank() || queueId.length > 256 || entryId.length > 256 ||
                revision < 0) {
                return Futures.immediateFuture(errorResult(playbackError(ErrorDomain.FORMAT_UNSUPPORTED,
                    "INVALID_QUEUE_REQUEST", false), SessionError.ERROR_BAD_VALUE))
            }
            preparation?.cancel()
            val future = SettableFuture.create<SessionResult>()
            preparation = scope.launch {
                try {
                    updateEngineSnapshot()
                    val error = startQueueEntry(queueId, QueueEntryId(entryId), revision)
                    future.set(when {
                        error == null -> SessionResult(SessionResult.RESULT_SUCCESS)
                        error.code == STALE_QUEUE -> errorResult(error, SessionError.ERROR_INVALID_STATE)
                        else -> errorResult(error)
                    })
                } catch (_: CancellationException) {
                    future.set(SessionResult(SessionError.INFO_CANCELLED))
                }
            }
            return future
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(MediaItem.Builder().setMediaId(ROOT)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(getString(R.string.app_name))
                    .setIsBrowsable(true).setIsPlayable(false).build()).build(), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?):
            ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.immediateFuture(
                if (parentId == ROOT) LibraryResult.ofItemList(emptyList(), params)
                else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
            )
    }

    private companion object {
        const val ROOT = "audipp-root"
        const val STALE_QUEUE = "STALE_QUEUE_REVISION"
    }
}

private fun Int.isPhoneCallMode() = this == AudioManager.MODE_RINGTONE || this == AudioManager.MODE_IN_CALL

private fun errorResult(error: PlaybackError,
    code: Int = when (error.domain) {
        ErrorDomain.PERMISSION_DENIED -> SessionError.ERROR_PERMISSION_DENIED
        ErrorDomain.FORMAT_UNSUPPORTED -> SessionError.ERROR_NOT_SUPPORTED
        ErrorDomain.SOURCE_IO -> SessionError.ERROR_IO
        else -> SessionError.ERROR_UNKNOWN
    }) = SessionResult(code, Bundle().apply {
    putString(PlaybackConnection.ERROR, ModelJson.encode(error))
})

internal fun PlaybackException.toCommonError(): PlaybackError {
    if (generateSequence<Throwable>(this) { it.cause }.take(8).any { it is SecurityException }) {
        return playbackError(ErrorDomain.PERMISSION_DENIED, "DOCUMENT_PERMISSION_DENIED", true,
            UserAction.REQUEST_PERMISSION)
    }
    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> playbackError(ErrorDomain.PERMISSION_DENIED,
            "DOCUMENT_PERMISSION_DENIED", true, UserAction.REQUEST_PERMISSION)
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> playbackError(ErrorDomain.FORMAT_UNSUPPORTED,
            "LOCAL_FORMAT_UNSUPPORTED", false, UserAction.RESELECT_SOURCE)
        in 2000..2999 -> playbackError(ErrorDomain.SOURCE_IO,
            "LOCAL_SOURCE_IO", true, UserAction.RESELECT_SOURCE)
        else -> playbackError(ErrorDomain.PLAYBACK_ENGINE, "LOCAL_ENGINE_FAILURE", true, UserAction.RETRY)
    }
}
