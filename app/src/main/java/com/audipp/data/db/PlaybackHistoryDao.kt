package com.audipp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.audipp.domain.model.SourceKind

@Entity(tableName = "play_events")
internal data class PlayEventEntity(
    @PrimaryKey @ColumnInfo(name = "play_event_id") val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "variant_id") val variantId: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "source_kind") val sourceKind: SourceKind,
    val codec: String?,
    @ColumnInfo(name = "start_utc_ms") val startUtcMs: Long,
    @ColumnInfo(name = "time_zone") val timeZone: String,
    @ColumnInfo(name = "source_duration_ms") val sourceDurationMs: Long?,
    @ColumnInfo(name = "end_utc_ms") val endUtcMs: Long? = null,
    @ColumnInfo(name = "played_ms") val playedMs: Long = 0,
    val qualified: Boolean = false,
)

@Entity(
    tableName = "play_segments",
    foreignKeys = [ForeignKey(
        entity = PlayEventEntity::class,
        parentColumns = ["play_event_id"],
        childColumns = ["play_event_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("play_event_id")],
)
internal data class PlaySegmentEntity(
    @PrimaryKey @ColumnInfo(name = "segment_id") val id: String,
    @ColumnInfo(name = "play_event_id") val eventId: String,
    @ColumnInfo(name = "start_utc_ms") val startUtcMs: Long,
    @ColumnInfo(name = "time_zone") val timeZone: String,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
    @ColumnInfo(name = "source_start_ms") val sourceStartMs: Long,
    @ColumnInfo(name = "source_end_ms") val sourceEndMs: Long,
    @ColumnInfo(name = "playback_speed") val speed: Double,
    @ColumnInfo(name = "route_id") val routeId: String?,
    @ColumnInfo(name = "profile_id") val profileId: String?,
)

@Dao
internal abstract class PlaybackHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEvent(event: PlayEventEntity)

    @Update
    protected abstract suspend fun updateEvent(event: PlayEventEntity)

    @Upsert
    protected abstract suspend fun upsertSegment(segment: PlaySegmentEntity)

    @Query("SELECT COALESCE(SUM(listened_ms), 0) FROM play_segments WHERE play_event_id = :eventId")
    protected abstract suspend fun playedMs(eventId: String): Long

    @Query("SELECT * FROM play_events WHERE play_event_id = :eventId")
    abstract suspend fun event(eventId: String): PlayEventEntity?

    @Query("SELECT * FROM play_segments WHERE play_event_id = :eventId ORDER BY rowid")
    abstract suspend fun segments(eventId: String): List<PlaySegmentEntity>

    @Transaction
    open suspend fun save(event: PlayEventEntity, segment: PlaySegmentEntity?) {
        val previous = event(event.id)
        if (previous?.endUtcMs != null) return
        if (previous == null) insertEvent(event)
        if (segment != null) upsertSegment(segment)
        val playedMs = playedMs(event.id)
        updateEvent(event.copy(playedMs = playedMs, qualified = qualifiesPlay(playedMs, event.sourceDurationMs)))
    }
}

internal fun qualifiesPlay(playedMs: Long, durationMs: Long?): Boolean =
    playedMs > 0 && durationMs != 0L && playedMs >= minOf(durationMs?.div(2) ?: 240_000, 240_000)
