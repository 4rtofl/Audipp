package com.audipp.audio.pcm

import android.media.AudioFormat

internal enum class PcmSample(val bytes: Int) {
    SIGNED_16(2), SIGNED_24(3), SIGNED_32(4), FLOAT_32(4),
}

internal data class PcmFormat(
    val sample: PcmSample,
    val sampleRateHz: Int,
    val channelMask: Int,
) {
    val channelCount: Int = when (channelMask) {
        AudioFormat.CHANNEL_OUT_MONO -> 1
        AudioFormat.CHANNEL_OUT_STEREO -> 2
        AudioFormat.CHANNEL_OUT_5POINT1, FIVE_POINT_ONE_SIDE -> 6
        else -> 0
    }

    val bytesPerFrame: Int = channelCount * sample.bytes
    val isSupported: Boolean get() = sampleRateHz in 1..384_000 && channelCount != 0

    private companion object {
        const val FIVE_POINT_ONE_SIDE = AudioFormat.CHANNEL_OUT_STEREO or
            AudioFormat.CHANNEL_OUT_FRONT_CENTER or AudioFormat.CHANNEL_OUT_LOW_FREQUENCY or
            AudioFormat.CHANNEL_OUT_SIDE_LEFT or AudioFormat.CHANNEL_OUT_SIDE_RIGHT
    }
}
