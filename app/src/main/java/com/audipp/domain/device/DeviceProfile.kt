package com.audipp.domain.device

import com.audipp.domain.model.EvidenceLevel
import com.audipp.domain.model.Measured
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class ComponentKind { DAC, DAP, AMP, EARPHONE, HEADPHONE }

data class DeviceComponent(
    val kind: ComponentKind,
    val id: String = UUID.randomUUID().toString(),
    val manufacturer: Measured<String>? = null,
    val product: Measured<String>? = null,
    val vendorId: Measured<Int>? = null,
    val productId: Measured<Int>? = null,
    val usbSerialHash: String? = null,
    val notes: String? = null,
    val evidence: List<RegistryEvidence> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        vendorId?.value?.let { require(it in 0..0xffff) }
        productId?.value?.let { require(it in 0..0xffff) }
    }
}

@Serializable
enum class ComponentRole { PLAYER, DAC, AMPLIFIER, TRANSDUCER }

data class ProfileComponent(val componentId: String, val role: ComponentRole) {
    init { require(componentId.isNotBlank()) }
}

data class DeviceProfile(
    val displayName: String,
    val createdAtEpochMs: Long,
    val id: String = UUID.randomUUID().toString(),
    val components: List<ProfileComponent> = emptyList(),
    val presets: List<AudioPreset> = emptyList(),
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank() && createdAtEpochMs >= 0)
        require(components.distinct().size == components.size)
        require(presets.map { it.id }.distinct().size == presets.size)
    }
}

data class AudioPreset(
    val displayName: String,
    val id: String = UUID.randomUUID().toString(),
    val revision: Long = 0,
    val settings: AudioPresetSettings = AudioPresetSettings(),
    val defaultVolume: VolumeSettings? = null,
    val initialVolume: VolumeSettings? = null,
    val safeVolume: VolumeSettings? = null,
    val physicalGainNote: String? = null,
    val impulseResponseUri: String? = null,
) {
    init { require(id.isNotBlank() && displayName.isNotBlank() && revision >= 0) }
}

@Serializable
enum class RegistryOrigin { MANUFACTURER, USER_REPORT, OS_REPORT, MEASUREMENT }

@Serializable
data class CapabilityClaim(
    val pcm: PcmFormat? = null,
    val dsdMode: DsdMode? = null,
    val dsdRateMultiplier: Int? = null,
    val dopCarrier: PcmFormat? = null,
    val supported: Boolean? = null,
) {
    init {
        require(dsdRateMultiplier == null || dsdRateMultiplier > 0)
        require(dopCarrier == null || dsdMode == DsdMode.DOP)
    }
}

@Serializable
data class MeasurementConditions(
    val appVersionCode: Long,
    val androidApi: Int,
    val settings: AudioPresetSettings,
    val procedure: String,
    val deviceAndFirmware: String? = null,
) {
    init { require(appVersionCode > 0 && androidApi > 0 && procedure.isNotBlank()) }
}

@Serializable
data class RegistryEvidence(
    val origin: RegistryOrigin,
    val claim: Measured<CapabilityClaim>,
    val conditions: MeasurementConditions? = null,
    val notes: String? = null,
) {
    init {
        require((origin == RegistryOrigin.MEASUREMENT) == (conditions != null))
        require(claim.level == EvidenceLevel.UNKNOWN || when (origin) {
            RegistryOrigin.MANUFACTURER, RegistryOrigin.USER_REPORT -> claim.level == EvidenceLevel.SOURCE
            RegistryOrigin.OS_REPORT -> claim.level == EvidenceLevel.OS_REPORTED
            RegistryOrigin.MEASUREMENT ->
                claim.level == EvidenceLevel.APP_OBSERVED || claim.level == EvidenceLevel.EXTERNAL_VERIFIED
        })
    }
}
