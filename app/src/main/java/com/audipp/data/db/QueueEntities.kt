package com.audipp.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.audipp.domain.model.QueueEntry
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.VariantId

enum class QueueRepeatMode { OFF, ONE, ALL }

@Entity(tableName = "queues")
data class QueueEntity(
    @PrimaryKey @ColumnInfo(name = "queue_id") val queueId: String,
    val name: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val revision: Long = 0,
    @ColumnInfo(name = "shuffle_enabled") val shuffleEnabled: Boolean = false,
    @ColumnInfo(name = "shuffle_seed") val shuffleSeed: Long? = null,
    @ColumnInfo(name = "repeat_mode") val repeatMode: QueueRepeatMode = QueueRepeatMode.OFF,
)

@Entity(
    tableName = "queue_items",
    foreignKeys = [
        ForeignKey(entity = QueueEntity::class, parentColumns = ["queue_id"], childColumns = ["queue_id"]),
        ForeignKey(entity = VariantEntity::class, parentColumns = ["variant_id"], childColumns = ["variant_id"]),
    ],
    indices = [Index(value = ["queue_id", "ordinal"], unique = true), Index("variant_id")],
)
data class QueueItemEntity(
    @PrimaryKey @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "queue_id") val queueId: String,
    val ordinal: Int,
    @ColumnInfo(name = "variant_id") val variantId: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
) {
    fun toDomain() = QueueEntry(QueueEntryId(entryId), VariantId(variantId))
}

@Entity(
    tableName = "playback_checkpoint",
    foreignKeys = [
        ForeignKey(entity = QueueEntity::class, parentColumns = ["queue_id"], childColumns = ["queue_id"]),
        ForeignKey(entity = QueueItemEntity::class, parentColumns = ["entry_id"], childColumns = ["entry_id"]),
    ],
    indices = [Index("queue_id"), Index("entry_id")],
)
data class PlaybackCheckpointEntity(
    @ColumnInfo(name = "queue_id") val queueId: String,
    @ColumnInfo(name = "queue_revision") val queueRevision: Long,
    @ColumnInfo(name = "entry_id") val entryId: String?,
    @ColumnInfo(name = "position_ms") val positionMs: Long?,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    @PrimaryKey @ColumnInfo(name = "slot_id") val slotId: Int = 1,
) {
    init {
        require(slotId == 1)
        require(queueRevision >= 0 && savedAt >= 0)
        require(positionMs == null || (entryId != null && positionMs >= 0))
    }
}
