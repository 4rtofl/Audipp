package com.audipp.playback

import com.audipp.data.db.PlayEventEntity
import com.audipp.data.db.PlaySegmentEntity
import com.audipp.domain.model.ProviderScope
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.TrackVariant
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs

internal data class PlaybackHistoryWrite(
    val event: PlayEventEntity,
    val segment: PlaySegmentEntity?,
    val providerScope: ProviderScope?,
)

internal data class HistoryOutput(
    val routeRevision: Long,
    val routeId: String?,
    val profileId: String? = null,
)

// Only the playback looper owns the attempt and its open segment.
internal class PlaybackHistoryRecorder(
    private val elapsedRealtimeMs: () -> Long,
    private val nowUtcMs: () -> Long = System::currentTimeMillis,
    private val timeZoneId: () -> String = { TimeZone.getDefault().id },
    private val write: (PlaybackHistoryWrite) -> Unit,
) {
    private data class OpenSegment(
        val startElapsedMs: Long,
        var lastElapsedMs: Long,
        val output: HistoryOutput,
        var data: PlaySegmentEntity,
    )

    private val sessionId = UUID.randomUUID().toString()
    private var event: PlayEventEntity? = null
    private var scope: ProviderScope? = null
    private var open: OpenSegment? = null

    fun observe(
        entryId: QueueEntryId,
        variant: TrackVariant,
        isPlaying: Boolean,
        positionMs: Long?,
        durationMs: Long?,
        speed: Double,
        utcMs: Long,
        output: HistoryOutput,
        providerScope: ProviderScope? = null,
        clockChanged: Boolean = false,
    ) {
        val elapsedMs = elapsedRealtimeMs()
        val zone = timeZoneId()
        val current = event ?: run {
            if (!isPlaying || positionMs == null) return
            scope = providerScope?.takeIf { it.providerKey == variant.source.providerKey }
            PlayEventEntity(
                UUID.randomUUID().toString(), sessionId, entryId.value, variant.trackId.value,
                variant.variantId.value, variant.source.sourceId.value, variant.source.kind,
                variant.format.codec, utcMs, zone, variant.durationMs,
            )
        }
        event = current.copy(sourceDurationMs = durationMs ?: current.sourceDurationMs)
        open?.let { segment ->
            val progressed = advance(segment, positionMs, elapsedMs)
            val data = segment.data
            val clockStep = abs((utcMs - data.startUtcMs) - (elapsedMs - segment.startElapsedMs))
            if (!progressed || !isPlaying || speed != data.speed || output != segment.output ||
                zone != data.timeZone || clockChanged || clockStep >= CLOCK_STEP_MS
            ) {
                open = null
            }
        }
        if (open == null && isPlaying && positionMs != null) {
            open = OpenSegment(elapsedMs, elapsedMs, output, PlaySegmentEntity(
                UUID.randomUUID().toString(), current.id, utcMs, zone, 0, positionMs, positionMs,
                speed, output.routeId, output.profileId,
            ))
        }
    }

    fun discontinuity(oldPositionMs: Long?) {
        open?.let { advance(it, oldPositionMs, elapsedRealtimeMs()) }
        open = null
    }

    fun finish(utcMs: Long = nowUtcMs()) {
        val current = event ?: return
        write(PlaybackHistoryWrite(current.copy(endUtcMs = utcMs), null, scope))
        open = null
        event = null
        scope = null
    }

    private fun advance(segment: OpenSegment, positionMs: Long?, elapsedMs: Long): Boolean {
        val data = segment.data
        if (positionMs == null || positionMs < data.sourceEndMs) return false
        val elapsed = elapsedMs - segment.lastElapsedMs
        if (elapsed == 0L) return true
        if (positionMs == data.sourceEndMs) return false
        segment.data = data.copy(listenedMs = data.listenedMs + elapsed, sourceEndMs = positionMs)
        segment.lastElapsedMs = elapsedMs
        write(PlaybackHistoryWrite(checkNotNull(event), segment.data, scope))
        return true
    }

    private companion object {
        const val CLOCK_STEP_MS = 1_000L
    }
}
