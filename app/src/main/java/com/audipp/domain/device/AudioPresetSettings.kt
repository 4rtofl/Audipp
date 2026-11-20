package com.audipp.domain.device

import kotlinx.serialization.Serializable

@Serializable
enum class OutputTarget { SPEAKER, WIRED, USB, BLUETOOTH, NETWORK }

@Serializable
enum class OutputBackend { ANDROID_STANDARD, ANDROID_DIRECT, CUSTOM_USB }

@Serializable
enum class OutputMode { STANDARD, PROCESSED, DIRECT_ATTEMPT }

@Serializable
enum class DsdMode { NATIVE, DOP, TO_PCM, FROM_PCM }

@Serializable
enum class DsdRate { DSD64, DSD128, DSD256 }

@Serializable
data class PcmFormat(
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
) {
    init {
        require(sampleRateHz == null || sampleRateHz > 0)
        require(bitDepth == null || bitDepth > 0)
        require(channels == null || channels > 0)
    }
}

// Shared reports include these settings, so local references belong in AudioPreset.
@Serializable
data class AudioPresetSettings(
    val outputTarget: OutputTarget? = null,
    val outputMode: OutputMode? = null,
    val outputBackend: OutputBackend? = null,
    val pcm: PcmFormat? = null,
    val dsdMode: DsdMode? = null,
    val dsdRate: DsdRate? = null,
    val resampler: ResamplerSettings? = null,
    val dsp: DspSettings? = null,
    val buffer: BufferSettings? = null,
) {
    init {
        require(pcm?.sampleRateHz == null || pcm.sampleRateHz <= 384_000)
        require(pcm?.bitDepth == null || pcm.bitDepth <= 32)
        require(pcm?.channels == null || pcm.channels <= 6)
    }
}

@Serializable
enum class ResamplerEngine { LIBSAMPLERATE, LIBSOXR }

@Serializable
enum class ResamplerFilter { LINEAR_PHASE, MINIMUM_PHASE, APODIZING }

@Serializable
data class ResamplerSettings(
    val engine: ResamplerEngine? = null,
    val quality: Int? = null,
    val filter: ResamplerFilter? = null,
    val targetHz: Int? = null,
    val upsampleFactor: Int? = null,
    val nosBypass: Boolean? = null,
) {
    init {
        require(quality == null || quality >= 0)
        require(targetHz == null || targetHz in 1..384_000)
        require(upsampleFactor == null || upsampleFactor in listOf(2, 4))
        require(targetHz == null || upsampleFactor == null)
    }
}

@Serializable
enum class NormalizationMode { OFF, REPLAY_GAIN_TRACK, REPLAY_GAIN_ALBUM, R128_TRACK, R128_ALBUM }

@Serializable
enum class ChannelMode { ORIGINAL, SWAP_STEREO, MONO, DOWNMIX_5_1 }

@Serializable
data class DspSettings(
    val enabled: Boolean? = null,
    val channelMode: ChannelMode? = null,
    val includeLfe: Boolean? = null,
    val leftGainDb: Double? = null,
    val rightGainDb: Double? = null,
    val normalization: NormalizationMode? = null,
    val replayGainPreampDb: Double? = null,
    val replayGainPreventClipping: Boolean? = null,
    val r128TargetLufs: Double? = null,
    val preampDb: Double? = null,
    val eq: ParametricEq? = null,
    val convolutionEnabled: Boolean? = null,
    val crossfeedEnabled: Boolean? = null,
    val crossfeedLevelDb: Double? = null,
    val crossfeedCutoffHz: Int? = null,
    val headroomDb: Double? = null,
    val clipGuardEnabled: Boolean? = null,
    val ditherEnabled: Boolean? = null,
) {
    init {
        require(listOfNotNull(leftGainDb, rightGainDb, replayGainPreampDb, preampDb,
            crossfeedLevelDb, headroomDb).all(Double::isFinite))
        require(r128TargetLufs == null || r128TargetLufs in -30.0..-10.0)
        require(crossfeedCutoffHz == null || crossfeedCutoffHz > 0)
    }
}

@Serializable
data class ParametricEq(
    val sampleRateHz: Int,
    val bands: List<ParametricBand>,
    val preampDb: Double? = null,
    val enabled: Boolean? = null,
) {
    init {
        require(sampleRateHz in 1..384_000)
        require(preampDb == null || preampDb in -30.0..0.0)
        require(bands.size <= 20 && bands.all { it.centerHz <= 0.45 * sampleRateHz })
    }
}

@Serializable
enum class EqBandType { PEAK, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS }

@Serializable
data class ParametricBand(
    val type: EqBandType,
    val centerHz: Double,
    val gainDb: Double,
    val q: Double,
    val enabled: Boolean,
) {
    init {
        require(centerHz in 20.0..20_000.0)
        require(gainDb in -12.0..12.0)
        require(q in 0.1..20.0)
    }
}

@Serializable
data class BufferSettings(
    val softwareMs: Int? = null,
    val prebufferMs: Int? = null,
    val usbTransferMs: Int? = null,
) {
    init {
        require(softwareMs == null || softwareMs in listOf(40, 80, 160))
        require(prebufferMs == null || prebufferMs in listOf(0, 250, 500, 1_000))
        require(usbTransferMs == null || usbTransferMs > 0)
    }
}

@Serializable
data class VolumeSettings(
    val digitalGainDb: Double? = null,
    val androidVolumeStep: Int? = null,
    val hardwareVolume: Double? = null,
    val hardwareVolumeUnit: String? = null,
    val muted: Boolean? = null,
) {
    init {
        require(digitalGainDb == null || digitalGainDb in -90.0..0.0 && digitalGainDb * 2 % 1 == 0.0)
        require(androidVolumeStep == null || androidVolumeStep >= 0)
        require(hardwareVolume == null || hardwareVolume.isFinite())
        require(hardwareVolumeUnit == null || hardwareVolumeUnit.isNotBlank())
    }
}
