package com.audipp.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.audipp.audio.route.OutputConfirmation
import com.audipp.domain.device.VolumeSettings
import com.audipp.domain.model.AudioFormat
import com.audipp.domain.model.ModelJson
import com.audipp.domain.model.PlaybackError
import com.audipp.domain.model.PlaybackSnapshot
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.VariantId
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.serialization.Serializable

@Serializable
internal data class OutputConfirmationRequest(
    val id: String,
    val reason: OutputConfirmation,
    val outputType: Int?,
    val limit: VolumeSettings?,
    val physicalGainNote: String?,
)

/** Connect on the main looper; release the future with MediaController.releaseFuture on UI teardown. */
@androidx.annotation.OptIn(UnstableApi::class)
object PlaybackConnection {
    internal const val PLAY_LOCAL = "com.audipp.playback.PLAY_LOCAL"
    internal const val PLAY_QUEUE = "com.audipp.playback.PLAY_QUEUE"
    internal const val QUEUE_ID = "queueId"
    internal const val QUEUE_REVISION = "queueRevision"
    internal const val CONFIRM_OUTPUT = "com.audipp.playback.CONFIRM_OUTPUT"
    internal const val RETRY_OUTPUT_OBSERVATION = "com.audipp.playback.RETRY_OUTPUT_OBSERVATION"
    internal const val CONFIRMATION_ID = "confirmationId"
    internal const val RESTORE_DIGITAL_VOLUME = "restoreDigitalVolume"
    internal const val VARIANT = "variant"
    internal const val ENTRY_ID = "entryId"
    internal const val SNAPSHOT = "playbackSnapshot"
    internal const val SCHEDULE = "playbackSchedule"
    internal const val ERROR = "playbackError"
    internal const val OUTPUT_CONFIRMATION = "outputConfirmation"
    internal const val PREPARED_TRANSITION = "preparedTransition"
    internal const val PREPARED_VARIANT = "preparedVariant"
    internal const val SOURCE_FORMAT = "sourceFormat"
    internal const val OBSERVED_OUTPUT_TYPES = "observedOutputTypes"
    const val ACTION_OPEN_NOW_PLAYING = "com.audipp.playback.OPEN_NOW_PLAYING"
    internal val playLocalCommand = SessionCommand(PLAY_LOCAL, Bundle.EMPTY)
    internal val playQueueCommand = SessionCommand(PLAY_QUEUE, Bundle.EMPTY)
    internal val confirmOutputCommand = SessionCommand(CONFIRM_OUTPUT, Bundle.EMPTY)
    internal val retryOutputObservationCommand = SessionCommand(RETRY_OUTPUT_OBSERVATION, Bundle.EMPTY)

    fun connect(context: Context, listener: MediaController.Listener): ListenableFuture<MediaController> =
        MediaController.Builder(context.applicationContext,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(listener).buildAsync()

    /** Success acknowledges preparation only; actual engine state/errors arrive in session extras. */
    fun playLocal(controller: MediaController, entryId: QueueEntryId,
        variant: TrackVariant): ListenableFuture<SessionResult> = controller.sendCustomCommand(
        playLocalCommand, Bundle().apply {
            putString(ENTRY_ID, entryId.value)
            putString(VARIANT, ModelJson.encode(variant))
        },
    )

    /**
     * Plays a saved queue entry and continues with the queue's order on natural end. A revision
     * other than the committed one is rejected with ERROR_INVALID_STATE without touching playback.
     */
    fun playQueue(controller: MediaController, queueId: String, entryId: QueueEntryId,
        queueRevision: Long): ListenableFuture<SessionResult> = controller.sendCustomCommand(
        playQueueCommand, Bundle().apply {
            putString(QUEUE_ID, queueId)
            putString(ENTRY_ID, entryId.value)
            putLong(QUEUE_REVISION, queueRevision)
        },
    )

    fun snapshot(extras: Bundle): PlaybackSnapshot? = extras.getString(SNAPSHOT)?.let {
        ModelJson.decode<PlaybackSnapshot>(it)
    }

    internal fun outputConfirmation(extras: Bundle): OutputConfirmationRequest? =
        extras.getString(OUTPUT_CONFIRMATION)?.let { ModelJson.decode<OutputConfirmationRequest>(it) }

    internal fun preparedTransition(extras: Bundle): TransitionId? =
        extras.getString(PREPARED_TRANSITION)?.let(::TransitionId)

    internal fun preparedVariant(extras: Bundle): VariantId? =
        extras.getString(PREPARED_VARIANT)?.let(::VariantId)

    /** The library's declared format of the prepared variant; it is not the decoder or output format. */
    internal fun sourceFormat(extras: Bundle): AudioFormat? =
        extras.getString(SOURCE_FORMAT)?.let { ModelJson.decode<AudioFormat>(it) }

    /** Android `AudioDeviceInfo` types of the AudioTrack's live route; null while no route is observed. */
    internal fun observedOutputTypes(extras: Bundle): List<Int>? =
        extras.getIntArray(OBSERVED_OUTPUT_TYPES)?.toList()

    internal fun confirmOutput(controller: MediaController, id: String, restoreDigitalVolume: Boolean = false) =
        controller.sendCustomCommand(confirmOutputCommand, Bundle().apply {
            putString(CONFIRMATION_ID, id)
            putBoolean(RESTORE_DIGITAL_VOLUME, restoreDigitalVolume)
        })

    internal fun retryOutputObservation(controller: MediaController, id: String) =
        controller.sendCustomCommand(retryOutputObservationCommand, Bundle().apply {
            putString(CONFIRMATION_ID, id)
        })

    fun schedule(extras: Bundle): PlaybackSchedule? = extras.getString(SCHEDULE)?.let {
        ModelJson.decode<PlaybackSchedule>(it)
    }

    fun error(result: SessionResult): PlaybackError? = result.extras.getString(ERROR)?.let {
        ModelJson.decode<PlaybackError>(it)
    }
}
