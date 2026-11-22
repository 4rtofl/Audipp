package com.audipp.ui.navigation

import android.media.AudioDeviceInfo
import com.audipp.R
import com.audipp.audio.route.OutputConfirmation
import com.audipp.domain.model.PlaceholderReason
import com.audipp.domain.model.PlaybackPhase
import com.audipp.playback.OutputConfirmationRequest
import com.audipp.ui.nowplaying.formatClock
import com.audipp.ui.nowplaying.formatKilohertz
import com.audipp.ui.nowplaying.label
import com.audipp.ui.nowplaying.outputTypeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NowPlayingPresentationTest {
    private val playing = NavigationPlaybackState.Current(
        PlaybackPhase.PLAYING, "Title", "Artist", control = null,
        positionMs = 5_000, durationMs = 60_000, seekCommandAvailable = true,
    )

    @Test
    fun seekIsAvailableOnlyWithAKnownDurationAndTheSeekCommand() {
        assertEquals(SeekState.Available(5_000, 60_000), playing.seek)
        assertEquals(SeekState.Available(5_000, 60_000), playing.copy(phase = PlaybackPhase.PAUSED).seek)

        assertEquals(SeekState.Blocked(SeekBlock.UNKNOWN_DURATION, 5_000, null),
            playing.copy(durationMs = null).seek)
        assertEquals(SeekState.Blocked(SeekBlock.NOT_SEEKABLE, 5_000, 60_000),
            playing.copy(seekCommandAvailable = false).seek)
    }

    @Test
    fun seekIsBlockedWhilePreparingAndWhileTheOutputAwaitsConfirmation() {
        for (phase in listOf(PlaybackPhase.RESOLVING, PlaybackPhase.BUFFERING, PlaybackPhase.ADVANCING)) {
            assertEquals(SeekBlock.PREPARING, (playing.copy(phase = phase).seek as SeekState.Blocked).reason)
        }
        val confirmation = OutputConfirmationRequest("id", OutputConfirmation.ROUTE_CHANGED, null, null, null)
        val waiting = playing.copy(phase = PlaybackPhase.PAUSED, outputConfirmation = confirmation)
        assertEquals(SeekBlock.OUTPUT_CONFIRMATION, (waiting.seek as SeekState.Blocked).reason)
    }

    @Test
    fun silentConfirmationWaitIsDistinctFromPausedLoadingAndPlaying() {
        val confirmation = OutputConfirmationRequest("id", OutputConfirmation.ROUTE_UNKNOWN, null, null, null)
        val labels = listOf(
            playing.copy(phase = PlaybackPhase.PAUSED, outputConfirmation = confirmation).statusLabel,
            playing.copy(phase = PlaybackPhase.PAUSED).statusLabel,
            playing.copy(phase = PlaybackPhase.BUFFERING).statusLabel,
            playing.statusLabel,
        )
        assertEquals(listOf(R.string.now_playing_status_silent, R.string.playback_paused,
            R.string.playback_loading, R.string.playback_playing), labels)
    }

    @Test
    fun observedOutputIsOnlyAsSpecificAsTheReportedRoute() {
        assertEquals(ObservedOutput.Unobserved, null.toObservedOutput())
        assertEquals(ObservedOutput.Unobserved, emptyList<Int>().toObservedOutput())
        assertEquals(ObservedOutput.Single(AudioDeviceInfo.TYPE_USB_DEVICE),
            listOf(AudioDeviceInfo.TYPE_USB_DEVICE).toObservedOutput())
        assertEquals(ObservedOutput.Multiple,
            listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).toObservedOutput())
    }

    @Test
    fun routeTypesMapToLabelsWithoutClaimingADeviceOrAFormat() {
        assertEquals(R.string.now_playing_output_usb, outputTypeLabel(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(R.string.now_playing_output_bluetooth, outputTypeLabel(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(R.string.now_playing_output_speaker, outputTypeLabel(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(R.string.now_playing_output_other, outputTypeLabel(AudioDeviceInfo.TYPE_UNKNOWN))
    }

    @Test
    fun everyArtworkPlaceholderReasonHasItsOwnText() {
        val context = RuntimeEnvironment.getApplication()
        val texts = PlaceholderReason.entries.map { context.getString(it.label()) }
        assertEquals(PlaceholderReason.entries.size, texts.toSet().size)
        assertNotEquals("", texts.first())
    }

    @Test
    fun clockAndSampleRateAreReformattedWithoutRounding() {
        assertEquals("0:00", formatClock(0))
        assertEquals("1:05", formatClock(65_999))
        assertEquals("1:00:01", formatClock(3_601_000))
        assertEquals("44.1", formatKilohertz(44_100))
        assertEquals("96", formatKilohertz(96_000))
        assertEquals("11.025", formatKilohertz(11_025))
    }
}
