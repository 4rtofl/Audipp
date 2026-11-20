package com.audipp.domain.device

import kotlinx.serialization.Serializable

@Serializable
data class SharedDeviceProfile(
    val components: List<SharedDeviceComponent>,
    val presets: List<AudioPresetSettings>,
)

@Serializable
data class SharedDeviceComponent(
    val role: ComponentRole,
    val kind: ComponentKind,
    val vendorId: Int?,
    val productId: Int?,
    val reports: List<SharedRegistryReport>,
)

@Serializable
data class SharedRegistryReport(
    val origin: RegistryOrigin,
    val claim: CapabilityClaim?,
    val conditions: SharedMeasurementConditions?,
)

@Serializable
data class SharedMeasurementConditions(
    val appVersionCode: Long,
    val androidApi: Int,
    val settings: AudioPresetSettings,
)
