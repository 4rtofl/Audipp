package com.audipp.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audipp.audio.route.OutputConfirmation
import com.audipp.audio.route.OutputRouteMonitor
import com.audipp.audio.route.OutputSafetyState
import com.audipp.audio.route.ProfileApplication
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.device.DeviceProfileStore
import com.audipp.domain.device.AudioPreset
import com.audipp.domain.device.DeviceProfile
import com.audipp.domain.device.VolumeSettings
import com.audipp.domain.model.*
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class PlaybackServiceTest {
    private lateinit var lifecycle: ServiceController<PlaybackService>
    private lateinit var service: PlaybackService
    private val controllers = mutableListOf<MediaController>()
    private val ownApp get() = controllerInfo(Process.myUid(), true)

    @Before
    fun createService() {
        setRoutedOutput(outputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, 1))
        lifecycle = Robolectric.buildService(PlaybackService::class.java).create()
        service = lifecycle.get()
    }

    @After
    fun destroyService() {
        controllers.forEach { it.release() }
        lifecycle.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun multipleControllersShareServicePlayerAndDisconnectDoesNotDestroySession() {
        val session = service.onGetSession(ownApp)!!
        val player = session.player
        val first = connect(session.token)
        assertTrue(first.isCommandAvailable(Player.COMMAND_STOP))
        assertFalse(first.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        first.release()
        val second = connect(session.token)
        assertSame(session, service.onGetSession(ownApp))
        assertSame(player, service.onGetSession(ownApp)!!.player)
        assertTrue(second.isConnected)
        assertEquals(PlaybackPhase.IDLE, PlaybackConnection.snapshot(second.sessionExtras)!!.phase)
        assertEquals(PlaybackSchedule.Unknown, PlaybackConnection.schedule(second.sessionExtras))
        assertEquals(EvidenceLevel.OS_REPORTED, service.outputRoute.value.connectedOutputs.level)
        assertEquals(EvidenceLevel.UNKNOWN, service.outputRoute.value.observedOutputs.level)
    }

    @Test
    fun connectionPolicyAndEmptyBrowseDoNotExposeSources() {
        assertNull(service.onGetSession(controllerInfo(Process.myUid() + 1, false)))
        val session = service.onGetSession(ownApp)!!
        val callback = service.SessionCallback()
        val untrusted = callback.onConnect(session, controllerInfo(Process.myUid() + 1, false))
        assertFalse(untrusted.isAccepted)
        val trusted = controllerInfo(Process.myUid() + 1, true)
        val permissions = callback.onConnect(session, trusted)
        assertTrue(permissions.isAccepted)
        assertFalse(permissions.availableSessionCommands.contains(PlaybackConnection.playLocalCommand))
        assertFalse(permissions.availablePlayerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertFalse(permissions.availablePlayerCommands.contains(Player.COMMAND_GET_TIMELINE))
        assertEquals(SessionError.ERROR_PERMISSION_DENIED, callback.onCustomCommand(session,
            trusted, PlaybackConnection.playLocalCommand, Bundle.EMPTY).get().resultCode)
        val root = callback.onGetLibraryRoot(session, ownApp, null).get().value!!
        assertNull(root.localConfiguration)
        assertTrue(root.mediaMetadata.isBrowsable!!)
        assertTrue(callback.onGetChildren(session, ownApp, root.mediaId, 0, 50, null)
            .get().value!!.isEmpty())
        assertEquals(SessionError.ERROR_BAD_VALUE,
            callback.onGetChildren(session, ownApp, "private", 0, 1, null).get().resultCode)
    }

    @Test
    fun mediaControllerReturnsCommonPermissionErrorAndReleasesSource() {
        registerProvider("denied", DeniedProvider())
        val session = service.onGetSession(ownApp)!!
        val controller = connect(session.token)
        val result = await(PlaybackConnection.playLocal(controller, QueueEntryId("entry"), variant()))
        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
        val error = PlaybackConnection.error(result)!!
        assertEquals(ErrorDomain.PERMISSION_DENIED, error.domain)
        assertFalse(ModelJson.encode(error).contains("private"))
        shadowOf(Looper.getMainLooper()).idle()
        val snapshot = PlaybackConnection.snapshot(controller.sessionExtras)!!
        assertEquals(PlaybackPhase.ERROR, snapshot.phase)
        assertEquals(ErrorDomain.PERMISSION_DENIED, snapshot.error!!.domain)
        assertNull(snapshot.effectiveSource)
        assertEquals(0, session.player.mediaItemCount)
        assertEquals(Player.STATE_IDLE, session.player.playbackState)
    }

    @Test
    fun invalidCommandCannotPrepareArbitraryMedia() {
        val session = service.onGetSession(ownApp)!!
        val controller = connect(session.token)
        assertEquals(SessionError.ERROR_BAD_VALUE, await(controller.sendCustomCommand(
            PlaybackConnection.playLocalCommand, Bundle.EMPTY)).resultCode)
        for (args in listOf(
            Bundle().apply {
                putString(PlaybackConnection.ENTRY_ID, "x".repeat(257))
                putString(PlaybackConnection.VARIANT, ModelJson.encode(variant()))
            },
            Bundle().apply {
                putString(PlaybackConnection.ENTRY_ID, "entry")
                putString(PlaybackConnection.VARIANT, "x".repeat(16_385))
            },
        )) {
            val rejected = await(controller.sendCustomCommand(PlaybackConnection.playLocalCommand, args))
            assertEquals(SessionError.ERROR_BAD_VALUE, rejected.resultCode)
            assertEquals("INVALID_LOCAL_REQUEST", PlaybackConnection.error(rejected)!!.code)
        }
        val result = await(PlaybackConnection.playLocal(controller, QueueEntryId("x".repeat(256)),
            variant().copy(source = variant().source.copy(localDocumentUri = "https://private/song"))))
        assertEquals(SessionError.ERROR_NOT_SUPPORTED, result.resultCode)
        assertEquals(ErrorDomain.FORMAT_UNSUPPORTED, PlaybackConnection.error(result)!!.domain)
        assertEquals(0, session.player.mediaItemCount)
    }

    @Test
    fun stopFromMediaControllerCancelsPendingProviderAndCannotStartLater() {
        val opened = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        registerProvider("slow", object : DeniedProvider() {
            override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?,
                signal: CancellationSignal?):
                AssetFileDescriptor? {
                signal!!.setOnCancelListener { cancelled.countDown() }
                opened.countDown()
                check(cancelled.await(5, TimeUnit.SECONDS))
                signal.throwIfCanceled()
                return null
            }
        })
        val session = service.onGetSession(ownApp)!!
        val controller = connect(session.token)
        val slow = variant().copy(source = variant().source.copy(localDocumentUri = "content://slow/song"))
        val pending = PlaybackConnection.playLocal(controller, QueueEntryId("entry"), slow)
        val deadline = System.nanoTime() + 3_000_000_000L
        while (opened.count > 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals(0L, opened.count)
        controller.stop()
        assertEquals(SessionResult.RESULT_INFO_SKIPPED, await(pending).resultCode)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0L, cancelled.count)
        assertEquals(PlaybackPhase.IDLE, PlaybackConnection.snapshot(controller.sessionExtras)!!.phase)
        assertEquals(0, session.player.mediaItemCount)
        assertFalse(session.player.playWhenReady)
    }

    @Test
    fun sessionSeekRequiresMediaAndRejectsOutOfRangePositionsAndKeepsNewestValidSeek() {
        val session = service.onGetSession(ownApp)!!
        val controller = connect(session.token)
        assertFalse(controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        startWav(controller)
        controller.pause()
        advancePlaybackUntil {
            !rawPlayer.playWhenReady && controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }
        assertEquals(4_000L, rawPlayer.duration)

        controller.seekTo(4_000)
        // Media3 may resolve the end seek to the last millisecond inside the period.
        advancePlaybackUntil { rawPlayer.currentPosition in 3_999L..4_000L }
        controller.seekTo(0)
        advancePlaybackUntil { rawPlayer.currentPosition == 0L }
        controller.seekTo(3_000)
        controller.seekTo(1_000)
        advancePlaybackUntil { PlaybackConnection.snapshot(controller.sessionExtras)?.positionMs == 1_000L }
        controller.seekTo(-1)
        controller.seekTo(4_001)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1_000L, rawPlayer.currentPosition)
        assertEquals(1_000L, controller.currentPosition)
        assertEquals(1_000L, PlaybackConnection.snapshot(controller.sessionExtras)?.positionMs)
    }

    @Test
    @Config(shadows = [ClockedAudioTrack::class])
    fun externalOutputRemovalPausesAndReconnectionDoesNotResume() {
        val session = service.onGetSession(ownApp)!!
        val controller = connect(session.token)
        val device = outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, 2)
        val audio = shadowOf(service.getSystemService(AudioManager::class.java))
        audio.addOutputDevice(device, true)
        setRoutedOutput(device)
        startWav(controller)
        advancePlaybackUntil { service.outputSafety.value == OutputSafetyState.Ready }
        assertEquals(0f, ClockedAudioTrack.volumeAtPlay, 0f)

        audio.removeOutputDevice(device, true)
        advancePlaybackUntil { PlaybackConnection.snapshot(controller.sessionExtras)?.phase == PlaybackPhase.PAUSED }
        assertFalse(rawPlayer.playWhenReady)
        assertEquals(0f, ClockedAudioTrack.volume, 0f)
        audio.addOutputDevice(device, true)
        setRoutedOutput(outputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, 1))
        controller.play()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(rawPlayer.playWhenReady)
        assertEquals(QueueEntryId("entry"), PlaybackConnection.snapshot(controller.sessionExtras)?.currentEntryId)

        val disconnected = service.outputSafety.value as OutputSafetyState.ConfirmationRequired
        assertTrue(service.retryOutputObservation(disconnected.id))
        advancePlaybackUntil {
            (service.outputSafety.value as? OutputSafetyState.ConfirmationRequired)?.reason ==
                OutputConfirmation.ROUTE_CHANGED
        }
        assertEquals(0f, ClockedAudioTrack.volumeAtPlay, 0f)
        assertEquals(0f, ClockedAudioTrack.volume, 0f)
    }

    @Test
    fun unusedOutputRemovalDuringResolutionDoesNotCancelAutoplay() {
        val controller = connect(service.onGetSession(ownApp)!!.token)
        val opened = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val wav = registerWav {
            opened.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
        }
        val entry = QueueEntryId("entry")
        val audio = shadowOf(service.getSystemService(AudioManager::class.java))
        val unused = outputDevice(AudioDeviceInfo.TYPE_USB_DEVICE, 2)
        audio.addOutputDevice(unused, true)
        val pending = PlaybackConnection.playLocal(controller, entry, wav)
        try {
            advancePlaybackUntil { opened.count == 0L }
            assertEquals(PlaybackPhase.RESOLVING, PlaybackConnection.snapshot(controller.sessionExtras)?.phase)
            audio.removeOutputDevice(unused, true)
        } finally {
            proceed.countDown()
        }
        assertEquals(SessionResult.RESULT_SUCCESS, await(pending).resultCode)
        advancePlaybackUntil { rawPlayer.playWhenReady }
        assertEquals(entry, PlaybackConnection.snapshot(controller.sessionExtras)?.currentEntryId)
    }

    @Test
    @Config(shadows = [ClockedAudioTrack::class])
    fun actualBluetoothToWiredChangePausesEvenWhenUnusedDeviceChangesDoNot() {
        val audio = shadowOf(service.getSystemService(AudioManager::class.java))
        val bluetooth = outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 2)
        val wired = outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, 3)
        setRoutedOutput(bluetooth)
        audio.addOutputDevice(bluetooth, true)
        val controller = connect(service.onGetSession(ownApp)!!.token)
        startWav(controller)
        advancePlaybackUntil { service.outputSafety.value == OutputSafetyState.Ready }
        assertEquals(0f, ClockedAudioTrack.volumeAtPlay, 0f)
        assertTrue(ClockedAudioTrack.volume > 0f)

        audio.addOutputDevice(wired, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(rawPlayer.playWhenReady)
        assertTrue(ClockedAudioTrack.volume > 0f)

        setRoutedOutput(wired)
        ReflectionHelpers.getField<OutputRouteMonitor>(service, "routeMonitor").refreshRoute()
        assertFalse(rawPlayer.playWhenReady)
        assertEquals(0f, ClockedAudioTrack.volume, 0f)
        val pending = service.outputSafety.value as OutputSafetyState.ConfirmationRequired
        assertEquals(OutputConfirmation.ROUTE_CHANGED, pending.reason)
        assertEquals(wired.id, pending.route?.portId)
    }

    @Test
    @Config(shadows = [ClockedAudioTrack::class])
    fun usbWithoutHardwareVolumeStaysSilentThroughPlayAndReconnection() {
        val type = if (Build.VERSION.SDK_INT >= 26) AudioDeviceInfo.TYPE_USB_HEADSET
            else AudioDeviceInfo.TYPE_USB_DEVICE
        val usb = outputDevice(type, 2)
        val audio = shadowOf(service.getSystemService(AudioManager::class.java))
        audio.addOutputDevice(usb, true)
        setRoutedOutput(usb)
        val controller = connect(service.onGetSession(ownApp)!!.token)
        startWav(controller)
        advancePlaybackUntil { service.outputSafety.value is OutputSafetyState.ConfirmationRequired }
        val pending = service.outputSafety.value as OutputSafetyState.ConfirmationRequired
        assertEquals(OutputConfirmation.DAC_VOLUME_UNKNOWN, pending.reason)
        assertEquals(0f, ClockedAudioTrack.volumeAtPlay, 0f)
        controller.play()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(rawPlayer.playWhenReady)
        assertEquals(0f, ClockedAudioTrack.volume, 0f)

        audio.removeOutputDevice(usb, true)
        audio.addOutputDevice(usb, true)
        assertFalse(service.confirmOutput(pending.id, restoreDigitalVolume = false))
        val disconnected = service.outputSafety.value as OutputSafetyState.ConfirmationRequired
        assertTrue(service.retryOutputObservation(disconnected.id))
        advancePlaybackUntil {
            (service.outputSafety.value as? OutputSafetyState.ConfirmationRequired)?.reason ==
                OutputConfirmation.DAC_VOLUME_UNKNOWN
        }
        assertFalse(rawPlayer.playWhenReady)
        assertEquals(0f, ClockedAudioTrack.volume, 0f)
    }

    @Test
    @Config(shadows = [ClockedAudioTrack::class])
    fun unavailableRouteTimesOutSilentlyAndRequiresANewObservation() {
        setRoutedOutput(null)
        val controller = connect(service.onGetSession(ownApp)!!.token)
        startWav(controller)
        advancePlaybackUntil { service.outputSafety.value is OutputSafetyState.ConfirmationRequired }
        val pending = service.outputSafety.value as OutputSafetyState.ConfirmationRequired
        assertEquals(OutputConfirmation.ROUTE_UNKNOWN, pending.reason)
        assertFalse(service.confirmOutput(pending.id, restoreDigitalVolume = false))
        assertEquals(0f, ClockedAudioTrack.volumeAtPlay, 0f)
        assertEquals(0f, ClockedAudioTrack.volume, 0f)
        assertFalse(rawPlayer.playWhenReady)
    }

    @Test
    @Config(shadows = [ClockedAudioTrack::class])
    fun storedProfileAppliesItsDigitalCeilingWithoutRestoringItsHigherVolume() {
        val controller = connect(service.onGetSession(ownApp)!!.token)
        val preset = AudioPreset("Preset", initialVolume = VolumeSettings(digitalGainDb = 0.0),
            safeVolume = VolumeSettings(digitalGainDb = -12.0))
        val profile = DeviceProfile("Profile", 0, presets = listOf(preset))
        runBlocking(Dispatchers.IO) {
            DeviceProfileStore.open(service).use { it.saveProfile(profile) }
        }
        startWav(controller)
        advancePlaybackUntil { service.outputSafety.value == OutputSafetyState.Ready }

        val apply = CoroutineScope(Dispatchers.Main.immediate).async {
            service.applyOutputProfile(profile.id, preset.id)
        }
        advancePlaybackUntil { apply.isCompleted }
        assertEquals(ProfileApplication.CONFIRMATION_REQUIRED, runBlocking { apply.await() })
        assertFalse(rawPlayer.playWhenReady)
        assertEquals(0f, ClockedAudioTrack.volume, 0f)
        val pending = service.outputSafety.value as OutputSafetyState.ConfirmationRequired
        assertEquals(preset.initialVolume, pending.restore)
        assertTrue(service.confirmOutput(pending.id, restoreDigitalVolume = false))
        assertFalse(rawPlayer.playWhenReady)
        controller.play()
        advancePlaybackUntil { ClockedAudioTrack.volume > 0f }
        assertEquals(0.25119f, ClockedAudioTrack.volume, 0.00001f)
    }

    @Test
    @Config(sdk = [35])
    fun transientFocusLossResumesWhenFocusReturns() {
        val controller = connect(service.onGetSession(ownApp)!!.token)
        val audio = shadowOf(service.getSystemService(AudioManager::class.java))
        startWav(controller)
        advancePlaybackUntil { audio.lastAudioFocusRequest?.listener != null && rawPlayer.playWhenReady }
        val listener = audio.lastAudioFocusRequest.listener
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        advancePlaybackUntil {
            rawPlayer.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS &&
                rawPlayer.playbackState == Player.STATE_READY
        }
        assertTrue(rawPlayer.playWhenReady)
        assertFalse(rawPlayer.isPlaying)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        advancePlaybackUntil { rawPlayer.isPlaying }
        assertTrue(rawPlayer.playWhenReady)
    }

    @Test
    @Config(sdk = [23])
    fun transientFocusLossDuringCallClearsPlayIntentOnLegacyAndroid() {
        val controller = connect(service.onGetSession(ownApp)!!.token)
        val audio = service.getSystemService(AudioManager::class.java)
        startWav(controller)
        val shadowAudio = shadowOf(audio)
        advancePlaybackUntil { shadowAudio.lastAudioFocusRequest?.listener != null && rawPlayer.playWhenReady }
        val listener = shadowAudio.lastAudioFocusRequest.listener
        audio.mode = AudioManager.MODE_IN_CALL
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        advancePlaybackUntil { !rawPlayer.playWhenReady }
        audio.mode = AudioManager.MODE_NORMAL
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(rawPlayer.playbackLooper).idle()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(rawPlayer.playWhenReady)
        assertEquals(PlaybackPhase.PAUSED, PlaybackConnection.snapshot(controller.sessionExtras)?.phase)
    }

    @Test
    @Config(sdk = [23])
    fun focusLossBeforeCallModeChangeDoesNotResumeAfterCallOnLegacyAndroid() {
        val controller = connect(service.onGetSession(ownApp)!!.token)
        val audio = service.getSystemService(AudioManager::class.java)
        val shadowAudio = shadowOf(audio)
        startWav(controller)
        advancePlaybackUntil { shadowAudio.lastAudioFocusRequest?.listener != null && rawPlayer.playWhenReady }
        val listener = shadowAudio.lastAudioFocusRequest.listener
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        advancePlaybackUntil { PlaybackConnection.snapshot(controller.sessionExtras)?.phase == PlaybackPhase.PAUSED }

        audio.mode = AudioManager.MODE_IN_CALL
        shadowOf(Looper.getMainLooper()).idle()
        audio.mode = AudioManager.MODE_NORMAL
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(rawPlayer.playbackLooper).idle()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(rawPlayer.playWhenReady)
        assertFalse(controller.playWhenReady)
        assertEquals(PlaybackPhase.PAUSED, PlaybackConnection.snapshot(controller.sessionExtras)?.phase)
    }

    @Test
    fun becomingNoisyPausesWithCurrentSelectionRetained() {
        val controller = connect(service.onGetSession(ownApp)!!.token)
        // Media3 registers the broadcast receiver asynchronously on the playback looper.
        shadowOf(rawPlayer.playbackLooper).idle()
        startWav(controller)
        advancePlaybackUntil { rawPlayer.playWhenReady }
        service.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        advancePlaybackUntil {
            !rawPlayer.playWhenReady &&
                PlaybackConnection.snapshot(controller.sessionExtras)?.phase == PlaybackPhase.PAUSED
        }
        assertEquals(QueueEntryId("entry"), PlaybackConnection.snapshot(controller.sessionExtras)?.currentEntryId)
    }

    @Test
    @Config(sdk = [35])
    fun callModePausesAndRejectsResumeUntilCallEnds() {
        val controller = connect(service.onGetSession(ownApp)!!.token)
        val audio = service.getSystemService(AudioManager::class.java)
        val mainLooper = shadowOf(Looper.getMainLooper())
        val playbackLooper = shadowOf(rawPlayer.playbackLooper)
        // Hold preparation so the fixture cannot end while testing play intent on the main looper.
        playbackLooper.pause()
        try {
            startWav(controller)
            mainLooper.idle()
            assertTrue(rawPlayer.playWhenReady)
            assertTrue(controller.playWhenReady)

            audio.mode = AudioManager.MODE_RINGTONE
            mainLooper.idle()
            assertFalse(rawPlayer.playWhenReady)
            assertFalse(controller.playWhenReady)
            assertEquals(PlaybackPhase.PAUSED, PlaybackConnection.snapshot(controller.sessionExtras)?.phase)

            for (mode in listOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL)) {
                audio.mode = mode
                mainLooper.idle()
                controller.play()
                mainLooper.idle()
                assertFalse(rawPlayer.playWhenReady)
                assertFalse(controller.playWhenReady)
            }
            audio.mode = AudioManager.MODE_NORMAL
            mainLooper.idle()
            assertFalse(rawPlayer.playWhenReady)
            controller.play()
            mainLooper.idle()
            assertTrue(rawPlayer.playWhenReady)
            assertTrue(controller.playWhenReady)
        } finally {
            playbackLooper.unPause()
        }
    }

    @Test
    fun communicationModePreventsAutostartButAllowsExplicitPlay() {
        val controller = connect(service.onGetSession(ownApp)!!.token)
        val audio = service.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        startWav(controller)
        advancePlaybackUntil { PlaybackConnection.snapshot(controller.sessionExtras)?.phase == PlaybackPhase.PAUSED }
        assertFalse(controller.playWhenReady)
        assertFalse(rawPlayer.playWhenReady)

        controller.play()
        advancePlaybackUntil { rawPlayer.playWhenReady && controller.playWhenReady }
    }

    @Test
    @Config(sdk = [35], shadows = [ClockedAudioTrack::class])
    fun pauseResumeSeekAndStopSaveOneAttemptWithoutTheSeekGap() {
        val controller = connect(service.onGetSession(ownApp)!!.token)
        startWav(controller)
        advancePlaybackUntil { rawPlayer.isPlaying && rawPlayer.currentPosition >= 100 }
        controller.pause()
        advancePlaybackUntil { !rawPlayer.isPlaying && !controller.playWhenReady }
        val pausedAt = rawPlayer.currentPosition
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        controller.play()
        advancePlaybackUntil { rawPlayer.isPlaying && rawPlayer.currentPosition >= pausedAt + 100 }
        controller.seekTo(2_500)
        advancePlaybackUntil { rawPlayer.isPlaying && rawPlayer.currentPosition >= 2_600 }
        controller.stop()
        advancePlaybackUntil { rawPlayer.playbackState == Player.STATE_IDLE }

        val database = LibraryDatabase.open(service)
        try {
            val dao = database.playbackHistoryDao()
            val eventIds = mutableListOf<String>()
            advancePlaybackUntil {
                runBlocking(Dispatchers.IO) {
                    eventIds.clear()
                    database.openHelper.readableDatabase.query(
                        "SELECT play_event_id FROM play_events WHERE end_utc_ms IS NOT NULL",
                    ).use { cursor ->
                        while (cursor.moveToNext()) eventIds += cursor.getString(0)
                    }
                    eventIds.isNotEmpty()
                }
            }
            runBlocking {
                val event = dao.event(eventIds.single())!!
                val segments = dao.segments(event.id)
                assertEquals("entry", event.entryId)
                val beforeSeek = segments.filter { it.sourceEndMs < 2_500 }
                assertTrue(segments.toString(), beforeSeek.size >= 2)
                assertTrue(segments.toString(), segments.any { it.sourceStartMs >= 2_500 && it.sourceEndMs >= 2_600 })
                assertTrue(segments.all { it in beforeSeek || it.sourceStartMs >= 2_500 })
                assertEquals(segments.sumOf { it.listenedMs }, event.playedMs)
                assertTrue(event.playedMs.toString(), event.playedMs in 1 until 2_000)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun seekWaitsForPlaybackReadyAfterTheMaskedPosition() {
        val sampleRate = 8_000
        val pcmBytes = sampleRate * 2 * 4
        val wave = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcmBytes)
        val file = File(service.cacheDir, "seek.wav").apply { writeBytes(wave.array()) }
        registerProvider("seek", object : DeniedProvider() {
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        })
        val session = service.onGetSession(ownApp)!!
        val controller = connect(session.token)
        val audio = variant().copy(source = variant().source.copy(localDocumentUri = "content://seek/song.wav"))
        assertEquals(SessionResult.RESULT_SUCCESS,
            await(PlaybackConnection.playLocal(controller, QueueEntryId("entry"), audio)).resultCode)
        session.player.pause()

        val player = rawPlayer
        val playbackLooper = shadowOf(player.playbackLooper)
        val mainLooper = shadowOf(Looper.getMainLooper())
        advancePlaybackUntil {
            player.playbackState == Player.STATE_READY &&
                controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
                PlaybackConnection.snapshot(controller.sessionExtras)?.phase == PlaybackPhase.PAUSED
        }

        val targetMs = 3_000L
        playbackLooper.pause()
        try {
            controller.seekTo(targetMs)
            mainLooper.idle()
            assertEquals(Player.STATE_BUFFERING, player.playbackState)
            assertEquals(targetMs, player.currentPosition)
            val snapshot = PlaybackConnection.snapshot(controller.sessionExtras)!!
            assertEquals(PlaybackPhase.PAUSED, snapshot.phase)
            assertNull(snapshot.positionMs)
            assertEquals(PlaybackSchedule.Unknown, PlaybackConnection.schedule(controller.sessionExtras))
        } finally {
            playbackLooper.unPause()
        }

        advancePlaybackUntil {
            val snapshot = PlaybackConnection.snapshot(controller.sessionExtras)
            player.playbackState == Player.STATE_READY &&
                snapshot?.phase == PlaybackPhase.PAUSED && snapshot.positionMs == targetMs
        }
        assertEquals(
            PlaybackSchedule.RemainingDurationOnly(RemainingPlaybackTime(1_000, emptyMap(), 1_000)),
            PlaybackConnection.schedule(controller.sessionExtras),
        )
    }

    @Test
    fun corruptDocumentReportsFormatFailureAndClearsEngine() {
        val file = File(service.cacheDir, "corrupt-audio.bin").apply {
            writeBytes("deterministic unsupported audio fixture".toByteArray())
        }
        val opens = AtomicInteger()
        registerProvider("corrupt", object : DeniedProvider() {
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                opens.incrementAndGet()
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        })
        val session = service.onGetSession(ownApp)!!
        val controller = connect(session.token)
        val corrupt = variant().copy(source = variant().source.copy(localDocumentUri = "content://corrupt/song"))
        assertEquals(SessionResult.RESULT_SUCCESS,
            await(PlaybackConnection.playLocal(controller, QueueEntryId("entry"), corrupt)).resultCode)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (PlaybackConnection.snapshot(controller.sessionExtras)?.phase != PlaybackPhase.ERROR &&
            System.nanoTime() < deadline) {
            shadowOf(ReflectionHelpers.getField<ExoPlayer>(service, "player").playbackLooper)
                .idleFor(100, TimeUnit.MILLISECONDS)
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        val snapshot = PlaybackConnection.snapshot(controller.sessionExtras)!!
        assertEquals("snapshot=$snapshot; state=${session.player.playbackState}; opens=$opens",
            ErrorDomain.FORMAT_UNSUPPORTED, snapshot.error?.domain)
        assertEquals(0, session.player.mediaItemCount)
        assertEquals(Player.STATE_IDLE, session.player.playbackState)
        assertFalse(snapshot.toString().contains("content://"))
        file.delete()
    }

    @Test
    fun codecAndEngineErrorsAreSanitizedAndClassified() {
        val error = PlaybackException("content://private/song", IOException("secret"),
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED).toCommonError()
        assertEquals(ErrorDomain.FORMAT_UNSUPPORTED, error.domain)
        assertFalse(error.retryable)
        assertFalse(ModelJson.encode(error).contains("private"))
        assertEquals(ErrorDomain.PERMISSION_DENIED, PlaybackException("private", null,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION).toCommonError().domain)
        assertEquals(ErrorDomain.SOURCE_IO, PlaybackException("private", null,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND).toCommonError().domain)
        assertEquals(ErrorDomain.PERMISSION_DENIED, PlaybackException("private", SecurityException("private"),
            PlaybackException.ERROR_CODE_UNSPECIFIED).toCommonError().domain)
    }

    private fun connect(token: androidx.media3.session.SessionToken): MediaController =
        await(MediaController.Builder(service, token).buildAsync()).also { controllers += it }

    private val rawPlayer: ExoPlayer get() = ReflectionHelpers.getField(service, "player")

    private fun advancePlaybackUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(rawPlayer.playbackLooper).idleFor(20, TimeUnit.MILLISECONDS)
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        assertTrue("condition timed out, state=${rawPlayer.playbackState}, position=${rawPlayer.currentPosition}",
            condition())
    }

    private fun startWav(controller: MediaController) {
        val result = await(PlaybackConnection.playLocal(controller, QueueEntryId("entry"), registerWav()))
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
    }

    private fun setRoutedOutput(device: AudioDeviceInfo?) {
        ShadowAudioTrack.setRoutedDevice(device)
        ClockedAudioTrack.routedOutput = device
    }

    private fun outputDevice(type: Int, id: Int): AudioDeviceInfo {
        val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        val port = ReflectionHelpers.getField<Any>(device, "mPort")
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(port, "mHandle"), "mId", id)
        ReflectionHelpers.setField(port, "mRole", 2) // AudioPort.ROLE_SINK
        ReflectionHelpers.setField(port, "mName", "Fixture")
        ReflectionHelpers.setField(port, "mSamplingRates", intArrayOf(44_100))
        ReflectionHelpers.setField(port, "mChannelMasks", intArrayOf(AudioFormat.CHANNEL_OUT_MONO))
        ReflectionHelpers.setField(port, "mChannelIndexMasks", intArrayOf())
        ReflectionHelpers.setField(port, "mFormats", intArrayOf(AudioFormat.ENCODING_PCM_16BIT))
        assertTrue(device.isSink)
        return device
    }

    private fun registerWav(beforeOpen: () -> Unit = {}): TrackVariant {
        val sampleRate = 8_000
        val pcmBytes = 4 * sampleRate * Short.SIZE_BYTES
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * Short.SIZE_BYTES)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcmBytes).array()
        val file = File(service.cacheDir, "four-seconds.wav").apply { writeBytes(wav) }
        registerProvider("wav", object : DeniedProvider() {
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                beforeOpen()
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        })
        val source = variant().source.copy(localDocumentUri = "content://wav/song")
        return variant().copy(source = source)
    }

    private fun registerProvider(authority: String, provider: ContentProvider) {
        provider.attachInfo(service, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    private fun <T> await(future: ListenableFuture<T>): T {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!future.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Media3 response timed out", future.isDone)
        return future.get()
    }

    // The default shadow treats all written PCM as heard immediately, even for a four-second WAV.
    @Implements(AudioTrack::class)
    class ClockedAudioTrack : ShadowAudioTrack() {
        companion object {
            @Volatile var routedOutput: AudioDeviceInfo? = null
            @Volatile var volumeAtPlay = 1f
            @Volatile var volume = 1f
        }

        @RealObject private lateinit var track: AudioTrack
        private var position = 0L
        private var sampledAt = 0L

        @Implementation
        override fun play() {
            volumeAtPlay = volume
            sampledAt = SystemClock.elapsedRealtime()
            super.play()
        }

        @Implementation
        fun setVolume(gain: Float): Int {
            volume = gain
            return AudioTrack.SUCCESS
        }

        @Implementation
        override fun getRoutedDevice(): AudioDeviceInfo? = routedOutput

        @Implementation(minSdk = 23, maxSdk = 28)
        override fun native_write_native_bytes(
            data: Any, offset: Int, size: Int, format: Int, blocking: Boolean,
        ): Int = super.native_write_native_bytes(data, offset, writableBytes(size), format, blocking)

        @Implementation(minSdk = 29)
        override fun native_write_native_bytes(
            data: ByteBuffer, offset: Int, size: Int, format: Int, blocking: Boolean,
        ): Int = super.native_write_native_bytes(data, offset, writableBytes(size), format, blocking)

        private fun writableBytes(size: Int): Int {
            val queued = super.getPlaybackHeadPosition() - getPlaybackHeadPosition()
            val capacity = track.sampleRate / 4
            return minOf(size, (capacity - queued).coerceAtLeast(0) * Short.SIZE_BYTES)
        }

        @Implementation
        override fun getPlaybackHeadPosition(): Int {
            val now = SystemClock.elapsedRealtime()
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val frames = (now - sampledAt) * track.sampleRate / 1_000
                position = (position + frames).coerceAtMost(super.getPlaybackHeadPosition().toLong())
            }
            sampledAt = now
            return position.toInt()
        }

        @Implementation
        override fun flush() {
            super.flush()
            position = 0
        }
    }

    private fun variant() = TrackVariant(VariantId("variant"), TrackId("track"),
        SourceRef(SourceId("source"), SourceKind.LOCAL,
            localDocumentUri = "content://denied/private-song", availability = Availability.PLAYABLE))

    private fun controllerInfo(uid: Int, trusted: Boolean) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo("com.audipp", Process.myPid(),
            uid, 0, 0, trusted, Bundle.EMPTY, true)

    open class DeniedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/wav"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? =
            throw SecurityException("private URI must never leave provider")
    }
}
