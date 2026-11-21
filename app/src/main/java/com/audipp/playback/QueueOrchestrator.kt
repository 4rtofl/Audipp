package com.audipp.playback

import com.audipp.data.db.LibraryDao
import com.audipp.data.db.QueueDao
import com.audipp.data.db.QueueRepeatMode
import com.audipp.data.db.StoredQueue
import com.audipp.domain.model.QueueEntry
import com.audipp.domain.model.QueueEntryId
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.ResolutionFailure
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceResolution
import com.audipp.playback.source.SourceResolver
import java.util.Collections
import java.util.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class QueueAdvanceTrigger { NATURAL_END, SKIP_NEXT }
enum class UnplayableItemPolicy { ASK, SKIP }

data class UnavailableQueueEntry(val entryId: QueueEntryId, val reason: ResolutionFailure)

sealed interface QueueAdvanceResult {
    data class Ready(
        val queueId: String,
        val queueRevision: Long,
        val entry: QueueEntry,
        val source: SourceResolution.Resolved,
        val skipped: List<UnavailableQueueEntry>,
    ) : QueueAdvanceResult

    data class RequiresUserAction(val failure: UnavailableQueueEntry) : QueueAdvanceResult
    data class NoPlayableItem(val failures: List<UnavailableQueueEntry>) : QueueAdvanceResult
    data class TimedOut(val entryId: QueueEntryId) : QueueAdvanceResult
    data object QueueEnded : QueueAdvanceResult
    data object QueueChanged : QueueAdvanceResult
    data object Cancelled : QueueAdvanceResult
}

fun StoredQueue.effectivePlaybackOrder(): List<QueueEntry> {
    if (!queue.shuffleEnabled) return entries
    // Ordinal edits should not reshuffle the same entries, even after restoring the queue.
    val shuffled = entries.sortedBy { it.entryId.value }.toMutableList()
    Collections.shuffle(shuffled, Random(checkNotNull(queue.shuffleSeed)))
    return shuffled
}

class QueueOrchestrator(
    private val queues: QueueDao,
    private val library: LibraryDao,
    private val resolver: SourceResolver,
) {
    /**
     * The playback owner begins the transition and supplies its context. Ready hands off the
     * resolved candidate; the owner checks its revision and context again when starting it.
     */
    suspend fun advance(
        queueId: String,
        currentEntryId: QueueEntryId?,
        trigger: QueueAdvanceTrigger,
        policy: UnplayableItemPolicy,
        context: ResolutionContext,
    ): QueueAdvanceResult {
        val snapshot = queues.read(queueId) ?: return QueueAdvanceResult.QueueChanged
        val revision = snapshot.queue.revision
        val order = snapshot.effectivePlaybackOrder()
        val currentIndex = order.indexOfFirst { it.entryId == currentEntryId }
        if (currentEntryId != null && currentIndex == -1) return QueueAdvanceResult.QueueChanged
        val tail = order.drop(currentIndex + 1)
        val candidates = when {
            currentIndex == -1 -> order
            snapshot.queue.repeatMode == QueueRepeatMode.ONE && trigger == QueueAdvanceTrigger.NATURAL_END ->
                listOf(order[currentIndex])
            snapshot.queue.repeatMode == QueueRepeatMode.ALL -> tail + order.take(currentIndex + 1)
            else -> tail
        }
        if (candidates.isEmpty()) return QueueAdvanceResult.QueueEnded

        val unavailable = mutableListOf<UnavailableQueueEntry>()
        for (entry in candidates) {
            if (queues.revision(queueId) != revision) return QueueAdvanceResult.QueueChanged
            val variant = library.trackVariant(entry.variantId)
            val result = if (variant == null) {
                SourceResolution.Unavailable(ResolutionFailure.SOURCE_MISSING)
            } else {
                resolver.resolve(ResolutionRequest(variant, context))
            }
            currentCoroutineContext().ensureActive()
            if (queues.revision(queueId) != revision) return QueueAdvanceResult.QueueChanged
            when (result) {
                is SourceResolution.Resolved ->
                    return QueueAdvanceResult.Ready(queueId, revision, entry, result, unavailable.toList())
                is SourceResolution.Unavailable -> when (result.reason) {
                    ResolutionFailure.STALE_ROUTE, ResolutionFailure.STALE_TRANSITION ->
                        return QueueAdvanceResult.Cancelled
                    ResolutionFailure.TIMEOUT -> return QueueAdvanceResult.TimedOut(entry.entryId)
                    else -> {
                        val failure = UnavailableQueueEntry(entry.entryId, result.reason)
                        if (policy == UnplayableItemPolicy.ASK) return QueueAdvanceResult.RequiresUserAction(failure)
                        unavailable += failure
                    }
                }
            }
        }
        return QueueAdvanceResult.NoPlayableItem(unavailable.toList())
    }
}
