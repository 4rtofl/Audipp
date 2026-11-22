package com.audipp.playback

import com.audipp.data.db.qualifiesPlay
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryRecorderTest {
    private val writes = mutableListOf<PlaybackHistoryWrite>()
    private var elapsed = 0L
    private var utc = 300_000L
    private var zone = "Asia/Tokyo"
    private val recorder = PlaybackHistoryRecorder({ elapsed }, { utc }, { zone }) { writes += it }
    private val variant = TrackVariant(
        VariantId("variant"), TrackId("track"), SourceRef(SourceId("source"), SourceKind.LOCAL),
    )
    private val segments get() = writes.mapNotNull { it.segment }.associateBy { it.id }.values.toList()

    @Test
    fun seekPauseAndBufferingExcludeSkippedPositionsAndIdleElapsedTime() {
        sample(0)
        advance(100)
        recorder.discontinuity(100)
        sample(40_000)
        advance(100)
        sample(40_100, playing = false)
        advance(3_000)
        sample(40_100)
        advance(100)
        sample(40_200, playing = false) // buffering
        advance(5_000)
        sample(40_200)
        advance(100)
        sample(40_300)
        recorder.finish()

        assertEquals(listOf(0L, 40_000L, 40_100L, 40_200L), segments.map { it.sourceStartMs })
        assertEquals(listOf(100L, 40_100L, 40_200L, 40_300L), segments.map { it.sourceEndMs })
        assertEquals(400L, segments.sumOf { it.listenedMs })
        assertEquals(listOf(writes.last().event.id), segments.map { it.eventId }.distinct())
    }

    @Test
    fun speedRouteAndClockChangesCloseSegmentsAtTheObservation() {
        val unknown = HistoryOutput(0, null)
        val routed = HistoryOutput(1, "android:route", "profile")
        sample(0, output = unknown)
        advance(100)
        sample(100, speed = 2.0, output = unknown)
        advance(100)
        sample(300, speed = 2.0, output = routed)
        advance(100)
        utc += 60_000
        sample(500, speed = 2.0, output = routed)
        advance(100)
        zone = "UTC"
        sample(700, speed = 2.0, output = routed)
        advance(100)
        utc -= 50
        sample(900, speed = 2.0, output = routed, clockChanged = true)
        advance(100)
        sample(1_100, speed = 2.0, output = routed)

        assertEquals(listOf(300_000L, 300_100L, 300_200L, 360_300L, 360_400L, 360_450L),
            segments.map { it.startUtcMs })
        assertEquals(listOf(100L, 200L, 200L, 200L, 200L, 200L),
            segments.map { it.sourceEndMs - it.sourceStartMs })
        assertEquals(List(6) { 100L }, segments.map { it.listenedMs })
        assertEquals(listOf(1.0, 2.0, 2.0, 2.0, 2.0, 2.0), segments.map { it.speed })
        assertNull(segments.first().routeId)
        assertNull(segments.first().profileId)
        assertEquals("android:route", segments.last().routeId)
        assertEquals("profile", segments.last().profileId)
        assertEquals("Asia/Tokyo", segments[3].timeZone)
        assertEquals("UTC", segments.last().timeZone)
    }

    @Test
    fun repeatStartsANewAttemptAndDuplicateFinishProducesNoWrite() {
        sample(0)
        advance(100)
        sample(100, playing = false)
        advance(20)
        sample(100)
        advance(100)
        sample(200, playing = false)
        recorder.finish()
        val first = writes.last().event
        recorder.finish()
        assertEquals(3, writes.size)
        assertEquals(listOf(first.id), writes.map { it.event.id }.distinct())

        sample(0)
        advance(100)
        sample(100)
        assertNotEquals(first.id, writes.last().event.id)
        assertNull(writes.last().event.endUtcMs)
        assertEquals(100L, writes.last().segment!!.listenedMs)
    }

    @Test
    fun absentOrStalledPositionNeverAddsTime() {
        sample(null)
        advance(5_000)
        sample(100)
        advance(5_000)
        sample(100)
        advance(100)
        sample(200)
        assertEquals(100L, segments.single().listenedMs)
    }

    @Test
    fun qualificationAtHalfDurationAndFourMinutes() {
        assertFalse(qualifiesPlay(49_999, 100_000))
        assertTrue(qualifiesPlay(50_000, 100_000))
        assertFalse(qualifiesPlay(239_999, 600_000))
        assertTrue(qualifiesPlay(240_000, 600_000))
        assertFalse(qualifiesPlay(239_999, null))
        assertTrue(qualifiesPlay(240_000, null))
        assertFalse(qualifiesPlay(240_000, 0))
        assertFalse(qualifiesPlay(0, 1))
        assertFalse(qualifiesPlay(0, null))
    }

    private fun advance(ms: Long) {
        elapsed += ms
        utc += ms
    }

    private fun sample(
        positionMs: Long?,
        playing: Boolean = true,
        speed: Double = 1.0,
        output: HistoryOutput = HistoryOutput(0, null),
        clockChanged: Boolean = false,
    ) {
        recorder.observe(QueueEntryId("entry"), variant, playing, positionMs, null, speed, utc, output,
            clockChanged = clockChanged)
    }
}
