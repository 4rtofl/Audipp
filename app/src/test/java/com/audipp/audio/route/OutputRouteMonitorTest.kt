package com.audipp.audio.route

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.audipp.domain.model.BitPerfectState
import com.audipp.domain.model.BitPerfectVerification
import com.audipp.domain.model.EvidenceLevel
import com.audipp.domain.model.Measured
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutputRouteMonitorTest {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
    private val speaker = device(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker")
    private val usb = device(2, AudioDeviceInfo.TYPE_USB_DEVICE, "USB")
    private val headphones = device(3, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Wired")
    private val bluetooth = device(4, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Bluetooth")

    @Test
    fun inventoryAndCapabilitiesDoNotStandInForTheActiveRoute() {
        shadowOf(manager).setOutputDevices(listOf(bluetooth, usb, headphones, speaker))
        OutputRouteMonitor(manager, handler) {}.use { monitor ->
            val idle = monitor.snapshot.value
            assertEquals(EvidenceLevel.OS_REPORTED, idle.connectedOutputs.level)
            assertEquals(listOf(speaker.type, usb.type, headphones.type, bluetooth.type),
                idle.connectedOutputs.value!!.map { it.endpoint.type })
            assertEquals(listOf(48_000), idle.connectedOutputs.value[1].sampleRatesHz)
            assertEquals(EvidenceLevel.UNKNOWN, idle.observedOutputs.level)

            val track = RoutedTrack().apply { devices = listOf(speaker) }
            monitor.onPlaying(track, track)
            shadowOf(handler.looper).idle()
            val playing = monitor.snapshot.value
            assertSame(idle.connectedOutputs, playing.connectedOutputs)
            assertEquals(EvidenceLevel.APP_OBSERVED, playing.observedOutputs.level)
            assertEquals(listOf(speaker.id), playing.observedOutputs.value!!.map { it.portId })
            assertEquals(idle.route.routeRevision, playing.route.routeRevision)
            assertEquals(EvidenceLevel.UNKNOWN, playing.route.sampleRateHz.level)
            assertEquals(BitPerfectState.NOT_VERIFIED, playing.route.bitPerfectState)
        }
    }

    @Test
    fun routeChangesInvalidateVerificationAndPreserveEveryEndpointOfAMultipleRoute() {
        val revisions = mutableListOf<Long>()
        shadowOf(manager).setOutputDevices(listOf(speaker))
        OutputRouteMonitor(manager, handler, queryRoutedDevices = { (it as RoutedTrack).devices }) {
            revisions += it
        }.use { monitor ->
            val track = RoutedTrack().apply { devices = listOf(speaker) }
            monitor.onPlaying(track, track)
            shadowOf(handler.looper).idle()
            val original = monitor.snapshot.value.route
            assertTrue(revisions.isEmpty())
            val verification = BitPerfectVerification(
                Measured(true, EvidenceLevel.EXTERNAL_VERIFIED, "fixture", 0, "fixture"),
                original.deviceName.value!!, original.routeId!!, original.routeRevision,
                com.audipp.domain.model.AudioFormat("PCM", 48_000, 16, 2), "fixture",
            )
            shadowOf(manager).addOutputDevice(usb, true)
            val afterUsbConnect = monitor.snapshot.value.route
            assertEquals(original.routeId, afterUsbConnect.routeId)
            assertEquals(original.deviceName, afterUsbConnect.deviceName)
            assertEquals(listOf(1L), revisions)
            assertThrows(IllegalArgumentException::class.java) {
                afterUsbConnect.copy(
                    sampleRateHz = Measured(48_000, EvidenceLevel.EXTERNAL_VERIFIED, "fixture", 0, "fixture"),
                    bitDepth = Measured(16, EvidenceLevel.EXTERNAL_VERIFIED, "fixture", 0, "fixture"),
                    channelCount = Measured(2, EvidenceLevel.EXTERNAL_VERIFIED, "fixture", 0, "fixture"),
                    dspEnabled = false, bitPerfectState = BitPerfectState.VERIFIED, verification = verification,
                )
            }

            track.devices = listOf(usb, speaker, usb)
            track.notifyRouteChanged()
            val multi = monitor.snapshot.value
            assertEquals(listOf(speaker.id, usb.id), multi.observedOutputs.value!!.map { it.portId })
            assertEquals(EvidenceLevel.UNKNOWN, multi.route.deviceName.level)
            assertEquals(listOf(1L, 2L), revisions)
            track.devices = listOf(speaker, usb)
            track.notifyRouteChanged()
            assertSame(multi, monitor.snapshot.value)
        }
    }

    @Test
    fun pausingAndResumingOnTheSameRouteDoesNotAdvanceRevision() {
        val revisions = mutableListOf<Long>()
        OutputRouteMonitor(null, handler) { revisions += it }.use { monitor ->
            val track = RoutedTrack().apply { devices = listOf(speaker) }
            monitor.onPlaying(track, track)
            shadowOf(handler.looper).idle()
            val playing = monitor.snapshot.value.route
            assertNotNull(playing.routeId)
            val beforePause = revisions.toList()

            monitor.onInactive(track)
            shadowOf(handler.looper).idle()
            assertEquals(EvidenceLevel.UNKNOWN, monitor.snapshot.value.observedOutputs.level)

            monitor.onPlaying(track, track)
            shadowOf(handler.looper).idle()
            assertEquals(playing.routeId, monitor.snapshot.value.route.routeId)
            assertEquals(playing.routeRevision, monitor.snapshot.value.route.routeRevision)
            assertEquals(beforePause, revisions)
        }
    }

    @Test
    fun missingTrackDeniedQueryAndLateCallbacksDoNotPublishStaleEvidence() {
        val revisions = mutableListOf<Long>()
        val monitor = OutputRouteMonitor(null, handler) { revisions += it }
        val hidden = Any()
        monitor.onPlaying(hidden, null)
        shadowOf(handler.looper).idle()
        assertEquals(EvidenceLevel.UNKNOWN, monitor.snapshot.value.connectedOutputs.level)
        assertEquals(EvidenceLevel.UNKNOWN, monitor.snapshot.value.observedOutputs.level)

        val first = RoutedTrack().apply { devices = listOf(speaker) }
        monitor.onPlaying(first, first)
        shadowOf(handler.looper).idle()
        val staleListener = first.listener!!
        val replacement = RoutedTrack().apply { devices = listOf(usb) }
        monitor.onPlaying(replacement, replacement)
        monitor.onInactive(first)
        shadowOf(handler.looper).idle()
        staleListener.onRoutingChanged(first)
        assertEquals(listOf(usb.id), monitor.snapshot.value.observedOutputs.value!!.map { it.portId })
        assertNull(first.listener)

        replacement.denied = true
        replacement.notifyRouteChanged()
        assertEquals(EvidenceLevel.UNKNOWN, monitor.snapshot.value.observedOutputs.level)
        replacement.denied = false
        replacement.notifyRouteChanged()
        assertEquals(EvidenceLevel.APP_OBSERVED, monitor.snapshot.value.observedOutputs.level)
        assertEquals(listOf(1L, 2L, 3L), revisions)

        monitor.close()
        assertNull(replacement.listener)
        monitor.onPlaying(first, first)
        shadowOf(handler.looper).idle()
        assertEquals(EvidenceLevel.UNKNOWN, monitor.snapshot.value.observedOutputs.level)
        assertEquals(3L, monitor.snapshot.value.route.routeRevision)
    }

    private fun device(id: Int, type: Int, name: String): AudioDeviceInfo {
        val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        val port = ReflectionHelpers.getField<Any>(device, "mPort")
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(port, "mHandle"), "mId", id)
        ReflectionHelpers.setField(port, "mRole", 2) // android.media.AudioPort.ROLE_SINK
        ReflectionHelpers.setField(port, "mName", name)
        ReflectionHelpers.setField(port, "mSamplingRates", intArrayOf(48_000))
        ReflectionHelpers.setField(port, "mChannelMasks", intArrayOf(AudioFormat.CHANNEL_OUT_STEREO))
        ReflectionHelpers.setField(port, "mChannelIndexMasks", intArrayOf())
        ReflectionHelpers.setField(port, "mFormats", intArrayOf(AudioFormat.ENCODING_PCM_16BIT))
        return device
    }

    @Suppress("DEPRECATION")
    private class RoutedTrack : AudioTrack(AudioManager.STREAM_MUSIC, 44_100, AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT, 8_192, MODE_STREAM) {
        var devices: List<AudioDeviceInfo> = emptyList()
        var denied = false
        var listener: AudioRouting.OnRoutingChangedListener? = null

        override fun getPlayState() = PLAYSTATE_PLAYING
        override fun getRoutedDevice(): AudioDeviceInfo? {
            if (denied) throw SecurityException("fixture")
            return devices.singleOrNull()
        }
        override fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener, handler: Handler?) {
            this.listener = listener
        }
        override fun removeOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener) {
            assertSame(this.listener, listener)
            this.listener = null
        }
        fun notifyRouteChanged() = requireNotNull(listener).onRoutingChanged(this)
    }
}
