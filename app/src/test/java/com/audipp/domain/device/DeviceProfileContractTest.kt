package com.audipp.domain.device

import com.audipp.domain.model.EvidenceLevel
import com.audipp.domain.model.Measured
import com.audipp.domain.model.ModelJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceProfileContractTest {
    @Test
    fun checksRequestedFormatAndVolumeSeparatelyFromDeviceClaims() {
        AudioPresetSettings(pcm = PcmFormat(384_000, 32, 6))
        CapabilityClaim(
            dsdMode = DsdMode.DOP, dsdRateMultiplier = 512, dopCarrier = PcmFormat(1_411_200, 24, 2),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityClaim(dsdMode = DsdMode.NATIVE, dopCarrier = PcmFormat(705_600))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioPresetSettings(pcm = PcmFormat(705_600))
        }
        assertThrows(IllegalArgumentException::class.java) { BufferSettings(softwareMs = 50) }
        assertThrows(IllegalArgumentException::class.java) { VolumeSettings(digitalGainDb = -10.2) }
        assertThrows(IllegalArgumentException::class.java) { VolumeSettings(hardwareVolume = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            ParametricEq(40_000, listOf(ParametricBand(EqBandType.PEAK, 19_000.0, 0.0, 1.0, true)))
        }
    }

    @Test
    fun evidenceCannotClaimMeasurementWithoutMatchingProvenanceAndConditions() {
        val claim = CapabilityClaim(pcm = PcmFormat(44_100))
        val observed = Measured(claim, EvidenceLevel.APP_OBSERVED, "fixture", 0)
        assertThrows(IllegalArgumentException::class.java) {
            RegistryEvidence(RegistryOrigin.MEASUREMENT, observed)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegistryEvidence(RegistryOrigin.MANUFACTURER, observed)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegistryEvidence(
                RegistryOrigin.MEASUREMENT, Measured(claim, EvidenceLevel.SOURCE, "fixture", 0),
                MeasurementConditions(1, 23, AudioPresetSettings(), "fixture"),
            )
        }
    }

    @Test
    fun versionedSettingsPreserveNullAndRejectUnknownDocumentVersion() {
        val settings = AudioPresetSettings(pcm = PcmFormat(bitDepth = 24), dsp = DspSettings(enabled = false))
        val json = ModelJson.encode(settings)
        assertEquals(settings, ModelJson.decode<AudioPresetSettings>(json))
        assertThrows(IllegalArgumentException::class.java) {
            ModelJson.decode<AudioPresetSettings>(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        }
    }
}
