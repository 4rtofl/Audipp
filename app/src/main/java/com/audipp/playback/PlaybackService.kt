package com.audipp.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Process
import androidx.annotation.RequiresApi
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
import com.audipp.domain.model.ErrorDomain
import com.audipp.domain.model.ModelJson
import com.audipp.domain.model.PlaybackError
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.UserAction
import com.audipp.playback.source.AndroidDocumentAccess
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var playback: LocalPlayback
    private lateinit var audioManager: AudioManager
    private var preparation: Job? = null
    private var playWhenPrepared = true
    private var unregisterModeListener: (() -> Unit)? = null
    private val outputCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.isExternalOutput() }) pauseForInterruption()
        }
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
            .build()
        playback = LocalPlayback(object : LocalPlaybackEngine {
            override fun prepare(entryId: QueueEntryId, transitionId: TransitionId,
                document: SourceDescriptor.LocalDocument) {
                player.setMediaItem(MediaItem.Builder().setMediaId(entryId.value).setUri(document.uri)
                    .setTag(transitionId)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(getString(R.string.app_name))
                        .setIsBrowsable(false).setIsPlayable(true).build()).build())
                val mode = audioManager.mode
                player.playWhenReady = playWhenPrepared && !mode.isPhoneCallMode() &&
                    mode != AudioManager.MODE_IN_COMMUNICATION
                player.prepare()
            }

            override fun reset() {
                playWhenPrepared = true
                player.pause()
                player.stop()
                player.clearMediaItems()
            }
        }, AndroidDocumentAccess(contentResolver)::check)
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
                } else if (playback.isCurrentTransition(currentTransition()) && !audioManager.mode.isPhoneCallMode()) {
                    player.play()
                }
                return Futures.immediateVoidFuture()
            }

            override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
                if (seekCommand != Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) {
                    return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                }
                val duration = seekableDuration()
                if (mediaItemIndex == player.currentMediaItemIndex && duration != null && positionMs in 0..duration) {
                    playback.onSeekRequested()
                    player.seekTo(positionMs)
                }
                return Futures.immediateVoidFuture()
            }
        }
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, SessionCallback())
            .setSessionActivity(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        val handler = Handler(player.applicationLooper)
        audioManager.registerAudioDeviceCallback(outputCallback, handler)
        if (Build.VERSION.SDK_INT >= 31) unregisterModeListener = watchCallMode(handler)
        scope.launch {
            playback.snapshot.collect {
                mediaSession?.let { session ->
                    session.connectedControllers.filter { it.uid == Process.myUid() }.forEach {
                        session.setSessionExtras(it, snapshotExtras())
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (player.isPlaying) updateEngineSnapshot()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        if (controllerInfo.uid == Process.myUid() || controllerInfo.isTrusted) mediaSession else null

    override fun onDestroy() {
        scope.cancel()
        unregisterModeListener?.invoke()
        if (::audioManager.isInitialized) audioManager.unregisterAudioDeviceCallback(outputCallback)
        mediaSession?.release()
        mediaSession = null
        if (::playback.isInitialized) playback.stop()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun updateEngineSnapshot() {
        val transition = currentTransition() ?: return
        if (!playback.isCurrentTransition(transition)) return
        player.playerError?.let {
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
            player.playbackParameters.speed.toDouble(), System.currentTimeMillis())
    }

    private fun currentTransition() = player.currentMediaItem?.localConfiguration?.tag as? TransitionId

    private fun seekableDuration(): Long? = player.duration.takeIf {
        it >= 0 && playback.isCurrentTransition(currentTransition()) &&
            player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && player.isCurrentMediaItemSeekable
    }

    private fun pauseForInterruption() {
        if (!scope.isActive) return
        if (playback.snapshot.value.phase == PlaybackPhase.RESOLVING) {
            playWhenPrepared = false
        } else if (player.playWhenReady) {
            player.pause()
        }
    }

    private fun stopPlayback() {
        playback.stop()
        preparation?.cancel()
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
    }

    internal inner class SessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo):
            MediaSession.ConnectionResult {
            val ownApp = controller.uid == Process.myUid()
            if (!ownApp && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val commands = SessionCommands.Builder()
                .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
                .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
            if (ownApp) commands.add(PlaybackConnection.playLocalCommand)
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
            if (controller.uid != Process.myUid() ||
                customCommand.customAction != PlaybackConnection.PLAY_LOCAL
            ) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
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
            val future = SettableFuture.create<SessionResult>()
            preparation = scope.launch {
                try {
                    val error = playback.start(request.first, request.second)
                    future.set(if (error == null) SessionResult(SessionResult.RESULT_SUCCESS) else errorResult(error))
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
    }
}

private fun Int.isPhoneCallMode() = this == AudioManager.MODE_RINGTONE || this == AudioManager.MODE_IN_CALL

// Android reports device inventory here, including removals before a route is observable.
private fun AudioDeviceInfo.isExternalOutput(): Boolean {
    val deviceType = type
    if (!isSink || deviceType == AudioDeviceInfo.TYPE_UNKNOWN) return false
    return when (deviceType) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_TELEPHONY,
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> false
        else -> true
    }
}

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
