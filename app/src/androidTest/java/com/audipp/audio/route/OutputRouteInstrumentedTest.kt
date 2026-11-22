package com.audipp.audio.route

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.domain.model.EvidenceLevel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class OutputRouteInstrumentedTest {
    private lateinit var monitor: OutputRouteMonitor
    private lateinit var player: ExoPlayer

    @Test
    fun standardPlayerObservesItsOwnTrackAcrossPauseAndResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val wav = File.createTempFile("output-route", ".wav", context.cacheDir)
        val safety = OutputSafety({ 0 }) { player.pause() }
        try {
            wav.writeBytes(silentWav())
            instrumentation.runOnMainSync {
                monitor = OutputRouteMonitor(context, safety) {}
                assertEquals(EvidenceLevel.OS_REPORTED, monitor.snapshot.value.connectedOutputs.level)
                assertEquals(EvidenceLevel.UNKNOWN, monitor.snapshot.value.observedOutputs.level)
                player = ExoPlayer.Builder(context)
                    .setAudioOutputProvider(ObservedAudioOutputProvider(context, monitor))
                    .build()
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(wav)))
                player.prepare()
                player.play()
            }
            awaitCondition("The player's AudioTrack never exposed a routed output") {
                instrumentation.runOnMainSync { monitor.refreshRoute() }
                monitor.snapshot.value.observedOutputs.level == EvidenceLevel.APP_OBSERVED &&
                    safety.state.value == OutputSafetyState.Ready
            }
            val playing = monitor.snapshot.value
            assertTrue(playing.observedOutputs.value!!.isNotEmpty())
            assertEquals(if (Build.VERSION.SDK_INT >= 36) "AudioTrack.getRoutedDevices" else
                "AudioTrack.getRoutedDevice", playing.observedOutputs.sourceName)
            assertEquals(EvidenceLevel.UNKNOWN, playing.route.sampleRateHz.level)

            instrumentation.runOnMainSync { player.pause() }
            awaitCondition("Paused output retained a live route observation") {
                monitor.snapshot.value.observedOutputs.level == EvidenceLevel.UNKNOWN
            }
            assertEquals(playing.route.routeRevision, monitor.snapshot.value.route.routeRevision)

            instrumentation.runOnMainSync { player.play() }
            awaitCondition("Resumed output did not restore the live route observation") {
                instrumentation.runOnMainSync { monitor.refreshRoute() }
                monitor.snapshot.value.observedOutputs.level == EvidenceLevel.APP_OBSERVED
            }
            assertEquals(playing.route.routeId, monitor.snapshot.value.route.routeId)
            assertEquals(playing.route.routeRevision, monitor.snapshot.value.route.routeRevision)
        } finally {
            instrumentation.runOnMainSync {
                if (::player.isInitialized) player.release()
                if (::monitor.isInitialized) monitor.close()
            }
            wav.delete()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 36)
    fun api36ReadsEveryDeviceThroughThePluralTrackApi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(AudioManager::class.java)!!
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).take(2)
        assumeTrue("Two output ports are needed for the plural API fixture", devices.size == 2)
        var fixture: MultipleRouteTrack? = null
        try {
            instrumentation.runOnMainSync {
                monitor = OutputRouteMonitor(context, OutputSafety({ 0 }) {}) {}
                fixture = MultipleRouteTrack(devices.reversed()).also { monitor.onPlaying(it, it) }
            }
            instrumentation.waitForIdleSync()
            assertEquals(devices.map { it.id }.sorted(),
                monitor.snapshot.value.observedOutputs.value?.map { it.portId })
            assertEquals(EvidenceLevel.UNKNOWN, monitor.snapshot.value.route.deviceName.level)
        } finally {
            instrumentation.runOnMainSync {
                if (::monitor.isInitialized) monitor.close()
                fixture?.release()
            }
        }
    }

    @Suppress("DEPRECATION")
    private class MultipleRouteTrack(private val devices: List<AudioDeviceInfo>) : AudioTrack(
        AudioManager.STREAM_MUSIC, 44_100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        getMinBufferSize(44_100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8_192),
        MODE_STREAM,
    ) {
        override fun getPlayState() = PLAYSTATE_PLAYING
        override fun getRoutedDevice(): AudioDeviceInfo = error("API 36 must not query a single route")
        override fun getRoutedDevices() = devices
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            assertTrue(message, SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(30)
        }
    }

    private fun silentWav(): ByteArray {
        val sampleRate = 44_100
        val dataSize = sampleRate * 2 * 20
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize)
        }.array()
    }
}
