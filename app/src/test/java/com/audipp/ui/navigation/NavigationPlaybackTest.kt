package com.audipp.ui.navigation

import com.audipp.domain.model.Availability
import com.audipp.domain.model.ExecutionMode
import com.audipp.domain.model.ExecutionPlan
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.PlaybackSnapshot
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.VariantId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationPlaybackTest {
    private val source = SourceRef(SourceId("source"), SourceKind.LOCAL, availability = Availability.PLAYABLE)
    private val entry = QueueEntryId("entry")
    private val plan = ExecutionPlan(
        VariantId("variant"), source, "device", ExecutionMode.LOCAL_DIRECT,
        PlaybackCapabilities(canPlay = true, canPause = true), 0, TransitionId("transition"),
    )
    private val paused = PlaybackSnapshot(
        0, PlaybackPhase.PAUSED, currentEntryId = entry, effectiveSource = source, executionPlan = plan,
    )

    @Test
    fun sharedControlFollowsTheObservedPhaseAndSessionCapabilities() {
        assertEquals(PlaybackControl(entry, plan.transitionId, pause = false), paused.controlFor(entry.value, true))
        val playing = paused.copy(phase = PlaybackPhase.PLAYING)
        assertEquals(PlaybackControl(entry, plan.transitionId, pause = true), playing.controlFor(entry.value, true))

        val unableToResume = paused.copy(executionPlan = plan.copy(capabilities = PlaybackCapabilities(canPause = true)))
        val unableToPause = playing.copy(executionPlan = plan.copy(capabilities = PlaybackCapabilities(canPlay = true)))
        assertNull(unableToResume.controlFor(entry.value, true))
        assertNull(unableToPause.controlFor(entry.value, true))
        assertNull(paused.controlFor(entry.value, false))
    }

    @Test
    fun noValidActionDuringPreparationFailureOrWhenTheMediaItemDoesNotMatch() {
        for (phase in PlaybackPhase.entries - setOf(PlaybackPhase.PLAYING, PlaybackPhase.PAUSED)) {
            assertNull(paused.copy(phase = phase).controlFor(entry.value, true))
        }
        assertNull(paused.controlFor("old", true))
        assertNull(paused.controlFor(null, true))
        assertNull(paused.copy(executionPlan = null).controlFor(entry.value, true))
        val missing = source.copy(availability = Availability.MISSING)
        assertNull(paused.copy(effectiveSource = missing, executionPlan = plan.copy(source = missing))
            .controlFor(entry.value, true))
    }

    @Test
    fun aNewTransitionInvalidatesAnOldActionEvenWhenItReusesTheQueueEntry() {
        val replacement = paused.copy(executionPlan = plan.copy(transitionId = TransitionId("next")))
        assertNotEquals(paused.controlFor(entry.value, true), replacement.controlFor(entry.value, true))
    }

    @Test
    fun aPreparedItemCanResumeAfterARouteChangeInvalidatesItsPlan() {
        val retained = paused.copy(executionPlan = null)
        assertEquals(PlaybackControl(entry, plan.transitionId, pause = false),
            retained.controlFor(entry.value, true, preparedTransition = plan.transitionId))
        assertNull(retained.controlFor("different-entry", true, preparedTransition = plan.transitionId))
        assertNull(retained.controlFor(entry.value, false, preparedTransition = plan.transitionId))
    }
}
