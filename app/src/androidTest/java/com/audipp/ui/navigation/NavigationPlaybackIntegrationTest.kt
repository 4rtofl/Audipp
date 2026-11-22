package com.audipp.ui.navigation

import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.audipp.R
import com.audipp.ui.nowplaying.AccessibilityRobot
import org.junit.Assert.assertTrue
import org.junit.Assume
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.MainActivity
import com.audipp.domain.model.AudioFormat
import com.audipp.domain.model.Availability
import com.audipp.domain.model.ErrorDomain
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId
import com.audipp.playback.PlaybackConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
@androidx.annotation.OptIn(UnstableApi::class)
class NavigationPlaybackIntegrationTest {
    @Test
    fun playbackBarPausesAndResumesTheServiceItemAcrossActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "Audipp navigation test.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ))
        try {
            writeSilentTrack(context, uri)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                runBlocking {
                    val model = scenario.navigationModel()
                    val connection = withContext(Dispatchers.Main) {
                        PlaybackConnection.connect(context, object : MediaController.Listener {})
                    }
                    var controller: MediaController? = null
                    try {
                        val session = connection.get(10, TimeUnit.SECONDS).also { controller = it }
                        val entry = QueueEntryId("navigation-fixture-entry")
                        val variant = TrackVariant(
                            VariantId("navigation-fixture-variant"), TrackId("navigation-fixture-track"),
                            SourceRef(SourceId("navigation-fixture-source"), SourceKind.LOCAL,
                                localDocumentUri = uri.toString(), availability = Availability.PLAYABLE),
                            format = AudioFormat(codec = "WAV"),
                        )
                        val missing = variant.copy(source = variant.source.copy(availability = Availability.MISSING))
                        val rejected = withContext(Dispatchers.Main) {
                            PlaybackConnection.playLocal(session, entry, missing)
                        }.get(10, TimeUnit.SECONDS)
                        assertEquals(ErrorDomain.SOURCE_IO, PlaybackConnection.error(rejected)?.domain)
                        withTimeout(10_000) {
                            model.playback.first { it == NavigationPlaybackState.Failed(ErrorDomain.SOURCE_IO) }
                        }

                        val result = withContext(Dispatchers.Main) {
                            PlaybackConnection.playLocal(session, entry, variant)
                        }.get(10, TimeUnit.SECONDS)
                        assertEquals(PlaybackConnection.error(result)?.code, SessionResult.RESULT_SUCCESS, result.resultCode)
                        val pause = model.controlForPhase(PlaybackPhase.PLAYING)
                        assertEquals(entry, pause.entryId)
                        withContext(Dispatchers.Main) { model.togglePlayback(pause) }
                        val resume = model.controlForPhase(PlaybackPhase.PAUSED)

                        scenario.recreate()
                        assertSame(model, scenario.navigationModel())
                        assertEquals(resume, model.controlForPhase(PlaybackPhase.PAUSED))
                        withContext(Dispatchers.Main) { model.togglePlayback(resume) }
                        assertEquals(pause, model.controlForPhase(PlaybackPhase.PLAYING))
                    } finally {
                        withContext(Dispatchers.Main) {
                            controller?.takeIf { it.isConnected }?.stop()
                            MediaController.releaseFuture(connection)
                        }
                    }
                }
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun miniPlayerPausesResumesAndExpandsWithoutSendingPlaybackRequestsAndNowPlayingSeeks() = withPlayingFixture { model, session ->
        val robot = AccessibilityRobot()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pause = context.getString(R.string.playback_pause)
        val resume = context.getString(R.string.playback_resume)
        val unknownTitle = context.getString(R.string.playback_unknown_title)

        robot.click(pause)
        val paused = model.currentIn(PlaybackPhase.PAUSED)
        // The mini player and the session's own player (what the notification is built from) agree.
        assertEquals(false, onMain { session.playWhenReady })
        robot.click(resume)
        model.currentIn(PlaybackPhase.PLAYING)
        assertEquals(true, onMain { session.isPlaying })

        robot.click(pause)
        model.currentIn(PlaybackPhase.PAUSED)
        // Expanding while paused must not resume playback or restart the item.
        robot.click(unknownTitle)
        robot.await(context.getString(R.string.now_playing_collapse))
        robot.reach(context.getString(R.string.playback_resume))
        val settled = model.currentIn(PlaybackPhase.PAUSED)
        SystemClock.sleep(1_500)
        val afterExpand = model.currentIn(PlaybackPhase.PAUSED)
        assertEquals(paused.control?.transitionId, afterExpand.control?.transitionId)
        assertEquals(settled.positionMs, afterExpand.positionMs)
        assertEquals(false, onMain { session.playWhenReady })

        // The WAV fixture reports its length, so Now Playing offers seeking and the request reaches the player.
        val bar = robot.reach("seek bar") { it.rangeInfo != null }
        assertTrue(bar.isEnabled)
        assertTrue(afterExpand.durationMs in 29_000L..31_000L)
        assertTrue(bar.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 15_000f)
        }))
        withTimeout(10_000) {
            model.playback.filterIsInstance<NavigationPlaybackState.Current>()
                .first { (it.positionMs ?: 0L) in 14_000L..16_000L }
        }
        assertEquals(PlaybackPhase.PAUSED, model.currentIn(PlaybackPhase.PAUSED).phase)

        robot.click(resume)
        model.currentIn(PlaybackPhase.PLAYING)
        robot.click(pause)
        robot.back()
        robot.gone(context.getString(R.string.now_playing_collapse))
        robot.await(unknownTitle)
    }

    @Test
    fun nowPlayingStaysOpenAcrossRotationWithTheSameItem() = withPlayingFixture { model, _ ->
        val robot = AccessibilityRobot()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        robot.click(context.getString(R.string.playback_pause))
        val before = model.currentIn(PlaybackPhase.PAUSED)
        robot.click(context.getString(R.string.playback_unknown_title))
        robot.await(context.getString(R.string.now_playing_collapse))

        scenario.rotate()
        robot.await(context.getString(R.string.now_playing_collapse))
        assertEquals(before.control?.transitionId, model.currentIn(PlaybackPhase.PAUSED).control?.transitionId)
        scenario.rotate()
        robot.await(context.getString(R.string.now_playing_collapse))
        robot.click(context.getString(R.string.now_playing_collapse))
        robot.await(context.getString(R.string.playback_resume))
    }

    @Test
    fun notificationShowsTheSameTitleAndStateAsTheMiniPlayerWhenTheSystemShowsIt() = withPlayingFixture { model, session ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val robot = AccessibilityRobot()
        // Without POST_NOTIFICATIONS (Android 13+) the system does not list the media notification.
        var shown: Notification? = null
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (shown == null && SystemClock.uptimeMillis() < deadline) {
            shown = manager.activeNotifications.firstOrNull { it.packageName == context.packageName }?.notification
            SystemClock.sleep(200)
        }
        Assume.assumeTrue("The system did not show the media notification", shown != null)
        val title = onMain { session.mediaMetadata.title?.toString() }
        assertEquals(title, shown!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val pauseAction = context.getString(androidx.media3.session.R.string.media3_controls_pause_description)
        assertTrue(shown.actions.any { it.title == pauseAction })
        robot.click(context.getString(R.string.playback_pause))
        model.currentIn(PlaybackPhase.PAUSED)
        val playAction = context.getString(androidx.media3.session.R.string.media3_controls_play_description)
        robot.await("notification shows play") {
            manager.activeNotifications.any { n -> n.notification.actions?.any { it.title == playAction } == true }
        }
    }

    private lateinit var scenario: ActivityScenario<MainActivity>

    private fun ActivityScenario<MainActivity>.rotate() {
        var orientation = Configuration.ORIENTATION_UNDEFINED
        onActivity {
            orientation = it.resources.configuration.orientation
            it.requestedOrientation = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        AccessibilityRobot().waitUntil("Activity rotates") {
            var rotated = false
            onActivity { rotated = it.resources.configuration.orientation != orientation }
            rotated
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private suspend fun NavigationViewModel.currentIn(phase: PlaybackPhase) = withTimeout(10_000) {
        playback.filterIsInstance<NavigationPlaybackState.Current>().first { it.phase == phase }
    }

    private fun withPlayingFixture(block: suspend (NavigationViewModel, MediaController) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "Audipp now playing test.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ))
        try {
            writeSilentTrack(context, uri)
            ActivityScenario.launch(MainActivity::class.java).use { launched ->
                scenario = launched
                runBlocking {
                    val model = launched.navigationModel()
                    val connection = withContext(Dispatchers.Main) {
                        PlaybackConnection.connect(context, object : MediaController.Listener {})
                    }
                    var controller: MediaController? = null
                    try {
                        val session = connection.get(10, TimeUnit.SECONDS).also { controller = it }
                        val variant = TrackVariant(
                            VariantId("now-playing-fixture-variant"), TrackId("now-playing-fixture-track"),
                            SourceRef(SourceId("now-playing-fixture-source"), SourceKind.LOCAL,
                                localDocumentUri = uri.toString(), availability = Availability.PLAYABLE),
                            format = AudioFormat(codec = "WAV", sampleRateHz = 8_000, bitDepth = 16, channelCount = 1),
                        )
                        val result = withContext(Dispatchers.Main) {
                            PlaybackConnection.playLocal(session, QueueEntryId("now-playing-fixture-entry"), variant)
                        }.get(10, TimeUnit.SECONDS)
                        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
                        model.currentIn(PlaybackPhase.PLAYING)
                        block(model, session)
                    } finally {
                        withContext(Dispatchers.Main) {
                            controller?.takeIf { it.isConnected }?.stop()
                            MediaController.releaseFuture(connection)
                        }
                    }
                }
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun ActivityScenario<MainActivity>.navigationModel(): NavigationViewModel {
        lateinit var model: NavigationViewModel
        onActivity { model = ViewModelProvider(it)[NavigationViewModel::class.java] }
        return model
    }

    private suspend fun NavigationViewModel.controlForPhase(phase: PlaybackPhase) = withTimeout(10_000) {
        val playback = playback.filterIsInstance<NavigationPlaybackState.Current>()
            .first { it.phase == phase && it.control != null }
        requireNotNull(playback.control)
    }

    private fun writeSilentTrack(context: Context, uri: Uri) {
        val sampleRate = 8_000
        val dataBytes = 30 * sampleRate * 2
        val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataBytes)
        }
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(wav.array()) }
    }
}
