package com.audipp.audio.dsp

import com.audipp.domain.device.DspSettings
import com.audipp.domain.device.OutputMode
import com.audipp.audio.dsp.CrossfeedResult.Rejected as R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfeedStageTest {
    private val fs = 48_000

    private val corners = listOf(300 to 1.0, 300 to 15.0, 2_000 to 1.0, 2_000 to 15.0, 700 to 4.5,
        650 to 9.5)

    private fun run(stage: DspStage, input: FloatArray, block: Int = 480): FloatArray {
        val out = input.copyOf()
        val frames = input.size / 2
        val buffer = FloatArray(block * 2)
        var start = 0
        while (start < frames) {
            val n = min(block, frames - start)
            System.arraycopy(out, start * 2, buffer, 0, n * 2)
            stage.process(buffer, n)
            System.arraycopy(buffer, 0, out, start * 2, n * 2)
            start += n
        }
        return out
    }

    /** A stage that has finished fading in [settings] on silence, so its state is still zero. */
    private fun settled(settings: CrossfeedSettings, rate: Int = fs): CrossfeedStage {
        val stage = CrossfeedStage(rate, 2)
        assertTrue(stage.apply(settings) is CrossfeedResult.Applied)
        val fade = (rate / 100.0).toInt()
        stage.process(FloatArray(fade * 2), fade)
        return stage
    }

    // --- Independent reference: Bauer's analog filters with bs2b's level mapping, written out
    // again, evaluated at the bilinear transform's frequency K*tan(w/2) in Float64 complex numbers.
    private data class C(val re: Double, val im: Double) {
        operator fun plus(o: C) = C(re + o.re, im + o.im)
        operator fun minus(o: C) = C(re - o.re, im - o.im)
        operator fun times(k: Double) = C(re * k, im * k)
        operator fun div(o: C): C {
            val d = o.re * o.re + o.im * o.im
            return C((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
        }
        fun abs() = sqrt(re * re + im * im)
    }

    private class Bauer(cutoffHz: Int, levelDb: Double) {
        val lowDb = -5 * levelDb / 6 - 3
        val lowGain = 10.0.pow(lowDb / 20)
        val highCut = 1 - 10.0.pow((levelDb / 6 - 3) / 20)
        val lowHz = cutoffHz.toDouble()
        val highHz = cutoffHz * 2.0.pow((lowDb - 20 * log10(highCut)) / 12)
        val gain = 1 / (1 - highCut + lowGain)
    }

    private fun analogFrequency(cornerHz: Double, hz: Double, rate: Int): Double {
        val w = 2 * PI * cornerHz
        return w / tan(w / (2.0 * rate)) * tan(PI * hz / rate)
    }

    /** Direct path g*(s + wh*(1 - cut))/(s + wh) and cross path g*lowGain*wl/(s + wl). */
    private fun reference(b: Bauer, hz: Double, rate: Int): Pair<C, C> {
        val wh = 2 * PI * b.highHz
        val wl = 2 * PI * b.lowHz
        val sh = C(0.0, analogFrequency(b.highHz, hz, rate))
        val sl = C(0.0, analogFrequency(b.lowHz, hz, rate))
        val direct = (sh + C(wh * (1 - b.highCut), 0.0)) / (sh + C(wh, 0.0)) * b.gain
        val cross = C(b.gain * b.lowGain * wl, 0.0) / (sl + C(wl, 0.0))
        return direct to cross
    }

    private fun dft(x: DoubleArray, hz: Double, rate: Int): C {
        var re = 0.0
        var im = 0.0
        for (n in x.indices) {
            val p = 2 * PI * hz * n / rate
            re += x[n] * cos(p)
            im -= x[n] * sin(p)
        }
        return C(re, im)
    }

    /** Direct and cross impulse responses actually produced by the stage (impulse on L). */
    private fun impulseResponses(cutoffHz: Int, levelDb: Double, rate: Int, frames: Int):
        Pair<DoubleArray, DoubleArray> {
        val input = FloatArray(frames * 2)
        input[0] = 1f
        val out = run(settled(CrossfeedSettings(true, cutoffHz, levelDb), rate), input)
        return DoubleArray(frames) { out[2 * it].toDouble() } to DoubleArray(frames) { out[2 * it + 1].toDouble() }
    }

    @Test
    fun processedImpulseResponseMatchesTheIndependentReference() {
        val hz = listOf(20.0, 100.0, 300.0, 700.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0)
        var worst = 0.0
        for (rate in listOf(44_100, 48_000, 96_000, 192_000, 384_000)) for ((cutoff, level) in corners) {
            // 0.35 s: the slowest pole (300 Hz) has decayed by exp(-660).
            val (direct, cross) = impulseResponses(cutoff, level, rate, (rate * 0.35).toInt())
            val b = Bauer(cutoff, level)
            for (f in hz) {
                val (wantDirect, wantCross) = reference(b, f, rate)
                val errors = listOf((dft(direct, f, rate) - wantDirect).abs(),
                    (dft(cross, f, rate) - wantCross).abs())
                worst = maxOf(worst, errors.max())
                assertTrue("$rate $cutoff/$level $f Hz $errors", errors.all { it < 1e-6 })
            }
        }
        println("crossfeed impulse response: worst |H - Href| = $worst")
    }

    @Test
    fun responseHasTheStatedLevelCornerAndUnityInPhaseGainAtDc() {
        for ((cutoff, level) in corners) {
            val b = Bauer(cutoff, level)
            val (direct, cross) = reference(b, 0.0, fs)
            assertEquals(1.0, (direct + cross).abs(), 1e-12)
            assertEquals(-level, 20 * log10(cross.abs() / direct.abs()), 1e-9)
            // The prewarped cross path is exactly 3 dB down at the cutoff.
            assertEquals(cross.abs() / sqrt(2.0), reference(b, cutoff.toDouble(), fs).second.abs(), 1e-12)
            // And the stage's own impulse response agrees at DC.
            val (d, c) = impulseResponses(cutoff, level, fs, (fs * 0.35).toInt())
            assertEquals(-level, 20 * log10(c.sum() / d.sum()), 1e-4)
            assertEquals(1.0, d.sum() + c.sum(), 1e-5)
        }
    }

    @Test
    fun notStereoIsRejectedWithItsReasonAndLeavesSamplesAlone() {
        for (channels in listOf(1, 6)) {
            val stage = CrossfeedStage(fs, channels)
            assertEquals(R.NOT_STEREO, stage.apply(CrossfeedSettings(true)))
            assertEquals(CrossfeedResult.Applied(0.0), stage.apply(CrossfeedSettings(false)))
            val input = FloatArray(480 * channels) { (sin(it * 0.3) * 0.7).toFloat() }
            val out = input.copyOf()
            stage.process(out, 480)
            assertArrayEquals(input, out, 0f)
        }
    }

    @Test
    fun boundaryValuesAreAcceptedAndOthersRejectedWithTheirReason() {
        val stage = CrossfeedStage(fs, 2)
        for ((cutoff, level) in corners) {
            assertTrue(stage.apply(CrossfeedSettings(true, cutoff, level)) is CrossfeedResult.Applied)
        }
        val bad = mapOf(
            CrossfeedSettings(true, 299) to R.CUTOFF_OUT_OF_RANGE,
            CrossfeedSettings(true, 2_001) to R.CUTOFF_OUT_OF_RANGE,
            CrossfeedSettings(true, -700) to R.CUTOFF_OUT_OF_RANGE,
            CrossfeedSettings(true, 700, 0.999) to R.LEVEL_OUT_OF_RANGE,
            CrossfeedSettings(true, 700, 15.001) to R.LEVEL_OUT_OF_RANGE,
            CrossfeedSettings(true, 700, Double.NaN) to R.NON_FINITE,
            CrossfeedSettings(true, 700, Double.POSITIVE_INFINITY) to R.NON_FINITE,
        )
        for ((s, reason) in bad) assertEquals(s.toString(), reason, stage.apply(s))
        // Switching off is never rejected, whatever values the saved setting carries.
        for (off in listOf(CrossfeedSettings(false, 100), CrossfeedSettings(false, 700, Double.NaN),
            CrossfeedSettings(false, 2_001, -1.0))) {
            assertEquals(off.toString(), CrossfeedResult.Applied(0.0), stage.apply(off))
        }
        // The high-boost corner (up to about 3.47 kHz) must stay at or below 0.45 Fs.
        assertTrue(CrossfeedStage(8_000, 2).apply(CrossfeedSettings(true, 2_000, 15.0)) is
            CrossfeedResult.Applied)
        assertEquals(R.CUTOFF_OUT_OF_RANGE, CrossfeedStage(7_000, 2).apply(CrossfeedSettings(true, 2_000, 15.0)))
        assertTrue(CrossfeedStage(7_000, 2).apply(CrossfeedSettings(true, 700, 4.5)) is CrossfeedResult.Applied)
    }

    @Test
    fun aRejectedSettingKeepsThePreviousOneBitForBitEvenDuringAFade() {
        val input = FloatArray(4800 * 2) { (sin(it * 0.013) * 0.5 + sin(it * 0.29) * 0.3).toFloat() }
        val reference = CrossfeedStage(fs, 2).apply { apply(CrossfeedSettings(true)) }
        val probed = CrossfeedStage(fs, 2).apply { apply(CrossfeedSettings(true)) }
        val want = run(reference, input, block = 240)
        val got = input.copyOf()
        val buffer = FloatArray(480)
        for (b in 0 until 20) {
            System.arraycopy(got, b * 480, buffer, 0, 480)
            probed.process(buffer, 240)
            System.arraycopy(buffer, 0, got, b * 480, 480)
            assertEquals(R.LEVEL_OUT_OF_RANGE, probed.apply(CrossfeedSettings(true, 700, 30.0)))
            assertEquals(R.NON_FINITE, probed.apply(CrossfeedSettings(true, 700, Double.NaN)))
        }
        assertArrayEquals(want, got, 0f)
    }

    /** Bauer filters in direct form I, Float64, one channel pair; used as the fade reference. */
    private class ReferenceBranch(settings: CrossfeedSettings, rate: Int) {
        private val coefficients: DoubleArray = if (!settings.enabled) doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        else {
            val b = Bauer(settings.cutoffHz, settings.levelDb)
            fun section(cornerHz: Double, s1: Double, s0: Double): List<Double> {
                val w = 2 * PI * cornerHz
                val k = w / tan(w / (2.0 * rate))
                return listOf((s1 * k + s0) / (k + w), (s0 - s1 * k) / (k + w), (w - k) / (k + w))
            }
            (section(b.highHz, b.gain, b.gain * 2 * PI * b.highHz * (1 - b.highCut)) +
                section(b.lowHz, 0.0, b.gain * b.lowGain * 2 * PI * b.lowHz)).toDoubleArray()
        }
        private val x1 = DoubleArray(2)
        private val yd = DoubleArray(2)
        private val yc = DoubleArray(2)

        fun step(l: Double, r: Double): Pair<Double, Double> {
            val c = coefficients
            val x = doubleArrayOf(l, r)
            val direct = DoubleArray(2) { c[0] * x[it] + c[1] * x1[it] - c[2] * yd[it] }
            val cross = DoubleArray(2) { c[3] * x[it] + c[4] * x1[it] - c[5] * yc[it] }
            for (i in 0..1) {
                yd[i] = direct[i]
                yc[i] = cross[i]
                x1[i] = x[i]
            }
            return direct[0] + cross[1] to direct[1] + cross[0]
        }
    }

    @Test
    fun aChangeCrossfadesTheOldAndANewZeroStateFilterOver10Ms() {
        val first = CrossfeedSettings(true, 700, 4.5)
        val second = CrossfeedSettings(true, 2_000, 1.0)
        val stage = CrossfeedStage(fs, 2)
        stage.apply(first)
        val frames = 4800
        val input = FloatArray(frames * 2) {
            val n = it / 2
            (if (it % 2 == 0) 0.6 * sin(2 * PI * 440 * n / fs) else 0.4 * sin(2 * PI * 1_250 * n / fs)).toFloat()
        }
        val out = FloatArray(frames * 2)
        val buffer = FloatArray(480 * 2)
        val old = ReferenceBranch(CrossfeedSettings(false), fs)
        var current = ReferenceBranch(first, fs)
        var next: ReferenceBranch? = null
        var startedAt = 0
        val want = DoubleArray(frames * 2)
        for (block in 0 until 10) {
            if (block == 5) stage.apply(second)
            System.arraycopy(input, block * 960, buffer, 0, 960)
            stage.process(buffer, 480)
            System.arraycopy(buffer, 0, out, block * 960, 960)
            if (block == 5) {
                next = ReferenceBranch(second, fs)
                startedAt = block * 480
            }
            for (f in block * 480 until (block + 1) * 480) {
                val l = input[2 * f].toDouble()
                val r = input[2 * f + 1].toDouble()
                // Block 0 fades flat -> first; block 5 fades first -> second.
                val (fromBranch, toBranch, w) = when {
                    f < 480 -> Triple(old, current, (f + 1) / 480.0)
                    next != null && f - startedAt < 480 -> Triple(current, next, (f - startedAt + 1) / 480.0)
                    next != null -> Triple(next, next, 1.0)
                    else -> Triple(current, current, 1.0)
                }
                val a = fromBranch.step(l, r)
                val b = if (toBranch === fromBranch) a else toBranch.step(l, r)
                want[2 * f] = (1 - w) * a.first + w * b.first
                want[2 * f + 1] = (1 - w) * a.second + w * b.second
            }
        }
        for (i in want.indices) assertEquals("sample $i", want[i], out[i].toDouble(), 2e-6)
    }

    @Test
    fun noJumpWhenSwitchingBetweenOffAndTheStrongestSetting() {
        val strong = CrossfeedSettings(true, 2_000, 1.0)
        fun sine(n: Int, c: Int) = if (c == 0) 0.7 * sin(2 * PI * 1_000 * n / fs) else 0.0
        val frames = 9600
        val input = FloatArray(frames * 2) { sine(it / 2, it % 2).toFloat() }
        fun maxStep(x: FloatArray, from: Int, to: Int) = (from + 1 until to).maxOf { n ->
            maxOf(abs(x[2 * n] - x[2 * n - 2]), abs(x[2 * n + 1] - x[2 * n - 1])).toDouble()
        }
        // Steady-state steps of each setting alone.
        val steady = maxOf(maxStep(run(settled(strong), input), 4800, frames),
            maxStep(input, 1, frames))
        for ((a, b) in listOf(CrossfeedSettings(false) to strong, strong to CrossfeedSettings(false))) {
            val stage = settled(a)
            val out = input.copyOf()
            val buffer = FloatArray(960)
            for (block in 0 until 20) {
                if (block == 10) stage.apply(b)
                System.arraycopy(out, block * 960, buffer, 0, 960)
                stage.process(buffer, 480)
                System.arraycopy(buffer, 0, out, block * 960, 960)
            }
            val switching = maxStep(out, 4800 - 10, 4800 + 960)
            assertTrue("$a -> $b $switching vs $steady", switching <= 1.1 * steady)
        }
    }

    @Test
    fun aSettingArrivingDuringAFadeWaitsAndTheLatestOneWins() {
        val latest = CrossfeedSettings(true, 2_000, 15.0)
        val input = FloatArray(4800 * 2) { (sin(it * 0.021) * 0.5).toFloat() }
        val stage = CrossfeedStage(fs, 2)
        stage.apply(CrossfeedSettings(true))
        val first = input.copyOfRange(0, 480)
        stage.process(first, 240)
        stage.apply(CrossfeedSettings(true, 300, 1.0))
        stage.apply(latest)
        val second = input.copyOfRange(480, 960)
        stage.process(second, 240)
        val rest = run(stage, input.copyOfRange(960, input.size))
        // Same history, then `latest` from frame 480 on: the 300 Hz setting never took effect.
        val reference = CrossfeedStage(fs, 2)
        reference.apply(CrossfeedSettings(true))
        val head = run(reference, input.copyOfRange(0, 960))
        reference.apply(latest)
        val tail = run(reference, input.copyOfRange(960, input.size))
        assertArrayEquals(head + tail, first + second + rest, 0f)
    }

    @Test
    fun switchingOffWithOutOfRangeSavedValuesStillTurnsCrossfeedOff() {
        val stage = settled(CrossfeedSettings(true, 700, 4.5))
        assertEquals(CrossfeedResult.Applied(0.0), stage.apply(CrossfeedSettings(false, 250)))
        stage.process(FloatArray(480 * 2), 480)
        val input = FloatArray(960 * 2) { (sin(it * 0.17) * 0.8).toFloat() }
        assertArrayEquals(input, run(stage, input), 0f)
    }

    @Test
    fun maxPositiveGainIsTheMeasuredSumOfTheImpulseResponses() {
        // The sum of |h| over both paths bounds every input, including different frequencies on
        // the two channels. It is compared with the stage's actual impulse responses.
        var worst = 0.0
        for (rate in listOf(8_000, 44_100, 48_000, 384_000)) for ((cutoff, level) in corners) {
            val applied = CrossfeedStage(rate, 2).apply(CrossfeedSettings(true, cutoff, level))
                as CrossfeedResult.Applied
            val (d, c) = impulseResponses(cutoff, level, rate, (rate * 0.35).toInt())
            val measured = 20 * log10(d.sumOf { abs(it) } + c.sumOf { abs(it) })
            assertEquals("$rate $cutoff/$level", measured, applied.maxPositiveGainDb, 0.01)
            worst = maxOf(worst, applied.maxPositiveGainDb)
            if (rate == fs) {
                // Below Fs/4 the bilinear transform keeps it at or below the analog g*(1 + GH + GL).
                val b = Bauer(cutoff, level)
                assertTrue(applied.maxPositiveGainDb <=
                    20 * log10(b.gain * (1 + b.highCut + b.lowGain)) + 1e-6)
            }
        }
        println("crossfeed max positive gain: worst $worst dB")
        assertTrue("$worst", worst in 2.5..4.0)
    }

    /** Records the largest |sample| that reaches it, after every stage before the clip guard. */
    private class Probe(var skip: Int) : DspStage {
        var peak = 0.0
        override fun process(samples: FloatArray, frames: Int) {
            if (skip > 0) {
                skip -= frames
                return
            }
            for (i in 0 until frames * 2) peak = maxOf(peak, abs(samples[i].toDouble()))
        }
    }

    @Test
    fun differentFullScaleFrequenciesOnTheTwoSidesStayAtOrBelowFullScaleBeforeTheGuard() {
        for ((cutoff, level) in corners) {
            val crossfeed = CrossfeedStage(fs, 2)
            val gain = (crossfeed.apply(CrossfeedSettings(true, cutoff, level)) as CrossfeedResult.Applied)
                .maxPositiveGainDb
            val probe = Probe(fs / 2)
            val chain = (DspChain.open(DspConfig(OutputMode.PROCESSED, fs, 2, DspOutput.FLOAT32, 0.0,
                gain, headroomAttenuationDb = requiredHeadroomAttenuationDb(gain, 1.0),
                stages = DspStages(crossfeed = crossfeed, apodizing = probe))) as DspOpenResult.Ready).chain
            val frames = 480
            val out = java.nio.ByteBuffer.allocate(frames * 8)
            for (block in 0 until 100) {
                // L = 10 kHz, R = 50 Hz, both at 0 dBFS: high-boost and low pass add up above 1.
                val x = FloatArray(frames * 2) {
                    val n = block * frames + it / 2
                    sin(2 * PI * (if (it % 2 == 0) 10_000.0 else 50.0) * n / fs).toFloat()
                }
                out.clear()
                assertEquals(DspBlockResult.PROCESSED, chain.process(x, frames, out))
            }
            // Without the attenuation this input would exceed full scale.
            val unattenuated = probe.peak * 10.0.pow(requiredHeadroomAttenuationDb(gain, 1.0) / 20)
            assertTrue("$cutoff/$level ${probe.peak}", probe.peak <= 1.0 + 1e-6)
            assertTrue("$cutoff/$level $unattenuated", unattenuated > 1.0 || level == 15.0)
        }
    }

    @Test
    fun chainOutsideTheProcessedPathNeverRunsTheseStages() {
        var calls = 0
        val counting = DspStage { _, _ -> calls++ }
        val channelStage = ChannelStage(fs, 2).apply { apply(ChannelSettings(swap = true)) }
        val crossfeedStage = CrossfeedStage(fs, 2).apply { apply(CrossfeedSettings(true)) }
        val channels = DspStage { samples, frames -> calls++; channelStage.process(samples, frames) }
        val crossfeed = DspStage { samples, frames -> calls++; crossfeedStage.process(samples, frames) }
        for (mode in OutputMode.entries - OutputMode.PROCESSED) {
            val result = DspChain.open(DspConfig(mode, fs, 2, DspOutput.INT32, 0.0, 0.0,
                stages = DspStages(map = counting, channels = channels, crossfeed = crossfeed,
                    apodizing = counting)))
            assertEquals(mode.toString(), DspOpenResult.Rejected.NOT_PROCESSED_PATH, result)
        }
        assertEquals(0, calls)
    }

    @Test
    fun dspSettingsMapToCrossfeedSettings() {
        assertEquals(CrossfeedSettings(false, 700, 4.5), DspSettings().toCrossfeedSettings())
        assertEquals(CrossfeedSettings(true, 650, 9.5), DspSettings(crossfeedEnabled = true,
            crossfeedCutoffHz = 650, crossfeedLevelDb = 9.5).toCrossfeedSettings())
        assertEquals(CrossfeedSettings(false, 2_000, 1.0), DspSettings(crossfeedEnabled = false,
            crossfeedCutoffHz = 2_000, crossfeedLevelDb = 1.0).toCrossfeedSettings())
    }
}
