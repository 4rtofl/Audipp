package com.audipp.playback

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.db.PlaybackCheckpointEntity
import com.audipp.data.db.QueueDao
import com.audipp.data.db.StoredQueue
import com.audipp.domain.model.ExecutionMode
import com.audipp.domain.model.ExecutionPlan
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.PlaybackSnapshot
import com.audipp.domain.model.QueueEntry
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceResolution
import com.audipp.playback.source.SourceResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class PlaybackCheckpointSample(
    val queueId: String,
    val queueRevision: Long,
    val snapshot: PlaybackSnapshot,
)

internal enum class CheckpointWriteOutcome { SAVED, STALE_QUEUE, STORAGE_ERROR }

internal data class CheckpointWriteResult(
    val checkpoint: PlaybackCheckpointEntity,
    val outcome: CheckpointWriteOutcome,
)

/** Sample and enqueue on the playback looper; Room writes run serially on IO. */
internal class PlaybackCheckpointJournal(
    scope: CoroutineScope,
    private val queues: QueueDao,
    currentPlayback: () -> PlaybackCheckpointSample?,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    waitForSample: suspend (Long) -> Unit = { delay(it) },
) {
    // A slow write must not accumulate obsolete positions or delay playback commands.
    private val writes = Channel<PlaybackCheckpointEntity>(Channel.CONFLATED)
    private val mutableLastWrite = MutableStateFlow<CheckpointWriteResult?>(null)
    val lastWrite = mutableLastWrite.asStateFlow()

    init {
        val writer = scope.launch(Dispatchers.IO) {
            for (checkpoint in writes) {
                val outcome = try {
                    if (queues.saveCheckpoint(checkpoint)) CheckpointWriteOutcome.SAVED
                    else CheckpointWriteOutcome.STALE_QUEUE
                } catch (_: SQLiteException) {
                    CheckpointWriteOutcome.STORAGE_ERROR
                }
                mutableLastWrite.value = CheckpointWriteResult(checkpoint, outcome)
            }
        }
        writer.invokeOnCompletion { writes.close() }
        scope.launch {
            while (isActive) {
                waitForSample(5_000)
                val sample = currentPlayback() ?: continue
                if (sample.snapshot.phase == PlaybackPhase.PLAYING && sample.observedPositionMs() != null) {
                    saveConfirmed(sample)
                }
            }
        }
    }

    /** For a committed queue/item change, completed seek, pause, or stop. */
    fun saveConfirmed(sample: PlaybackCheckpointSample) {
        writes.trySend(
            PlaybackCheckpointEntity(
                sample.queueId, sample.queueRevision, sample.snapshot.currentEntryId?.value,
                sample.observedPositionMs(), nowEpochMs(),
            ),
        )
    }
}

internal data class RestoredPlaybackCheckpoint(
    val queue: StoredQueue,
    val entry: QueueEntry? = null,
    val positionMs: Long? = null,
)

/** Read the latest commit and revalidate its selected variant without starting playback. */
internal suspend fun restorePlaybackCheckpoint(
    database: LibraryDatabase,
    resolver: SourceResolver,
    context: ResolutionContext,
): RestoredPlaybackCheckpoint? {
    val queues = database.queueDao()
    val (checkpoint, restored, variant) = database.withTransaction {
        val checkpoint = queues.checkpoint() ?: return@withTransaction null
        val queue = queues.read(checkpoint.queueId) ?: return@withTransaction null
        if (queue.queue.revision != checkpoint.queueRevision) return@withTransaction null
        val entry = queue.entries.firstOrNull { it.entryId.value == checkpoint.entryId }
        val variant = entry?.let { database.libraryDao().trackVariant(it.variantId) }
        Triple(checkpoint, RestoredPlaybackCheckpoint(queue, entry), variant)
    } ?: return null

    if (variant == null) return restored.copy(entry = null)
    val result = resolver.resolve(ResolutionRequest(variant, context))
    // Resolution may suspend while the active queue or checkpoint is replaced.
    if (queues.checkpoint() != checkpoint) return null
    if (result !is SourceResolution.Resolved) return restored.copy(entry = null)
    val position = checkpoint.positionMs
    val duration = variant.durationMs
    return restored.copy(
        positionMs = if (position != null && duration != null && result.plan.canObserveCheckpointPosition()) {
            position.coerceAtMost(duration)
        } else null,
    )
}

private fun PlaybackCheckpointSample.observedPositionMs(): Long? =
    snapshot.positionMs?.takeIf {
        snapshot.currentEntryId != null && snapshot.executionPlan?.canObserveCheckpointPosition() != false
    }

private fun ExecutionPlan.canObserveCheckpointPosition() =
    mode != ExecutionMode.EXTERNAL_HANDOFF && capabilities.canReadPosition
