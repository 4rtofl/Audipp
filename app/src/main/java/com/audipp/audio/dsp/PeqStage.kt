package com.audipp.audio.dsp

import com.audipp.domain.device.EqBandType
import com.audipp.domain.device.ParametricEq
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Unvalidated band as the settings layer hands it over; [PeqStage.apply] is the only check. */
internal data class PeqBand(
    val type: EqBandType,
    val centerHz: Double,
    val gainDb: Double,
    val q: Double,
    val enabled: Boolean = true,
)

internal data class PeqPreset(val sampleRateHz: Int, val bands: List<PeqBand>, val preampDb: Double)

internal sealed interface PeqResult {
    /** [maxPositiveGainDb] is what the caller adds to [DspConfig.maxPositiveGainDb]. */
    data class Applied(val maxPositiveGainDb: Double) : PeqResult
    enum class Rejected : PeqResult {
        SAMPLE_RATE_MISMATCH, TOO_MANY_BANDS, NON_FINITE, FREQUENCY_OUT_OF_RANGE, GAIN_OUT_OF_RANGE,
        Q_OUT_OF_RANGE, PREAMP_OUT_OF_RANGE,
    }
}

/** An unset [ParametricEq.enabled] counts as enabled; a disabled EQ is a flat preset with no preamp. */
internal fun ParametricEq.toPeqPreset() =
    if (enabled == false) PeqPreset(sampleRateHz, emptyList(), 0.0)
    else PeqPreset(sampleRateHz,
        bands.map { PeqBand(it.type, it.centerHz, it.gainDb, it.q, it.enabled) }, preampDb ?: 0.0)

/**
 * Parametric EQ stage (DD-DSP-02). Every channel gets the same cascade of biquads, in Float64.
 *
 * [apply] validates and designs the coefficients on the caller's thread and hands them over by an
 * atomic reference. [process] runs the old and the new cascade side by side and crossfades their
 * outputs over [DspChain.RAMP_MS]; it allocates and locks nothing. Both cascades are stable on
 * their own, so no intermediate coefficient set is ever used. A preset that arrives during a
 * crossfade waits for it to end and a newer one replaces it.
 */
internal class PeqStage(private val sampleRateHz: Int, private val channelCount: Int) : DspStage {
    private class Filter(
        val sections: Int,
        /** Per section b0, b1, b2, a1, a2, divided by a0. */
        val coefficients: DoubleArray,
        val preampLinear: Double,
    )

    private inner class Branch(var filter: Filter) {
        val state = DoubleArray(channelCount * MAX_BANDS * 2)

        fun start(next: Filter) {
            filter = next
            state.fill(0.0)
        }

        // Transposed direct form II.
        fun run(channel: Int, input: Double): Double {
            val coefficients = filter.coefficients
            val base = channel * MAX_BANDS * 2
            var x = input
            for (s in 0 until filter.sections) {
                val k = s * 5
                val z = base + s * 2
                val y = coefficients[k] * x + state[z]
                state[z] = coefficients[k + 1] * x - coefficients[k + 3] * y + state[z + 1]
                state[z + 1] = coefficients[k + 2] * x - coefficients[k + 4] * y
                x = y
            }
            return x * filter.preampLinear
        }
    }

    private val flat = Filter(0, DoubleArray(0), 1.0)
    private val pending = AtomicReference<Filter?>(null)
    private var active = Branch(flat)
    private var incoming = Branch(flat)
    private val fadeFrames = maxOf(1, (sampleRateHz * DspChain.RAMP_MS / 1_000.0).roundToInt())
    private var fadeLeft = 0

    /** A rejected preset changes nothing: the previous one stays in effect. */
    fun apply(preset: PeqPreset): PeqResult {
        val rejection = validate(preset)
        if (rejection != null) return rejection
        val enabled = preset.bands.filter { it.enabled }
        val coefficients = DoubleArray(enabled.size * 5)
        enabled.forEachIndexed { i, band -> design(band, sampleRateHz).copyInto(coefficients, i * 5) }
        val filter = Filter(enabled.size, coefficients, 10.0.pow(preset.preampDb / 20.0))
        pending.set(filter)
        return PeqResult.Applied(maxPositiveGainDb(filter, preset.preampDb,
            enabled.map { it.centerHz }, sampleRateHz))
    }

    private fun validate(preset: PeqPreset): PeqResult.Rejected? {
        if (preset.sampleRateHz != sampleRateHz) return PeqResult.Rejected.SAMPLE_RATE_MISMATCH
        if (preset.bands.size > MAX_BANDS) return PeqResult.Rejected.TOO_MANY_BANDS
        if (!preset.preampDb.isFinite()) return PeqResult.Rejected.NON_FINITE
        if (preset.preampDb !in MIN_PREAMP_DB..MAX_PREAMP_DB) return PeqResult.Rejected.PREAMP_OUT_OF_RANGE
        val maxHz = min(MAX_HZ, 0.45 * sampleRateHz)
        for (band in preset.bands) {
            if (!band.centerHz.isFinite() || !band.gainDb.isFinite() || !band.q.isFinite()) {
                return PeqResult.Rejected.NON_FINITE
            }
            if (band.centerHz !in MIN_HZ..maxHz) return PeqResult.Rejected.FREQUENCY_OUT_OF_RANGE
            if (band.gainDb !in -MAX_GAIN_DB..MAX_GAIN_DB) return PeqResult.Rejected.GAIN_OUT_OF_RANGE
            if (band.q !in MIN_Q..MAX_Q) return PeqResult.Rejected.Q_OUT_OF_RANGE
        }
        return null
    }

    override fun process(samples: FloatArray, frames: Int) {
        if (fadeLeft == 0) {
            val next = pending.getAndSet(null)
            if (next != null) {
                incoming.start(next)
                fadeLeft = fadeFrames
            }
        }
        val channels = channelCount
        for (frame in 0 until frames) {
            if (fadeLeft == 0) {
                for (c in 0 until channels) {
                    val i = frame * channels + c
                    samples[i] = active.run(c, samples[i].toDouble()).toFloat()
                }
            } else {
                val weight = (fadeFrames - fadeLeft + 1).toDouble() / fadeFrames
                for (c in 0 until channels) {
                    val i = frame * channels + c
                    val x = samples[i].toDouble()
                    samples[i] = (active.run(c, x) * (1 - weight) + incoming.run(c, x) * weight).toFloat()
                }
                if (--fadeLeft == 0) {
                    val finished = active
                    active = incoming
                    incoming = finished
                }
            }
        }
    }

    companion object {
        const val MAX_BANDS = 20
        const val MIN_HZ = 20.0
        const val MAX_HZ = 20_000.0
        const val MAX_GAIN_DB = 12.0
        const val MIN_Q = 0.1
        const val MAX_Q = 20.0
        const val MIN_PREAMP_DB = -30.0
        const val MAX_PREAMP_DB = 0.0
        private const val RESPONSE_GRID_POINTS = 4096

        /** RBJ Audio EQ Cookbook, Q form for the shelves. Returns b0, b1, b2, a1, a2 over a0. */
        internal fun design(band: PeqBand, sampleRateHz: Int): DoubleArray {
            val w0 = 2 * PI * band.centerHz / sampleRateHz
            val cosW = cos(w0)
            val alpha = sin(w0) / (2 * band.q)
            val a = 10.0.pow(band.gainDb / 40.0)
            val shelf = 2 * sqrt(a) * alpha
            val c = when (band.type) {
                EqBandType.PEAK -> doubleArrayOf(
                    1 + alpha * a, -2 * cosW, 1 - alpha * a, 1 + alpha / a, -2 * cosW, 1 - alpha / a)
                EqBandType.LOW_SHELF -> doubleArrayOf(
                    a * ((a + 1) - (a - 1) * cosW + shelf), 2 * a * ((a - 1) - (a + 1) * cosW),
                    a * ((a + 1) - (a - 1) * cosW - shelf), (a + 1) + (a - 1) * cosW + shelf,
                    -2 * ((a - 1) + (a + 1) * cosW), (a + 1) + (a - 1) * cosW - shelf)
                EqBandType.HIGH_SHELF -> doubleArrayOf(
                    a * ((a + 1) + (a - 1) * cosW + shelf), -2 * a * ((a - 1) + (a + 1) * cosW),
                    a * ((a + 1) + (a - 1) * cosW - shelf), (a + 1) - (a - 1) * cosW + shelf,
                    2 * ((a - 1) - (a + 1) * cosW), (a + 1) - (a - 1) * cosW - shelf)
                EqBandType.LOW_PASS -> doubleArrayOf(
                    (1 - cosW) / 2, 1 - cosW, (1 - cosW) / 2, 1 + alpha, -2 * cosW, 1 - alpha)
                EqBandType.HIGH_PASS -> doubleArrayOf(
                    (1 + cosW) / 2, -(1 + cosW), (1 + cosW) / 2, 1 + alpha, -2 * cosW, 1 - alpha)
            }
            return doubleArrayOf(c[0] / c[3], c[1] / c[3], c[2] / c[3], c[4] / c[3], c[5] / c[3])
        }

        /**
         * Peak of the cascade's magnitude response plus [preampDb], floored at 0 dB. The cascade is
         * evaluated as a whole because bands overlap and a low/high pass with Q > 1/sqrt(2) has gain
         * at its corner that no per-band gain field states. It is the steady-state sinusoid peak,
         * sampled on a log grid plus DC and Nyquist, not a bound for arbitrary signals.
         */
        private fun maxPositiveGainDb(
            filter: Filter, preampDb: Double, centers: List<Double>, sampleRateHz: Int,
        ): Double {
            var peak = maxOf(responseDb(filter, 0.0), responseDb(filter, PI))
            for (center in centers) peak = maxOf(peak, responseDb(filter, 2 * PI * center / sampleRateHz))
            val lowW = 2 * PI * 10.0 / sampleRateHz
            val octaves = log2(PI / lowW)
            for (i in 0..RESPONSE_GRID_POINTS) {
                peak = maxOf(peak, responseDb(filter, lowW * 2.0.pow(octaves * i / RESPONSE_GRID_POINTS)))
            }
            return maxOf(0.0, peak + preampDb)
        }

        private fun responseDb(filter: Filter, w: Double): Double {
            val c1 = cos(w)
            val s1 = sin(w)
            val c2 = cos(2 * w)
            val s2 = sin(2 * w)
            var db = 0.0
            for (s in 0 until filter.sections) {
                val k = s * 5
                val b = filter.coefficients
                val nr = b[k] + b[k + 1] * c1 + b[k + 2] * c2
                val ni = b[k + 1] * s1 + b[k + 2] * s2
                val dr = 1 + b[k + 3] * c1 + b[k + 4] * c2
                val di = b[k + 3] * s1 + b[k + 4] * s2
                db += 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
            }
            return db
        }
    }
}
