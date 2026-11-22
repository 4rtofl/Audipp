package com.audipp.ui.navigation

import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.audipp.R
import com.audipp.audio.route.OutputConfirmation
import com.audipp.domain.model.Availability
import com.audipp.domain.model.ErrorDomain
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.PlaybackSnapshot
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.TransitionId
import com.audipp.playback.OutputConfirmationRequest
import com.audipp.playback.PlaybackConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import java.util.concurrent.ExecutionException

internal sealed interface NavigationPlaybackState {
    data object Connecting : NavigationPlaybackState
    data object Disconnected : NavigationPlaybackState
    data object Idle : NavigationPlaybackState
    data class Failed(val domain: ErrorDomain) : NavigationPlaybackState
    data class Current(
        val phase: PlaybackPhase,
        val title: String?,
        val artist: String?,
        val control: PlaybackControl?,
        val outputConfirmation: OutputConfirmationRequest? = null,
    ) : NavigationPlaybackState
}

internal data class PlaybackControl(
    val entryId: QueueEntryId,
    val transitionId: TransitionId,
    val pause: Boolean,
)

internal fun PlaybackSnapshot.controlFor(
    mediaId: String?,
    playPauseAvailable: Boolean,
    preparedTransition: TransitionId? = null,
): PlaybackControl? {
    val entry = currentEntryId ?: return null
    // An output revision invalidates the plan without dropping the service's prepared media item.
    val transition = executionPlan?.transitionId ?: preparedTransition ?: return null
    if (entry.value != mediaId || !playPauseAvailable || effectiveSource?.availability != Availability.PLAYABLE) {
        return null
    }
    return when {
        phase == PlaybackPhase.PLAYING && executionPlan?.capabilities?.canPause != false ->
            PlaybackControl(entry, transition, pause = true)
        phase == PlaybackPhase.PAUSED && executionPlan?.capabilities?.canPlay != false ->
            PlaybackControl(entry, transition, pause = false)
        else -> null
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class NavigationPlayback(private val context: Context) {
    private var controller: MediaController? = null

    // MediaController callbacks and commands run on the same looper as viewModelScope.
    fun observe() = callbackFlow<NavigationPlaybackState> {
        trySend(NavigationPlaybackState.Connecting)
        fun publish(current: MediaController) {
            if (current === controller) trySend(current.presentation())
        }

        val playerListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) {
                    publish(player as MediaController)
                }
            }
        }
        val listener = object : MediaController.Listener {
            override fun onExtrasChanged(current: MediaController, extras: Bundle) {
                publish(current)
            }

            override fun onDisconnected(current: MediaController) {
                trySend(NavigationPlaybackState.Disconnected)
                close()
            }
        }
        val future = PlaybackConnection.connect(context, listener)
        future.addListener({
            if (!isActive) return@addListener
            val current = try {
                future.get()
            } catch (_: ExecutionException) {
                null
            } catch (_: java.util.concurrent.CancellationException) {
                null
            }
            if (current == null || !current.isConnected) {
                trySend(NavigationPlaybackState.Disconnected)
                close()
            } else {
                controller = current
                current.addListener(playerListener)
                publish(current)
            }
        }, ContextCompat.getMainExecutor(context))
        awaitClose {
            controller?.removeListener(playerListener)
            controller = null
            MediaController.releaseFuture(future)
        }
    }.catch {
        if (it is CancellationException || it !is Exception) throw it
        emit(NavigationPlaybackState.Disconnected)
    }

    fun toggle(requested: PlaybackControl) {
        val current = controller?.takeIf { it.isConnected } ?: return
        if (PlaybackConnection.outputConfirmation(current.sessionExtras) != null) return
        if (current.control(current.snapshot()) != requested) return
        if (requested.pause) current.pause() else current.play()
    }

    fun confirmOutput(requested: OutputConfirmationRequest) {
        val current = controller?.takeIf { it.isConnected } ?: return
        if (PlaybackConnection.outputConfirmation(current.sessionExtras) != requested) return
        if (requested.reason == OutputConfirmation.ROUTE_UNKNOWN) {
            PlaybackConnection.retryOutputObservation(current, requested.id)
        } else {
            PlaybackConnection.confirmOutput(current, requested.id)
        }
    }

    private fun MediaController.presentation(): NavigationPlaybackState {
        val snapshot = snapshot() ?: return NavigationPlaybackState.Disconnected
        if (snapshot.phase == PlaybackPhase.ERROR) {
            return NavigationPlaybackState.Failed(snapshot.error?.domain ?: ErrorDomain.UNKNOWN)
        }
        if (snapshot.currentEntryId == null || snapshot.phase == PlaybackPhase.IDLE) {
            return NavigationPlaybackState.Idle
        }
        val metadata = if (mediaId() == snapshot.currentEntryId.value && isCommandAvailable(Player.COMMAND_GET_METADATA)) {
            mediaMetadata
        } else {
            null
        }
        val title = metadata?.title?.toString()
        val outputConfirmation = PlaybackConnection.outputConfirmation(sessionExtras)
        // The service seeds the notification with the app name before file metadata is available.
        return NavigationPlaybackState.Current(
            phase = snapshot.phase,
            title = title?.takeUnless { it.isBlank() || it == context.getString(R.string.app_name) },
            artist = metadata?.artist?.toString()?.takeIf { it.isNotBlank() },
            control = if (outputConfirmation == null) control(snapshot) else null,
            outputConfirmation = outputConfirmation,
        )
    }

    private fun MediaController.mediaId(): String? =
        if (isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) currentMediaItem?.mediaId else null

    private fun MediaController.control(snapshot: PlaybackSnapshot?): PlaybackControl? =
        snapshot?.controlFor(mediaId(), isCommandAvailable(Player.COMMAND_PLAY_PAUSE),
            PlaybackConnection.preparedTransition(sessionExtras))

    private fun MediaController.snapshot(): PlaybackSnapshot? = try {
        PlaybackConnection.snapshot(sessionExtras)
    } catch (_: IllegalArgumentException) {
        null
    }
}
