package com.audipp.playback

import com.audipp.data.db.QueueRepeatMode
import com.audipp.domain.model.ExecutionMode
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.PlaybackSnapshot
import com.audipp.domain.model.QueueEntry
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId
import kotlin.math.roundToLong

data class RemainingPlaybackTime(
    val currentItemMs: Long?,
    val upcomingItemsMs: Map<QueueEntryId, Long>,
    val queueMs: Long?,
)

sealed interface PlaybackSchedule {
    data object Unknown : PlaybackSchedule
    data class Repeating(val currentItemMs: Long?) : PlaybackSchedule
    data class RemainingDurationOnly(val remaining: RemainingPlaybackTime) : PlaybackSchedule
    data class Partial(
        val remaining: RemainingPlaybackTime,
        val expectedStartsEpochMs: Map<QueueEntryId, Long>,
    ) : PlaybackSchedule
    data class Estimated(
        val remaining: RemainingPlaybackTime,
        val expectedStartsEpochMs: Map<QueueEntryId, Long>,
        val expectedEndEpochMs: Long,
    ) : PlaybackSchedule
}

/**
 * Use the playback owner's current order (including shuffle), speed, and snapshot together.
 * Leave the order or speed null if unobserved and omit untrusted variant durations.
 */
fun calculatePlaybackSchedule(
    snapshot: PlaybackSnapshot,
    effectiveOrder: List<QueueEntry>?,
    variants: Map<VariantId, TrackVariant>,
    playbackSpeed: Double?,
    repeatMode: QueueRepeatMode,
    nowEpochMs: Long,
): PlaybackSchedule {
    val order = effectiveOrder ?: return PlaybackSchedule.Unknown
    val speed = playbackSpeed?.takeIf { it.isFinite() && it > 0 } ?: return PlaybackSchedule.Unknown
    val currentIndex = order.indexOfFirst { it.entryId == snapshot.currentEntryId }
    if (currentIndex < 0) return PlaybackSchedule.Unknown

    val current = order[currentIndex]
    val plan = snapshot.executionPlan
    if (plan?.mode == ExecutionMode.EXTERNAL_HANDOFF || (plan != null && plan.variantId != current.variantId)) {
        return PlaybackSchedule.Unknown
    }
    val positionMs = when (plan?.mode) {
        ExecutionMode.NETWORK_REMOTE, ExecutionMode.PARTNER_REMOTE ->
            snapshot.positionMs.takeIf { plan.capabilities.canReadPosition }
        else -> snapshot.positionMs
    }
    val currentDurationMs = variants[current.variantId]?.durationMs
    val currentRemainingMs = if (currentDurationMs != null && positionMs != null) {
        durationAtSpeed((currentDurationMs - positionMs).coerceAtLeast(0), speed)
    } else {
        null
    }
    if (repeatMode != QueueRepeatMode.OFF) return PlaybackSchedule.Repeating(currentRemainingMs)

    val playing = snapshot.phase == PlaybackPhase.PLAYING
    val itemDurations = linkedMapOf<QueueEntryId, Long>()
    val expectedStarts = linkedMapOf<QueueEntryId, Long>()
    var remainingMs = currentRemainingMs
    for (index in currentIndex + 1 until order.size) {
        val entry = order[index]
        if (playing && remainingMs != null) {
            expectedStarts[entry.entryId] = nowEpochMs + remainingMs
        }
        val itemMs = variants[entry.variantId]?.durationMs?.let { durationAtSpeed(it, speed) }
        if (itemMs != null) itemDurations[entry.entryId] = itemMs
        remainingMs = if (remainingMs != null && itemMs != null) remainingMs + itemMs else null
    }

    if (currentRemainingMs == null && itemDurations.isEmpty()) {
        return PlaybackSchedule.Unknown
    }
    val remaining = RemainingPlaybackTime(currentRemainingMs, itemDurations, remainingMs)
    return when {
        !playing -> PlaybackSchedule.RemainingDurationOnly(remaining)
        remainingMs == null -> PlaybackSchedule.Partial(remaining, expectedStarts)
        else -> PlaybackSchedule.Estimated(remaining, expectedStarts, nowEpochMs + remainingMs)
    }
}

private fun durationAtSpeed(durationMs: Long, speed: Double): Long =
    (durationMs / speed).roundToLong()
