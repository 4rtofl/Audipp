package com.audipp.audio.route

import android.media.AudioDeviceInfo
import com.audipp.domain.device.AudioPreset
import com.audipp.domain.device.AudioPresetSettings
import com.audipp.domain.device.DspSettings
import com.audipp.domain.device.EqBandType
import com.audipp.domain.device.OutputBackend
import com.audipp.domain.device.ParametricBand
import com.audipp.domain.device.ParametricEq
import com.audipp.domain.device.VolumeSettings
import org.junit.Assert.*
import org.junit.Test

class OutputSafetyTest {
    private val bluetooth = OutputEndpoint(1, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "BT")
    private val wired = OutputEndpoint(2, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Wired")
    private val speaker = OutputEndpoint(3, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker")
    private var androidStep = 4
    private val events = mutableListOf<String>()
    private val gains = mutableListOf<Float>()
    private val safety = OutputSafety({ androidStep }) { events += "pause" }
    private val pending get() = safety.state.value as OutputSafetyState.ConfirmationRequired

    @Test
    fun pendingHighGainIsDiscardedOnRerouteAndDoesNotCarryOverAfterConfirmation() {
        observe(bluetooth)
        val preset = AudioPreset("Preset", initialVolume = VolumeSettings(digitalGainDb = 0.0),
            safeVolume = VolumeSettings(digitalGainDb = -12.0))
        assertEquals(ProfileApplication.CONFIRMATION_REQUIRED, apply(preset))
        val original = pending
        assertEquals(0.0, original.restore?.digitalGainDb)
        observe(wired)
        assertEquals(OutputConfirmation.ROUTE_CHANGED, pending.reason)
        assertNull(pending.restore)
        assertFalse(safety.confirm(original.id, restoreDigitalVolume = true))
        assertEquals(0f, gains.last(), 0f)
        assertFalse(safety.canPlay())

        assertTrue(safety.confirm(pending.id, restoreDigitalVolume = false))
        assertEquals(0f, gains.last(), 0f)
        observe(wired)
        assertEquals(0.25119f, gains.last(), 0.00001f)
    }

    @Test
    fun ambiguousEndpointsAndLossOfObservationNeverOpenTheGate() {
        observe(bluetooth)
        observe(wired, speaker)
        val ambiguous = pending
        assertEquals(OutputConfirmation.ROUTE_UNKNOWN, ambiguous.reason)
        assertNull(ambiguous.route)
        assertEquals(0f, gains.last(), 0f)
        assertFalse(safety.confirm(ambiguous.id, restoreDigitalVolume = false))
        assertTrue(safety.retryObservation(ambiguous.id))
        observe(OutputEndpoint(4, AudioDeviceInfo.TYPE_UNKNOWN, null))
        assertEquals(ambiguous.id, pending.id)
        observe()
        assertFalse(safety.canPlay())
        assertEquals(0f, gains.last(), 0f)
    }

    @Test
    fun firstRetryFindingUsbStillRequiresManualVolumeConfirmation() {
        observe()
        assertTrue(safety.retryObservation(pending.id))
        observe(OutputEndpoint(4, AudioDeviceInfo.TYPE_USB_DEVICE, "USB"))
        assertEquals(OutputConfirmation.DAC_VOLUME_UNKNOWN, pending.reason)
        assertEquals(0f, gains.last(), 0f)
        assertFalse(safety.canPlay())
    }

    @Test
    fun aRecreatedSafetyStateDoesNotAcceptItsPredecessorsConfirmation() {
        observe()
        val oldId = pending.id
        val recreated = OutputSafety({ androidStep }) {}
        recreated.onObservation(null) {}
        assertFalse(recreated.retryObservation(oldId))
    }

    @Test
    fun gainConfirmedBeforeAProbeIsDiscardedIfTheProbeFindsAnotherRoute() {
        observe(bluetooth)
        assertEquals(ProfileApplication.APPLIED,
            apply(AudioPreset("Quiet", safeVolume = VolumeSettings(digitalGainDb = -12.0))))
        observe(bluetooth)
        assertEquals(ProfileApplication.CONFIRMATION_REQUIRED,
            apply(AudioPreset("Louder", initialVolume = VolumeSettings(digitalGainDb = 0.0))))
        assertTrue(safety.confirm(pending.id, restoreDigitalVolume = true))
        observe(wired)
        assertEquals(0f, gains.last(), 0f)
        assertTrue(safety.confirm(pending.id, restoreDigitalVolume = false))
        observe(wired)
        assertEquals(0.25119f, gains.last(), 0.00001f)
    }

    @Test
    fun checksRouteAndMutesBeforeBackendThenChecksSettingsBeforeRequestingVolumeRestore() {
        val preset = AudioPreset("Preset", settings = AudioPresetSettings(outputBackend = OutputBackend.ANDROID_STANDARD),
            initialVolume = VolumeSettings(digitalGainDb = 0.0, androidVolumeStep = 8,
                hardwareVolume = -3.0, hardwareVolumeUnit = "dB"),
            safeVolume = VolumeSettings(digitalGainDb = -12.0, androidVolumeStep = 3),
            physicalGainNote = "Manual low gain")
        assertEquals(ProfileApplication.STALE_ROUTE, apply(preset))
        assertTrue(events.isEmpty())
        observe(wired)
        events.clear()
        val revision = safety.revision
        assertEquals(ProfileApplication.CONFIRMATION_REQUIRED, apply(preset))
        assertEquals(listOf("gain=0.0", "pause", "backend=ANDROID_STANDARD"), events)
        assertEquals(preset.initialVolume, pending.restore)
        assertEquals(preset.safeVolume, pending.limit)
        assertEquals("Manual low gain", pending.physicalGainNote)
        assertEquals(ProfileApplication.STALE_ROUTE, safety.applyProfile(revision, preset) {
            error("Stale request reached the backend")
        })
        assertFalse(safety.confirm(pending.id, restoreDigitalVolume = true))
        androidStep = 3
        assertTrue(safety.confirm(pending.id, restoreDigitalVolume = true))
        observe(wired)
        assertEquals(0.25119f, gains.last(), 0.00001f)
        androidStep = 4
        assertFalse(safety.canPlay())
        assertEquals(0f, gains.last(), 0f)
    }

    @Test
    fun rejectsUnsupportedBackendAndExtremeIrEqWithoutRestoringGain() {
        observe(wired)
        val maxEq = ParametricEq(48_000, List(20) {
            ParametricBand(EqBandType.PEAK, 1_000.0, 12.0, 1.0, true)
        }, enabled = true)
        val base = AudioPreset("Extreme", initialVolume = VolumeSettings(digitalGainDb = 0.0))
        val presets = listOf(
            base.copy(settings = AudioPresetSettings(outputBackend = OutputBackend.CUSTOM_USB)),
            base.copy(impulseResponseUri = "content://test/extreme-ir"),
            base.copy(settings = AudioPresetSettings(dsp = DspSettings(enabled = true, eq = maxEq))),
        )
        for (preset in presets) {
            events.clear()
            assertEquals(ProfileApplication.UNSUPPORTED, apply(preset))
            assertEquals("gain=0.0", events.first())
            assertEquals("backend=${preset.settings.outputBackend ?: OutputBackend.ANDROID_STANDARD}", events.last())
            assertEquals(OutputSafetyState.Ready, safety.state.value)
            assertEquals(0f, gains.last(), 0f)
        }
    }

    private fun observe(vararg routes: OutputEndpoint) =
        safety.onObservation(routes.toList().ifEmpty { null }) {
            gains += it
            events += "gain=$it"
        }

    private fun apply(preset: AudioPreset) = safety.applyProfile(safety.revision, preset) {
        events += "backend=$it"
        it == OutputBackend.ANDROID_STANDARD
    }
}
