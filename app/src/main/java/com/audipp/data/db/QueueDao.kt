package com.audipp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.audipp.domain.model.QueueEntry
import com.audipp.domain.model.QueueEntryId
import kotlin.random.Random

data class StoredQueue(val queue: QueueEntity, val entries: List<QueueEntry>)

sealed interface QueueEditResult {
    data class Updated(val revision: Long) : QueueEditResult
    data object StaleRevision : QueueEditResult
    data class StopRequired(val entryId: QueueEntryId) : QueueEditResult
}

@Dao
abstract class QueueDao {
    suspend fun create(queueId: String, name: String?, createdAt: Long) {
        require(queueId.isNotBlank() && createdAt >= 0)
        insertQueue(QueueEntity(queueId, name, createdAt, createdAt))
    }

    @Transaction
    open suspend fun read(queueId: String): StoredQueue? {
        val queue = queue(queueId) ?: return null
        return StoredQueue(queue, items(queueId).map(QueueItemEntity::toDomain))
    }

    @Query("SELECT revision FROM queues WHERE queue_id = :queueId")
    abstract suspend fun revision(queueId: String): Long?

    @Transaction
    open suspend fun setPlaybackOrder(
        queueId: String,
        expectedRevision: Long,
        shuffleEnabled: Boolean,
        repeatMode: QueueRepeatMode,
        updatedAt: Long,
    ): QueueEditResult {
        require(updatedAt >= 0)
        val queue = queue(queueId)
        if (queue == null || queue.revision != expectedRevision) return QueueEditResult.StaleRevision
        if (queue.shuffleEnabled == shuffleEnabled && queue.repeatMode == repeatMode) {
            return QueueEditResult.Updated(expectedRevision)
        }

        val seed = queue.shuffleSeed ?: if (shuffleEnabled) Random.nextLong() else null
        val revision = expectedRevision + 1
        updatePlaybackOrder(queueId, revision, shuffleEnabled, seed, repeatMode, updatedAt)
        reviseCheckpoint(queueId, revision, clearEntry = false)
        return QueueEditResult.Updated(revision)
    }

    @Query("SELECT * FROM playback_checkpoint WHERE slot_id = 1")
    abstract suspend fun checkpoint(): PlaybackCheckpointEntity?

    @Transaction
    open suspend fun saveCheckpoint(checkpoint: PlaybackCheckpointEntity): Boolean {
        if (queue(checkpoint.queueId)?.revision != checkpoint.queueRevision) return false
        val entryId = checkpoint.entryId
        if (entryId != null && !containsEntry(checkpoint.queueId, entryId)) return false
        upsertCheckpoint(checkpoint)
        return true
    }

    /**
     * Retry StopRequired with the same revision and entry ID after the playback owner cancels
     * the transition and requests stop. The owner serializes the retry with playback commands
     * and checks the live entry too, since the last checkpoint may lag behind the player.
     */
    @Transaction
    open suspend fun replaceEntries(
        queueId: String,
        expectedRevision: Long,
        entries: List<QueueEntry>,
        updatedAt: Long,
        stoppedEntryId: QueueEntryId? = null,
    ): QueueEditResult {
        require(updatedAt >= 0)
        val desiredIds = entries.map { it.entryId.value }.toSet()
        require(desiredIds.size == entries.size)
        val queue = queue(queueId)
        if (queue == null || queue.revision != expectedRevision) return QueueEditResult.StaleRevision
        val previous = items(queueId)
        val existing = previous.associateBy { it.entryId }
        require(entries.all { entry ->
            existing[entry.entryId.value]?.variantId?.let { it == entry.variantId.value } ?: true
        })
        if (previous.map(QueueItemEntity::toDomain) == entries) {
            return QueueEditResult.Updated(expectedRevision)
        }

        val removedCurrent = checkpoint()?.takeIf { it.queueId == queueId }?.entryId
            ?.takeUnless { it in desiredIds }
        if (removedCurrent != null && stoppedEntryId?.value != removedCurrent) {
            return QueueEditResult.StopRequired(QueueEntryId(removedCurrent))
        }

        val revision = expectedRevision + 1
        reviseCheckpoint(queueId, revision, removedCurrent != null)
        // SQLite checks ordinal uniqueness row by row, so move existing ordinals out of the way first.
        parkOrdinals(queueId)
        entries.forEachIndexed { ordinal, entry ->
            val entryId = entry.entryId.value
            if (entryId in existing) {
                assignOrdinal(queueId, entryId, ordinal)
            } else {
                insertItem(QueueItemEntity(entryId, queueId, ordinal, entry.variantId.value, updatedAt))
            }
        }
        deleteParkedItems(queueId)
        reviseQueue(queueId, revision, updatedAt)
        return QueueEditResult.Updated(revision)
    }

    @Insert protected abstract suspend fun insertQueue(queue: QueueEntity)
    @Insert protected abstract suspend fun insertItem(item: QueueItemEntity)
    @Upsert protected abstract suspend fun upsertCheckpoint(checkpoint: PlaybackCheckpointEntity)

    @Query("SELECT * FROM queues WHERE queue_id = :queueId")
    protected abstract suspend fun queue(queueId: String): QueueEntity?

    @Query("SELECT * FROM queue_items WHERE queue_id = :queueId ORDER BY ordinal")
    protected abstract suspend fun items(queueId: String): List<QueueItemEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM queue_items WHERE queue_id = :queueId AND entry_id = :entryId)")
    protected abstract suspend fun containsEntry(queueId: String, entryId: String): Boolean

    @Query("""
        UPDATE playback_checkpoint
        SET queue_revision = :revision,
            entry_id = CASE WHEN :clearEntry THEN NULL ELSE entry_id END,
            position_ms = CASE WHEN :clearEntry THEN NULL ELSE position_ms END
        WHERE slot_id = 1 AND queue_id = :queueId
    """)
    protected abstract suspend fun reviseCheckpoint(queueId: String, revision: Long, clearEntry: Boolean)

    @Query("UPDATE queue_items SET ordinal = -ordinal - 1 WHERE queue_id = :queueId")
    protected abstract suspend fun parkOrdinals(queueId: String)

    @Query("UPDATE queue_items SET ordinal = :ordinal WHERE queue_id = :queueId AND entry_id = :entryId")
    protected abstract suspend fun assignOrdinal(queueId: String, entryId: String, ordinal: Int)

    @Query("DELETE FROM queue_items WHERE queue_id = :queueId AND ordinal < 0")
    protected abstract suspend fun deleteParkedItems(queueId: String)

    @Query("UPDATE queues SET revision = :revision, updated_at = :updatedAt WHERE queue_id = :queueId")
    protected abstract suspend fun reviseQueue(queueId: String, revision: Long, updatedAt: Long)

    @Query("""
        UPDATE queues
        SET revision = :revision, shuffle_enabled = :shuffleEnabled, shuffle_seed = :seed,
            repeat_mode = :repeatMode, updated_at = :updatedAt
        WHERE queue_id = :queueId
    """)
    protected abstract suspend fun updatePlaybackOrder(
        queueId: String, revision: Long, shuffleEnabled: Boolean, seed: Long?,
        repeatMode: QueueRepeatMode, updatedAt: Long,
    )
}
