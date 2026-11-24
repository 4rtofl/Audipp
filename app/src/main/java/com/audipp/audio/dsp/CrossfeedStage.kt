package com.audipp.audio.dsp

import com.audipp.domain.device.DspSettings
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Unvalidated settings as the settings layer hands them over; [CrossfeedStage.apply] is the only
 * check. [levelDb] is how far the crossfed signal sits below the direct one at low frequencies.
 */
internal data class CrossfeedSettings(
    val enabled: Boolean,
    val cutoffHz: Int = CrossfeedStage.DEFAULT_CUTOFF_HZ,
    val levelDb: Double = CrossfeedStage.DEFAULT_LEVEL_DB,
)

internal sealed interface CrossfeedResult {
    /** [maxPositiveGainDb] is what the caller adds to [DspConfig.maxPositiveGainDb]. */
    data class Applied(val maxPositiveGainDb: Double) : CrossfeedResult
    enum class Rejected : CrossfeedResult {
        NOT_STEREO, NON_FINITE, CUTOFF_OUT_OF_RANGE, LEVEL_OUT_OF_RANGE,
    }
}

/** Unset values take the default 700 Hz / 4.5 dB; an unset switch is off. */
internal fun DspSettings.toCrossfeedSettings() = CrossfeedSettings(
    enabled = crossfeedEnabled == true,
    cutoffHz = crossfeedCutoffHz ?: CrossfeedStage.DEFAULT_CUTOFF_HZ,
    levelDb = crossfeedLevelDb ?: CrossfeedStage.DEFAULT_LEVEL_DB,
)

/**
 * Bauer stereophonic-to-binaural crossfeed for stereo only: each output is a first-order high-boost
 * of its own channel plus a first-order low pass of the other one, with the level-to-gain mapping
 * of bs2b. Both filters come from the analog prototypes by the bilinear transform, prewarped at
 * their corners, and run in Float64.
 *
 * [apply] validates and designs on the caller's thread and hands the filter over by an atomic
 * reference. [process] runs the old and the new filter side by side and crossfades their outputs
 * over [DspChain.RAMP_MS], like [PeqStage]; it allocates and locks nothing.
 */
internal class CrossfeedStage(private val sampleRateHz: Int, private val channelCount: Int) : DspStage {
    /** First-order sections y = b0*x + b1*x[-1] - a1*y[-1], with the output gain folded in. */
    private class Filter(
        val directB0: Double, val directB1: Double, val directA1: Double,
        val crossB0: Double, val crossB1: Double, val crossA1: Double,
    )

    private class Branch(var filter: Filter) {
        /** Transposed direct form II state: direct L, direct R, cross L->R, cross R->L. */
        val state = DoubleArray(4)

        fun start(next: Filter) {
            filter = next
            state.fill(0.0)
        }

        fun direct(z: Int, x: Double): Double {
            val y = filter.directB0 * x + state[z]
            state[z] = filter.directB1 * x - filter.directA1 * y
            return y
        }

        fun cross(z: Int, x: Double): Double {
            val y = filter.crossB0 * x + state[z]
            state[z] = filter.crossB1 * x - filter.crossA1 * y
            return y
        }
    }

    private val flat = Filter(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private val pending = AtomicReference<Filter?>(null)
    private var active = Branch(flat)
    private var incoming = Branch(flat)
    private val fadeFrames = maxOf(1, (sampleRateHz * DspChain.RAMP_MS / 1_000.0).roundToInt())
    private var fadeLeft = 0

    /** A rejected setting changes nothing: the previous one stays in effect. */
    fun apply(settings: CrossfeedSettings): CrossfeedResult {
        if (settings.enabled && channelCount != 2) return CrossfeedResult.Rejected.NOT_STEREO
        if (!settings.levelDb.isFinite()) return CrossfeedResult.Rejected.NON_FINITE
        if (settings.levelDb !in MIN_LEVEL_DB..MAX_LEVEL_DB) {
            return CrossfeedResult.Rejected.LEVEL_OUT_OF_RANGE
        }
        if (settings.cutoffHz !in MIN_CUTOFF_HZ..MAX_CUTOFF_HZ ||
            highBoostHz(settings.cutoffHz, settings.levelDb) > 0.45 * sampleRateHz
        ) return CrossfeedResult.Rejected.CUTOFF_OUT_OF_RANGE
        if (channelCount != 2) return CrossfeedResult.Applied(0.0)
        val filter = if (settings.enabled) design(settings.cutoffHz, settings.levelDb) else flat
        pending.set(filter)
        return CrossfeedResult.Applied(maxPositiveGainDb(filter))
    }

    override fun process(samples: FloatArray, frames: Int) {
        if (channelCount != 2) return
        if (fadeLeft == 0) {
            val next = pending.getAndSet(null)
            if (next != null) {
                incoming.start(next)
                fadeLeft = fadeFrames
            }
        }
        for (frame in 0 until frames) {
            val i = frame * 2
            val l = samples[i].toDouble()
            val r = samples[i + 1].toDouble()
            val a = active
            var outL = a.direct(0, l) + a.cross(3, r)
            var outR = a.direct(1, r) + a.cross(2, l)
            if (fadeLeft > 0) {
                val weight = (fadeFrames - fadeLeft + 1).toDouble() / fadeFrames
                val b = incoming
                outL = outL * (1 - weight) + (b.direct(0, l) + b.cross(3, r)) * weight
                outR = outR * (1 - weight) + (b.direct(1, r) + b.cross(2, l)) * weight
                if (--fadeLeft == 0) {
                    active = incoming
                    incoming = a
                }
            }
            samples[i] = outL.toFloat()
            samples[i + 1] = outR.toFloat()
        }
    }

    private fun design(cutoffHz: Int, levelDb: Double): Filter {
        val lowDb = lowPassGainDb(levelDb)
        val lowGain = 10.0.pow(lowDb / 20.0)
        val highCut = highBoostCut(levelDb)
        // Normalized so that an in-phase signal at DC keeps its level.
        val gain = 1 / (1 - highCut + lowGain)
        val wLow = 2 * PI * cutoffHz
        val wHigh = 2 * PI * highBoostHz(cutoffHz, levelDb)
        val direct = bilinear(gain, gain * wHigh * (1 - highCut), wHigh)
        val cross = bilinear(0.0, gain * lowGain * wLow, wLow)
        return Filter(direct[0], direct[1], direct[2], cross[0], cross[1], cross[2])
    }

    /** (s1*s + s0) / (s + w) with s = K(1 - z^-1)/(1 + z^-1), K prewarped at w. Returns b0, b1, a1. */
    private fun bilinear(s1: Double, s0: Double, w: Double): DoubleArray {
        val k = w / tan(w / (2.0 * sampleRateHz))
        val d = k + w
        return doubleArrayOf((s1 * k + s0) / d, (s0 - s1 * k) / d, (w - k) / d)
    }

    /**
     * Peak over frequency of |direct| + |cross|, floored at 0 dB: the steady-state bound when both
     * channels carry a full-scale sinusoid in the worst phase. Sampled on a log grid plus DC and
     * Nyquist; it is not a bound for arbitrary signals.
     */
    private fun maxPositiveGainDb(filter: Filter): Double {
        var peak = maxOf(sumOfMagnitudes(filter, 0.0), sumOfMagnitudes(filter, PI))
        val lowW = 2 * PI * 10.0 / sampleRateHz
        val octaves = log2(PI / lowW)
        for (i in 0..RESPONSE_GRID_POINTS) {
            peak = maxOf(peak, sumOfMagnitudes(filter, lowW * 2.0.pow(octaves * i / RESPONSE_GRID_POINTS)))
        }
        return maxOf(0.0, 20 * log10(peak))
    }

    private fun sumOfMagnitudes(f: Filter, w: Double) =
        magnitude(f.directB0, f.directB1, f.directA1, w) + magnitude(f.crossB0, f.crossB1, f.crossA1, w)

    private fun magnitude(b0: Double, b1: Double, a1: Double, w: Double): Double {
        val c = cos(w)
        val s = sin(w)
        val nr = b0 + b1 * c
        val dr = 1 + a1 * c
        return sqrt((nr * nr + b1 * s * b1 * s) / (dr * dr + a1 * s * a1 * s))
    }

    companion object {
        const val MIN_CUTOFF_HZ = 300
        const val MAX_CUTOFF_HZ = 2_000
        const val MIN_LEVEL_DB = 1.0
        const val MAX_LEVEL_DB = 15.0
        const val DEFAULT_CUTOFF_HZ = 700
        const val DEFAULT_LEVEL_DB = 4.5
        private const val RESPONSE_GRID_POINTS = 4096

        private fun lowPassGainDb(levelDb: Double) = levelDb * -5.0 / 6.0 - 3.0

        /** Depth of the high-boost's low-frequency cut, as 1 - its DC gain. */
        private fun highBoostCut(levelDb: Double) = 1 - 10.0.pow((levelDb / 6.0 - 3.0) / 20.0)

        internal fun highBoostHz(cutoffHz: Int, levelDb: Double) =
            cutoffHz * 2.0.pow((lowPassGainDb(levelDb) - 20 * log10(highBoostCut(levelDb))) / 12.0)
    }
}
