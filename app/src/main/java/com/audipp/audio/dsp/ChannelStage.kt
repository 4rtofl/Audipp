package com.audipp.audio.dsp

import com.audipp.domain.device.ChannelMode
import com.audipp.domain.device.DspSettings
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow
import kotlin.math.roundToInt

/** Unvalidated settings as the settings layer hands them over; [ChannelStage.apply] is the only check. */
internal data class ChannelSettings(
    val swap: Boolean = false,
    val mono: Boolean = false,
    val leftGainDb: Double = 0.0,
    val rightGainDb: Double = 0.0,
)

internal sealed interface ChannelResult {
    /** [maxPositiveGainDb] is what the caller adds to [DspConfig.maxPositiveGainDb]. */
    data class Applied(val maxPositiveGainDb: Double) : ChannelResult
    enum class Rejected : ChannelResult { NON_FINITE, GAIN_OUT_OF_RANGE, NO_LEFT_RIGHT_PAIR }
}

/** DOWNMIX_5_1 belongs to the map stage (#41) and leaves this stage as it is. */
internal fun DspSettings.toChannelSettings() = ChannelSettings(
    swap = channelMode == ChannelMode.SWAP_STEREO,
    mono = channelMode == ChannelMode.MONO,
    leftGainDb = leftGainDb ?: 0.0,
    rightGainDb = rightGainDb ?: 0.0,
)

/**
 * Swap, mono and left/right gain as one 2x2 matrix on each left/right pair: FL/FR in stereo, and
 * FL/FR and the back or side pair in 5.1. FC and LFE are left alone; mono has no pair.
 *
 * [apply] builds the matrix on the caller's thread and hands it over by an atomic reference.
 * [process] moves from the old to the new matrix over [DspChain.RAMP_MS] as (1-w)*old + w*new; it
 * allocates and locks nothing. A matrix that arrives during a fade waits for it to end and a newer
 * one replaces it.
 */
internal class ChannelStage(sampleRateHz: Int, private val channelCount: Int) : DspStage {
    /** Interleaved indices of the left/right pairs, in Android's channel order. */
    private val pairs = when (channelCount) {
        2 -> intArrayOf(0, 1)
        6 -> intArrayOf(0, 1, 4, 5)
        else -> IntArray(0)
    }
    private val pending = AtomicReference<DoubleArray?>(null)
    /** out L = m[0]*L + m[1]*R, out R = m[2]*L + m[3]*R. */
    private val current = doubleArrayOf(1.0, 0.0, 0.0, 1.0)
    private val from = DoubleArray(4)
    private var target = current.copyOf()
    private val fadeFrames = maxOf(1, (sampleRateHz * DspChain.RAMP_MS / 1_000.0).roundToInt())
    private var fadeLeft = 0

    /** A rejected setting changes nothing: the previous one stays in effect. */
    fun apply(settings: ChannelSettings): ChannelResult {
        val left = settings.leftGainDb
        val right = settings.rightGainDb
        if (!left.isFinite() || !right.isFinite()) return ChannelResult.Rejected.NON_FINITE
        if (left !in -MAX_GAIN_DB..MAX_GAIN_DB || right !in -MAX_GAIN_DB..MAX_GAIN_DB) {
            return ChannelResult.Rejected.GAIN_OUT_OF_RANGE
        }
        if (pairs.isEmpty() && settings != ChannelSettings()) {
            return ChannelResult.Rejected.NO_LEFT_RIGHT_PAIR
        }
        val m = when {
            settings.mono -> doubleArrayOf(0.5, 0.5, 0.5, 0.5)
            settings.swap -> doubleArrayOf(0.0, 1.0, 1.0, 0.0)
            else -> doubleArrayOf(1.0, 0.0, 0.0, 1.0)
        }
        val gl = 10.0.pow(left / 20.0)
        val gr = 10.0.pow(right / 20.0)
        pending.set(doubleArrayOf(m[0] * gl, m[1] * gl, m[2] * gr, m[3] * gr))
        // Each output row sums |coefficients| to its gain, so this bounds every input, not only sines.
        return ChannelResult.Applied(maxOf(0.0, left, right))
    }

    override fun process(samples: FloatArray, frames: Int) {
        if (pairs.isEmpty()) return
        if (fadeLeft == 0) {
            val next = pending.getAndSet(null)
            if (next != null) {
                current.copyInto(from)
                target = next
                fadeLeft = fadeFrames
            }
        }
        val channels = channelCount
        for (frame in 0 until frames) {
            if (fadeLeft > 0) {
                val weight = (fadeFrames - fadeLeft + 1).toDouble() / fadeFrames
                for (k in 0 until 4) current[k] = from[k] * (1 - weight) + target[k] * weight
                fadeLeft--
            }
            val base = frame * channels
            var p = 0
            while (p < pairs.size) {
                val l = base + pairs[p]
                val r = base + pairs[p + 1]
                val x = samples[l].toDouble()
                val y = samples[r].toDouble()
                samples[l] = (current[0] * x + current[1] * y).toFloat()
                samples[r] = (current[2] * x + current[3] * y).toFloat()
                p += 2
            }
        }
    }

    companion object {
        const val MAX_GAIN_DB = 12.0
    }
}
