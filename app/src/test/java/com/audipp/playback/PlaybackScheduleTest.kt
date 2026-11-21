package com.audipp.playback

import com.audipp.data.db.QueueRepeatMode
import com.audipp.domain.model.Availability
import com.audipp.domain.model.ExecutionMode
import com.audipp.domain.model.ExecutionPlan
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.PlaybackSnapshot
import com.audipp.domain.model.QueueEntry
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.VariantId
import com.audipp.playback.source.SourceDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackScheduleTest {
    private val source = SourceRef(SourceId("audio"), SourceKind.NETWORK)
    private val track = TrackId("same-track")
    private val five = TrackVariant(VariantId("five"), track, source, durationMs = 300_000)
    private val seven = TrackVariant(VariantId("seven"), track, source, durationMs = 420_000)
    private val unknown = TrackVariant(VariantId("unknown"), track, source)
    private val variants = listOf(five, seven, unknown).associateBy { it.variantId }
    private val current = QueueEntry(QueueEntryId("current"), seven.variantId)
    private val next = QueueEntry(QueueEntryId("next"), five.variantId)
    private val last = QueueEntry(QueueEntryId("last"), seven.variantId)
    private val missingDuration = QueueEntry(QueueEntryId("unknown"), unknown.variantId)
    private val order = listOf(current, next, last)
    private val now = 1_000_000L
    private val playing = PlaybackSnapshot(1, PlaybackPhase.PLAYING, current.entryId, positionMs = 60_000)

    @Test
    fun selectedVariantAndActualOrderDetermineRemainingAndClockTimes() {
        val previous = QueueEntry(QueueEntryId("already-played"), five.variantId)
        val shuffled = listOf(previous, current, last, next)

        val result = schedule(effectiveOrder = shuffled, speed = 2.0) as PlaybackSchedule.Estimated

        assertEquals(
            RemainingPlaybackTime(180_000, mapOf(last.entryId to 210_000L, next.entryId to 150_000L), 540_000),
            result.remaining,
        )
        assertEquals(listOf(last.entryId, next.entryId), result.expectedStartsEpochMs.keys.toList())
        assertEquals(mapOf(last.entryId to 1_180_000L, next.entryId to 1_390_000L), result.expectedStartsEpochMs)
        assertEquals(1_540_000L, result.expectedEndEpochMs)
    }

    @Test
    fun recalculatesAfterSeekReorderAndDurationUpdate() {
        val revisedVariants = variants + (seven.variantId to seven.copy(durationMs = 480_000))
        val snapshot = playing.copy(revision = 2, positionMs = 90_000)
        val result = schedule(snapshot, listOf(current, last, next), revisedVariants) as PlaybackSchedule.Estimated

        assertEquals(390_000L, result.remaining.currentItemMs)
        assertEquals(mapOf(last.entryId to 1_390_000L, next.entryId to 1_870_000L), result.expectedStartsEpochMs)
        assertEquals(2_170_000L, result.expectedEndEpochMs)
    }

    @Test
    fun nonPlayingPhasesOnlyReturnKnownDurations() {
        for (phase in listOf(PlaybackPhase.PAUSED, PlaybackPhase.BUFFERING)) {
            val result = schedule(playing.copy(phase = phase), listOf(current, missingDuration, next))

            assertEquals(
                PlaybackSchedule.RemainingDurationOnly(
                    RemainingPlaybackTime(360_000, mapOf(next.entryId to 300_000L), null),
                ),
                result,
            )
        }
    }

    @Test
    fun bothRepeatModesReturnOnlyTheCurrentItemEvenWhenPaused() {
        for (repeat in listOf(QueueRepeatMode.ONE, QueueRepeatMode.ALL)) {
            assertEquals(PlaybackSchedule.Repeating(360_000), schedule(repeat = repeat))
            assertEquals(
                PlaybackSchedule.Repeating(null),
                schedule(playing.copy(phase = PlaybackPhase.PAUSED, positionMs = null), repeat = repeat),
            )
        }
    }

    @Test
    fun unknownDurationKeepsKnownSegmentsWithoutPredictingThroughTheGap() {
        val result = schedule(effectiveOrder = listOf(current, next, missingDuration, last))

        assertEquals(
            PlaybackSchedule.Partial(
                RemainingPlaybackTime(360_000, mapOf(next.entryId to 300_000L, last.entryId to 420_000L), null),
                mapOf(next.entryId to 1_360_000L, missingDuration.entryId to 1_660_000L),
            ),
            result,
        )
        assertEquals(
            PlaybackSchedule.Unknown,
            schedule(playing.copy(currentEntryId = missingDuration.entryId), listOf(missingDuration)),
        )
    }

    @Test
    fun externalPositionRequiresObservationAndHandoffHasNoSchedule() {
        val plan = ExecutionPlan(
            current.variantId, source, "renderer", ExecutionMode.NETWORK_REMOTE, PlaybackCapabilities(),
            routeRevision = 1, transitionId = TransitionId("transition"),
        )
        val remote = playing.copy(effectiveSource = source, executionPlan = plan)

        assertEquals(
            PlaybackSchedule.Partial(RemainingPlaybackTime(null, mapOf(next.entryId to 300_000L), null), emptyMap()),
            schedule(remote, listOf(current, next)),
        )
        val observed = remote.copy(executionPlan = plan.copy(capabilities = PlaybackCapabilities(canReadPosition = true)))
        assertEquals(schedule(playing), schedule(observed))
        assertEquals(PlaybackSchedule.Unknown, schedule(remote, effectiveOrder = null))
        assertEquals(PlaybackSchedule.Unknown, schedule(observed, speed = null))
        assertEquals(
            PlaybackSchedule.Unknown,
            schedule(remote.copy(executionPlan = plan.copy(mode = ExecutionMode.EXTERNAL_HANDOFF))),
        )
        assertEquals(
            PlaybackSchedule.Unknown,
            schedule(observed.copy(executionPlan = plan.copy(variantId = five.variantId))),
        )
    }

    @Test
    fun knownPositionAtOrBeyondDurationProducesZeroRemaining() {
        for (position in listOf(420_000L, 500_000L)) {
            assertEquals(
                PlaybackSchedule.Estimated(RemainingPlaybackTime(0, emptyMap(), 0), emptyMap(), now),
                schedule(playing.copy(positionMs = position), listOf(current)),
            )
        }
        assertEquals(PlaybackSchedule.Unknown, schedule(speed = 0.0))
        assertEquals(PlaybackSchedule.Unknown, schedule(speed = Double.NaN))
    }

    @Test
    fun speedsOutsideEstimateRangeReturnUnknownWithoutClockTimes() {
        for (speed in listOf(1e-20, 0.249, 4.001, Double.POSITIVE_INFINITY)) {
            assertEquals(
                "speed=$speed",
                PlaybackSchedule.Unknown,
                schedule(playing.copy(positionMs = 0), listOf(current), speed = speed),
            )
        }
        for ((speed, remainingMs) in listOf(0.25 to 1_680_000L, 4.0 to 105_000L)) {
            assertEquals(
                PlaybackSchedule.Estimated(
                    RemainingPlaybackTime(remainingMs, emptyMap(), remainingMs), emptyMap(), now + remainingMs,
                ),
                schedule(playing.copy(positionMs = 0), listOf(current), speed = speed),
            )
        }
    }

    @Test
    fun localPlaybackWithholdsScheduleUntilItObservesTheSeekPosition() = runBlocking {
        val engine = object : LocalPlaybackEngine {
            override fun prepare(entryId: QueueEntryId, document: SourceDescriptor.LocalDocument) = Unit
            override fun reset() = Unit
        }
        val playback = LocalPlayback(engine, { Availability.PLAYABLE })
        val localSource = SourceRef(SourceId("local"), SourceKind.LOCAL,
            localDocumentUri = "content://fixture/song", availability = Availability.PLAYABLE)
        assertNull(playback.start(current.entryId, seven.copy(source = localSource)))

        playback.onEngineState(PlaybackPhase.PLAYING, 60_000, 420_000, true, 1.0, now)
        assertEquals(
            PlaybackSchedule.Estimated(RemainingPlaybackTime(360_000, emptyMap(), 360_000), emptyMap(), now + 360_000),
            playback.schedule.value,
        )

        playback.onSeekRequested()
        assertNull(playback.snapshot.value.positionMs)
        assertEquals(PlaybackSchedule.Unknown, playback.schedule.value)
        playback.onEngineState(PlaybackPhase.PLAYING, 60_000, 420_000, true, 1.0, now + 1_000)
        assertNull(playback.snapshot.value.positionMs)
        assertEquals(PlaybackSchedule.Unknown, playback.schedule.value)

        playback.onEngineState(PlaybackPhase.PLAYING, 360_000, 420_000, true, 1.0, now + 2_000,
            seekPositionObserved = true)
        assertEquals(360_000L, playback.snapshot.value.positionMs)
        assertEquals(
            PlaybackSchedule.Estimated(RemainingPlaybackTime(60_000, emptyMap(), 60_000), emptyMap(), now + 62_000),
            playback.schedule.value,
        )

        playback.onEngineState(PlaybackPhase.PLAYING, 360_000, 480_000, true, 1.0, now + 3_000)
        assertEquals(
            PlaybackSchedule.Estimated(RemainingPlaybackTime(120_000, emptyMap(), 120_000), emptyMap(), now + 123_000),
            playback.schedule.value,
        )
        playback.onEngineState(PlaybackPhase.PLAYING, 360_000, 480_000, true, 2.0, now + 4_000)
        assertEquals(
            PlaybackSchedule.Estimated(RemainingPlaybackTime(60_000, emptyMap(), 60_000), emptyMap(), now + 64_000),
            playback.schedule.value,
        )
        playback.onEngineState(PlaybackPhase.PAUSED, 360_000, 480_000, true, 2.0, now + 5_000)
        assertEquals(
            PlaybackSchedule.RemainingDurationOnly(RemainingPlaybackTime(60_000, emptyMap(), 60_000)),
            playback.schedule.value,
        )
        playback.stop()
        assertEquals(PlaybackSchedule.Unknown, playback.schedule.value)
    }

    private fun schedule(
        snapshot: PlaybackSnapshot = playing,
        effectiveOrder: List<QueueEntry>? = order,
        selectedVariants: Map<VariantId, TrackVariant> = variants,
        speed: Double? = 1.0,
        repeat: QueueRepeatMode = QueueRepeatMode.OFF,
    ) = calculatePlaybackSchedule(snapshot, effectiveOrder, selectedVariants, speed, repeat, now)
}
