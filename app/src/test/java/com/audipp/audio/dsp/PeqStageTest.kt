package com.audipp.audio.dsp

import com.audipp.domain.device.EqBandType
import com.audipp.domain.device.EqBandType.HIGH_PASS
import com.audipp.domain.device.EqBandType.HIGH_SHELF
import com.audipp.domain.device.EqBandType.LOW_PASS
import com.audipp.domain.device.EqBandType.LOW_SHELF
import com.audipp.domain.device.EqBandType.PEAK
import com.audipp.domain.device.OutputMode
import com.audipp.domain.device.ParametricBand
import com.audipp.domain.device.ParametricEq
import com.audipp.audio.dsp.PeqResult.Rejected as R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeqStageTest {
    private val fs = 48_000

    private fun band(type: EqBandType, hz: Double, gainDb: Double = 0.0, q: Double = 1.0) =
        PeqBand(type, hz, gainDb, q)

    private fun preset(vararg bands: PeqBand, preampDb: Double = 0.0, rate: Int = fs) =
        PeqPreset(rate, bands.toList(), preampDb)

    private fun applied(stage: PeqStage, preset: PeqPreset) =
        (stage.apply(preset) as PeqResult.Applied).maxPositiveGainDb

    /** Runs [input] (interleaved) through the stage in blocks of [block] frames. */
    private fun run(stage: PeqStage, input: FloatArray, channels: Int = 1, block: Int = 480): FloatArray {
        val out = input.copyOf()
        var start = 0
        val frames = input.size / channels
        val buffer = FloatArray(block * channels)
        while (start < frames) {
            val n = min(block, frames - start)
            System.arraycopy(out, start * channels, buffer, 0, n * channels)
            stage.process(buffer, n)
            System.arraycopy(buffer, 0, out, start * channels, n * channels)
            start += n
        }
        return out
    }

    private fun sine(hz: Double, frames: Int, amplitude: Double = 0.1, rate: Int = fs) =
        FloatArray(frames) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    // --- Independent reference: the cookbook's analog prototypes H(s) at s = j*tan(pi f/fs)/tan(pi f0/fs)
    // (the bilinear transform with f0 prewarped), evaluated in Float64 complex arithmetic.
    private data class C(val re: Double, val im: Double) {
        operator fun plus(o: C) = C(re + o.re, im + o.im)
        operator fun times(o: C) = C(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun times(k: Double) = C(re * k, im * k)
        operator fun div(o: C): C {
            val d = o.re * o.re + o.im * o.im
            return C((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
        }
        fun abs() = sqrt(re * re + im * im)
    }

    private fun referenceDb(b: PeqBand, hz: Double, rate: Int): Double {
        val s = C(0.0, tan(PI * hz / rate) / tan(PI * b.centerHz / rate))
        val s2 = s * s
        val one = C(1.0, 0.0)
        val a = 10.0.pow(b.gainDb / 40.0)
        val r = sqrt(a) / b.q
        val h = when (b.type) {
            PEAK -> (s2 + s * (a / b.q) + one) / (s2 + s * (1 / (b.q * a)) + one)
            LOW_SHELF -> (s2 + s * r + one * a) * a / (s2 * a + s * r + one)
            HIGH_SHELF -> (s2 * a + s * r + one) * a / (s2 + s * r + one * a)
            LOW_PASS -> one / (s2 + s * (1 / b.q) + one)
            HIGH_PASS -> s2 / (s2 + s * (1 / b.q) + one)
        }
        return 20 * log10(h.abs())
    }

    private fun tan(x: Double) = kotlin.math.tan(x)

    /** |H| in dB of the stage's own coefficients, by direct evaluation of the z-transform. */
    private fun designDb(b: PeqBand, hz: Double, rate: Int): Double {
        val c = PeqStage.design(b, rate)
        val w = 2 * PI * hz / rate
        val z1 = C(kotlin.math.cos(w), -sin(w))
        val z2 = z1 * z1
        val num = C(c[0], 0.0) + z1 * c[1] + z2 * c[2]
        val den = C(1.0, 0.0) + z1 * c[3] + z2 * c[4]
        return 20 * log10((num / den).abs())
    }

    private val logGrid = (0..240).map { 10.0 * 2.0.pow(it / 240.0 * 11.0) }

    @Test
    fun designedCoefficientsMatchTheAnalogReferenceWithin1e6Db() {
        var worst = 0.0
        for (rate in listOf(8_000, 44_100, 48_000, 96_000, 192_000, 384_000)) {
            val top = min(20_000.0, 0.45 * rate)
            for (type in EqBandType.entries) for (hz in listOf(20.0, 100.0, 1_000.0, top)) {
                for (g in listOf(-12.0, -3.0, 0.0, 6.0, 12.0)) for (q in listOf(0.1, 0.707, 4.0, 20.0)) {
                    val b = PeqBand(type, min(hz, top), g, q)
                    for (f in logGrid.filter { it < rate / 2 }) {
                        worst = maxOf(worst, abs(designDb(b, f, rate) - referenceDb(b, f, rate)))
                    }
                }
            }
        }
        assertTrue("worst deviation $worst dB", worst < 1e-6)
    }

    @Test
    fun processedSineHasTheReferenceGainWithin0_01Db() {
        val cases = listOf(
            band(PEAK, 1_000.0, 9.0, 2.0) to 1_000.0, band(PEAK, 1_000.0, -9.0, 2.0) to 1_200.0,
            band(LOW_SHELF, 200.0, 6.0, 0.7) to 50.0, band(HIGH_SHELF, 5_000.0, -6.0, 0.7) to 12_000.0,
            band(LOW_PASS, 2_000.0, 0.0, 0.707) to 4_000.0, band(HIGH_PASS, 500.0, 0.0, 3.0) to 400.0,
        )
        for ((b, hz) in cases) {
            val stage = PeqStage(fs, 1)
            applied(stage, preset(b))
            val x = sine(hz, 48_000)
            val y = run(stage, x)
            // Amplitude over the last 0.5 s (whole cycles are not needed: RMS ratio of long windows).
            val tail = 24_000 until 48_000
            val ratio = sqrt(tail.sumOf { y[it].toDouble().pow(2) } / tail.sumOf { x[it].toDouble().pow(2) })
            assertEquals("$b @ $hz", referenceDb(b, hz, fs), 20 * log10(ratio), 0.01)
        }
    }

    @Test
    fun cascadeOfBandsAndPreampMultiply() {
        val bands = arrayOf(band(PEAK, 300.0, 6.0, 1.0), band(LOW_SHELF, 100.0, -4.0, 0.7),
            band(PEAK, 350.0, 5.0, 3.0), band(HIGH_PASS, 30.0, 0.0, 0.707))
        val stage = PeqStage(fs, 1)
        applied(stage, preset(*bands, preampDb = -6.0))
        val x = sine(320.0, 48_000)
        val y = run(stage, x)
        val tail = 24_000 until 48_000
        val measured = 20 * log10(sqrt(tail.sumOf { y[it].toDouble().pow(2) } / tail.sumOf { x[it].toDouble().pow(2) }))
        assertEquals(bands.sumOf { referenceDb(it, 320.0, fs) } - 6.0, measured, 0.01)
    }

    @Test
    fun disabledBandsAreSkippedAndAnEmptyPresetPassesThrough() {
        val stage = PeqStage(fs, 1)
        applied(stage, preset(PeqBand(PEAK, 1_000.0, 12.0, 1.0, enabled = false)))
        val x = sine(1_000.0, 4_800)
        val y = run(stage, x)
        // The fade from the flat branch to another flat branch changes nothing.
        assertArrayEquals(x, y, 1e-7f)
    }

    @Test
    fun channelsAreFilteredIndependentlyWithTheSamePreset() {
        val p = preset(band(PEAK, 1_000.0, 8.0, 2.0), band(LOW_PASS, 5_000.0, 0.0, 1.5))
        val left = sine(700.0, 4_800)
        val right = sine(2_300.0, 4_800)
        val mono = PeqStage(fs, 1).also { applied(it, p) }
        val expectedLeft = run(mono, left)
        val expectedRight = run(PeqStage(fs, 1).also { applied(it, p) }, right)
        for (channels in listOf(2, 6)) {
            val input = FloatArray(4_800 * channels) { i ->
                when (i % channels) { 0 -> left[i / channels]; 1 -> right[i / channels]; else -> 0f }
            }
            val out = run(PeqStage(fs, channels).also { applied(it, p) }, input, channels)
            for (n in 0 until 4_800) {
                assertEquals(expectedLeft[n], out[n * channels], 0f)
                assertEquals(expectedRight[n], out[n * channels + 1], 0f)
            }
        }
    }

    @Test
    fun boundaryValuesAreAccepted() {
        val maxHz = min(20_000.0, 0.45 * fs)
        applied(PeqStage(fs, 1), preset(
            band(PEAK, 20.0, -12.0, 0.1), band(PEAK, maxHz, 12.0, 20.0), preampDb = -30.0))
        applied(PeqStage(fs, 1), preset(preampDb = 0.0))
        applied(PeqStage(fs, 1), preset(*Array(20) { band(PEAK, 100.0 + it * 500, 1.0) }))
        // 0.45 fs is the limit below 20 kHz: 44.1 kHz gives 19845 Hz, 32 kHz gives 14400 Hz.
        applied(PeqStage(44_100, 1), preset(band(PEAK, 19_845.0), rate = 44_100))
        applied(PeqStage(32_000, 1), preset(band(PEAK, 14_400.0), rate = 32_000))
    }

    @Test
    fun invalidPresetsAreRejectedWithTheirReason() {
        val cases = listOf(
            preset(*Array(21) { band(PEAK, 1_000.0) }) to R.TOO_MANY_BANDS,
            preset(rate = 44_100) to R.SAMPLE_RATE_MISMATCH,
            preset(band(PEAK, Double.NaN)) to R.NON_FINITE,
            preset(band(PEAK, Double.POSITIVE_INFINITY)) to R.NON_FINITE,
            preset(band(PEAK, 1_000.0, Double.NaN)) to R.NON_FINITE,
            preset(band(PEAK, 1_000.0, Double.NEGATIVE_INFINITY)) to R.NON_FINITE,
            preset(band(PEAK, 1_000.0, 0.0, Double.NaN)) to R.NON_FINITE,
            preset(band(PEAK, 1_000.0, 0.0, Double.POSITIVE_INFINITY)) to R.NON_FINITE,
            preset(preampDb = Double.NaN) to R.NON_FINITE,
            preset(band(PEAK, 19.99)) to R.FREQUENCY_OUT_OF_RANGE,
            preset(band(PEAK, 0.0)) to R.FREQUENCY_OUT_OF_RANGE,
            preset(band(PEAK, 20_000.1)) to R.FREQUENCY_OUT_OF_RANGE,
            preset(band(PEAK, 24_000.0)) to R.FREQUENCY_OUT_OF_RANGE, // Nyquist
            preset(band(PEAK, 100_000.0)) to R.FREQUENCY_OUT_OF_RANGE,
            preset(band(PEAK, 1_000.0, 12.001)) to R.GAIN_OUT_OF_RANGE,
            preset(band(PEAK, 1_000.0, -12.001)) to R.GAIN_OUT_OF_RANGE,
            preset(band(PEAK, 1_000.0, 0.0, 0.0999)) to R.Q_OUT_OF_RANGE,
            preset(band(PEAK, 1_000.0, 0.0, 20.001)) to R.Q_OUT_OF_RANGE,
            preset(band(PEAK, 1_000.0, 0.0, -1.0)) to R.Q_OUT_OF_RANGE,
            preset(preampDb = 0.001) to R.PREAMP_OUT_OF_RANGE,
            preset(preampDb = -30.001) to R.PREAMP_OUT_OF_RANGE,
            // The 0.45 fs limit applies below 20 kHz: 14401 Hz at 32 kHz, 19846 Hz at 44.1 kHz.
            preset(band(PEAK, 14_401.0), rate = 32_000) to R.FREQUENCY_OUT_OF_RANGE,
            preset(band(PEAK, 19_846.0), rate = 44_100) to R.FREQUENCY_OUT_OF_RANGE,
            // A disabled band is still checked, as is a pass filter's unused gain field.
            preset(PeqBand(PEAK, 1_000.0, 20.0, 1.0, enabled = false)) to R.GAIN_OUT_OF_RANGE,
            preset(band(LOW_PASS, 1_000.0, 13.0)) to R.GAIN_OUT_OF_RANGE,
        )
        for ((p, expected) in cases) {
            val stageRate = if (expected == R.SAMPLE_RATE_MISMATCH) fs else p.sampleRateHz
            assertEquals(p.toString(), expected, PeqStage(stageRate, 1).apply(p))
        }
    }

    @Test
    fun aRejectedPresetKeepsThePreviousOneInEffect() {
        val good = preset(band(PEAK, 1_000.0, 9.0, 2.0), band(HIGH_SHELF, 6_000.0, -5.0, 0.8), preampDb = -3.0)
        val x = sine(900.0, 9_600)
        val expected = run(PeqStage(fs, 1).also { applied(it, good) }, x)

        val stage = PeqStage(fs, 1)
        applied(stage, good)
        val first = run(stage, x.copyOf(4_800))
        for (bad in listOf(preset(band(PEAK, Double.NaN)), preset(band(PEAK, 1_000.0, 0.0, 21.0)),
            preset(*Array(21) { band(PEAK, 1_000.0) }), preset(band(PEAK, 24_000.0)))) {
            assertTrue(stage.apply(bad) is PeqResult.Rejected)
        }
        val second = run(stage, x.copyOfRange(4_800, 9_600))
        assertArrayEquals(expected, first + second, 0f)
    }

    @Test
    fun aRejectedPresetDuringAFadeDoesNotDisturbIt() {
        val good = preset(band(PEAK, 1_000.0, 9.0, 2.0))
        val x = sine(900.0, 2_000)
        val expected = run(PeqStage(fs, 1).also { applied(it, good) }, x)
        val stage = PeqStage(fs, 1)
        applied(stage, good)
        val first = run(stage, x.copyOf(100)) // inside the 480-frame fade
        assertTrue(stage.apply(preset(band(PEAK, 1_000.0, 99.0))) is PeqResult.Rejected)
        val second = run(stage, x.copyOfRange(100, 2_000))
        assertArrayEquals(expected, first + second, 0f)
    }

    // --- Crossfade: out = (1 - w) * old + w * new, new cascade starting from zero state.

    private fun referenceCascade(bands: List<PeqBand>, preampDb: Double, x: DoubleArray): DoubleArray {
        // Direct form I in Float64, one section at a time.
        var signal = x
        for (b in bands) {
            val c = PeqStage.design(b, fs)
            val y = DoubleArray(signal.size)
            for (n in signal.indices) {
                y[n] = c[0] * signal[n] + (if (n >= 1) c[1] * signal[n - 1] - c[3] * y[n - 1] else 0.0) +
                    (if (n >= 2) c[2] * signal[n - 2] - c[4] * y[n - 2] else 0.0)
            }
            signal = y
        }
        val g = 10.0.pow(preampDb / 20.0)
        return DoubleArray(signal.size) { signal[it] * g }
    }

    @Test
    fun presetChangeCrossfadesTheTwoCascadesOver10Ms() {
        val a = preset(band(PEAK, 1_000.0, 12.0, 4.0), preampDb = -2.0)
        val b = preset(band(LOW_SHELF, 300.0, -10.0, 0.7), band(PEAK, 900.0, -12.0, 8.0), preampDb = -9.0)
        val total = 4_800
        val switchAt = 1_920 // a block boundary at 480-frame blocks
        val x = sine(1_000.0, total, 0.3)
        val stage = PeqStage(fs, 1)
        applied(stage, a)
        val y = FloatArray(total)
        var start = 0
        while (start < total) {
            if (start == switchAt) applied(stage, b)
            val block = x.copyOfRange(start, start + 480)
            stage.process(block, 480)
            block.copyInto(y, start)
            start += 480
        }
        // The first preset itself fades in from flat; take the reference for the old branch from the
        // stage with only `a` so the check isolates the a -> b crossfade.
        val old = run(PeqStage(fs, 1).also { applied(it, a) }, x)
        val newPure = referenceCascade(b.bands, b.preampDb,
            DoubleArray(total - switchAt) { x[switchAt + it].toDouble() })
        for (n in 0 until total - switchAt) {
            val w = if (n < 480) (n + 1) / 480.0 else 1.0
            val expected = old[switchAt + n] * (1 - w) + newPure[n] * w
            assertEquals("frame $n", expected, y[switchAt + n].toDouble(), 2e-6)
        }
    }

    @Test
    fun noJumpAtTheStartOrEndOfACrossfadeBetweenOpposedPresets() {
        val boost = preset(band(PEAK, 1_000.0, 12.0, 1.0))
        val cut = preset(band(PEAK, 1_000.0, -12.0, 1.0), preampDb = -6.0)
        val x = sine(1_000.0, 9_600, 0.2)
        val stage = PeqStage(fs, 1)
        applied(stage, boost)
        val first = run(stage, x.copyOf(4_800))
        applied(stage, cut)
        val second = run(stage, x.copyOfRange(4_800, 9_600))
        val y = first + second
        // A 1 kHz sine of this level changes by at most 2*pi*1000/48000 * 0.2 * 4 (4x = +12 dB) per sample.
        val maxStep = 2 * PI * 1_000 / fs * 0.2 * 4.0
        var worst = 0.0
        for (n in 4_700 until 5_500) worst = maxOf(worst, abs(y[n] - y[n - 1]).toDouble())
        assertTrue("worst step $worst vs $maxStep", worst < maxStep * 1.1)
    }

    @Test
    fun aPresetArrivingDuringAFadeWaitsAndTheLatestOneWins() {
        val a = preset(band(PEAK, 1_000.0, 6.0, 1.0))
        val b = preset(band(PEAK, 1_000.0, -6.0, 1.0))
        val skipped = preset(band(PEAK, 1_000.0, 12.0, 1.0))
        val last = preset(band(HIGH_SHELF, 4_000.0, 9.0, 0.7), preampDb = -9.0)
        val stage = PeqStage(fs, 1)
        applied(stage, a)
        val x = sine(5_000.0, 14_400, 0.1)
        val out = FloatArray(14_400)
        var start = 0
        while (start < 14_400) {
            when (start) {
                960 -> applied(stage, b)        // fades 960..1439
                1_200 -> applied(stage, skipped) // arrives mid-fade, replaced before it can start
                1_320 -> applied(stage, last)
            }
            val block = x.copyOfRange(start, start + 120)
            stage.process(block, 120)
            block.copyInto(out, start)
            start += 120
        }
        val tail = 10_000 until 14_400
        val ratio = sqrt(tail.sumOf { out[it].toDouble().pow(2) } / tail.sumOf { x[it].toDouble().pow(2) })
        assertEquals(referenceDb(last.bands[0], 5_000.0, fs) - 9.0, 20 * log10(ratio), 0.01)
    }

    @Test
    fun stableForEveryTypeAcrossTheAllowedRange() {
        for (rate in listOf(8_000, 32_000, 44_100, 48_000, 96_000, 192_000, 384_000)) {
            val top = min(20_000.0, 0.45 * rate)
            for (type in EqBandType.entries) for (hz in listOf(20.0, 55.0, 1_000.0, top / 2, top)) {
                for (g in listOf(-12.0, 0.0, 12.0)) for (q in listOf(0.1, 0.5, 1.0, 20.0)) {
                    val c = PeqStage.design(PeqBand(type, hz, g, q), rate)
                    // Jury criteria for z^2 + a1 z + a2: |a2| < 1 and |a1| < 1 + a2.
                    assertTrue("$type $hz $g $q @$rate", abs(c[4]) < 1 && abs(c[3]) < 1 + c[4])
                }
            }
        }
    }

    // --- Maximum positive gain handed to the #68 headroom check.

    private fun bruteForcePeakDb(bands: List<PeqBand>, preampDb: Double): Double {
        var peak = Double.NEGATIVE_INFINITY
        for (i in 0..1_000_000) {
            val f = 5.0 * (fs / 10.0).pow(i / 1_000_000.0) // log grid, about 1/80000 octave
            peak = maxOf(peak, bands.sumOf { referenceDb(it, f, fs) })
        }
        return maxOf(0.0, peak + preampDb)
    }

    @Test
    fun maxPositiveGainIsThePeakOfTheCascadeWithinTolerance() {
        val cases = listOf(
            preset(band(PEAK, 1_000.0, 6.0, 1.0)),
            preset(band(PEAK, 1_000.0, 6.0, 1.0), band(PEAK, 1_000.0, 6.0, 1.0)),
            preset(band(PEAK, 100.0, 6.0, 4.0), band(PEAK, 10_000.0, 6.0, 4.0)),
            preset(band(PEAK, 1_000.0, 12.0, 20.0), band(PEAK, 1_200.0, 12.0, 20.0)),
            preset(band(LOW_SHELF, 100.0, 12.0, 0.7), band(HIGH_SHELF, 8_000.0, 12.0, 0.7), preampDb = -5.0),
            preset(band(LOW_PASS, 1_000.0, 0.0, 20.0)),
            preset(band(HIGH_PASS, 20.0, 0.0, 20.0), band(LOW_SHELF, 25.0, 12.0, 20.0)),
            preset(band(PEAK, 1_000.0, 6.0, 1.0), preampDb = -6.0),
        )
        for (p in cases) {
            val reported = applied(PeqStage(fs, 1), p)
            assertEquals(p.toString(), bruteForcePeakDb(p.bands, p.preampDb), reported, 0.01)
        }
    }

    @Test
    fun maxPositiveGainStatesTheOverlapAndPreampBehaviour() {
        // Same-frequency peaks add in dB; far-apart peaks do not; a -6 dB preamp cancels +6 dB.
        assertEquals(12.0, applied(PeqStage(fs, 1),
            preset(band(PEAK, 1_000.0, 6.0, 1.0), band(PEAK, 1_000.0, 6.0, 1.0))), 0.01)
        assertEquals(6.0, applied(PeqStage(fs, 1),
            preset(band(PEAK, 100.0, 6.0, 8.0), band(PEAK, 10_000.0, 6.0, 8.0))), 0.05)
        assertEquals(0.0, applied(PeqStage(fs, 1),
            preset(band(PEAK, 1_000.0, 6.0, 1.0), preampDb = -6.0)), 0.01)
        assertEquals(0.0, applied(PeqStage(fs, 1), preset(band(PEAK, 1_000.0, -12.0, 1.0))), 0.0)
        // A Q = 20 low pass resonates by about 20*log10(Q) although its gain field is 0.
        assertEquals(26.02, applied(PeqStage(fs, 1), preset(band(LOW_PASS, 1_000.0, 0.0, 20.0))), 0.02)
    }

    @Test
    fun maxPositiveGainFeedsTheHeadroomCheckOfTheChain() {
        val stage = PeqStage(fs, 1)
        val boost = applied(stage, preset(band(PEAK, 1_000.0, 6.0, 1.0)))
        fun open(attenuationDb: Double) = DspChain.open(DspConfig(OutputMode.PROCESSED, fs, 1,
            DspOutput.FLOAT32, 0.0, boost, headroomAttenuationDb = attenuationDb,
            stages = DspStages(peq = stage)))
        assertEquals(DspOpenResult.Rejected.HEADROOM_INSUFFICIENT, open(0.0))
        assertTrue(open(boost) is DspOpenResult.Ready)
    }

    // --- Relationship to the chain's 10 ms gain smoothing.

    @Test
    fun presetPreampFadeAndChainGainRampRunTogetherEachOverTheirOwn10Ms() {
        val stage = PeqStage(fs, 1)
        applied(stage, preset(preampDb = -6.0))
        val chain = (DspChain.open(DspConfig(OutputMode.PROCESSED, fs, 1, DspOutput.FLOAT32, 0.0, 6.0,
            headroomAttenuationDb = 6.0, stages = DspStages(peq = stage))) as DspOpenResult.Ready).chain
        // The chain starts at -6 dB (attenuation) = 0.5012; setGainDb(-6) moves it to 0.2512.
        assertTrue(chain.setGainDb(-6.0))
        val frames = 3_000
        val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(DspBlockResult.PROCESSED, chain.process(FloatArray(frames) { 0.5f }, frames, out))
        out.position(0)
        val y = FloatArray(frames) { out.getFloat() }
        val g0 = 10.0.pow(-6.0 / 20)
        val g1 = 10.0.pow(-12.0 / 20)
        val p1 = 10.0.pow(-6.0 / 20)
        val delay = chain.latencyFrames
        for (n in listOf(0, 100, 239, 479, 480, 1_000)) {
            val k = (n + 1).coerceAtMost(480) / 480.0 // both ramps are 480 frames at 48 kHz
            val gain = g0 + (g1 - g0) * k
            val fade = 1 + (p1 - 1) * k
            // The first latencyFrames output frames are the guard's silence.
            assertEquals("frame $n", 0.5 * gain * fade, y[n + delay].toDouble(), 1e-6)
        }
    }

    // --- Mapping from the stored settings type.

    @Test
    fun parametricEqMapsToAPresetWithoutChangingValues() {
        val eq = ParametricEq(fs, listOf(
            ParametricBand(PEAK, 1_000.0, 3.0, 2.0, true),
            ParametricBand(LOW_SHELF, 80.0, -2.0, 0.7, false)), preampDb = -4.0, enabled = null)
        val p = eq.toPeqPreset()
        assertEquals(PeqPreset(fs, listOf(PeqBand(PEAK, 1_000.0, 3.0, 2.0, true),
            PeqBand(LOW_SHELF, 80.0, -2.0, 0.7, false)), -4.0), p)
        assertTrue(PeqStage(fs, 1).apply(p) is PeqResult.Applied)
        assertEquals(PeqPreset(fs, emptyList(), 0.0), eq.copy(enabled = false).toPeqPreset())
        assertEquals(0.0, eq.copy(preampDb = null).toPeqPreset().preampDb, 0.0)
    }
}
