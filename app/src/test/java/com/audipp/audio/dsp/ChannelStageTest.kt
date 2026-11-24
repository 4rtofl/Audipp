package com.audipp.audio.dsp

import com.audipp.domain.device.ChannelMode
import com.audipp.domain.device.DspSettings
import com.audipp.domain.device.EqBandType
import com.audipp.domain.device.OutputMode
import com.audipp.audio.dsp.ChannelResult.Rejected as R
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelStageTest {
    private val fs = 48_000
    private val fade = 480

    private fun stage(settings: ChannelSettings, channels: Int = 2): ChannelStage {
        val stage = ChannelStage(fs, channels)
        assertTrue(stage.apply(settings) is ChannelResult.Applied)
        // Let the 10 ms move away from the identity finish on silence.
        stage.process(FloatArray(fade * channels), fade)
        return stage
    }

    private fun run(stage: DspStage, input: FloatArray, channels: Int, block: Int = 480): FloatArray {
        val out = input.copyOf()
        val frames = input.size / channels
        val buffer = FloatArray(block * channels)
        var start = 0
        while (start < frames) {
            val n = min(block, frames - start)
            System.arraycopy(out, start * channels, buffer, 0, n * channels)
            stage.process(buffer, n)
            System.arraycopy(buffer, 0, out, start * channels, n * channels)
            start += n
        }
        return out
    }

    private fun db(x: Double) = 10.0.pow(x / 20.0)

    /** The definition, written out in Float64: swap, then mono as (L+R)/2, then each side's gain. */
    private fun expected(s: ChannelSettings, l: Double, r: Double): Pair<Double, Double> {
        var a = if (s.swap) r else l
        var b = if (s.swap) l else r
        if (s.mono) {
            val m = (a + b) / 2
            a = m
            b = m
        }
        return a * db(s.leftGainDb) to b * db(s.rightGainDb)
    }

    private val impulses = listOf(
        "in phase" to (1.0 to 1.0), "anti phase" to (1.0 to -1.0),
        "left only" to (1.0 to 0.0), "right only" to (0.0 to 1.0),
    )

    private val settings = listOf(
        ChannelSettings(), ChannelSettings(swap = true), ChannelSettings(mono = true),
        ChannelSettings(leftGainDb = 6.0, rightGainDb = -6.0),
        ChannelSettings(swap = true, leftGainDb = -3.0, rightGainDb = 2.0),
        ChannelSettings(mono = true, leftGainDb = 12.0, rightGainDb = -12.0),
        ChannelSettings(swap = true, mono = true, rightGainDb = 1.5),
    )

    @Test
    fun inPhaseAntiPhaseAndOneSidedImpulsesMatchTheDefinition() {
        for (s in settings) for ((name, impulse) in impulses) {
            val input = FloatArray(8 * 2)
            input[2] = impulse.first.toFloat()
            input[3] = impulse.second.toFloat()
            val out = run(stage(s), input, 2)
            val (l, r) = expected(s, impulse.first, impulse.second)
            val want = FloatArray(16)
            want[2] = l.toFloat()
            want[3] = r.toFloat()
            assertArrayEquals("$s $name", want, out, 1e-7f)
        }
        // Mono cancels an anti-phase pair completely and halves a one-sided impulse.
        val mono = run(stage(ChannelSettings(mono = true)), floatArrayOf(0.8f, -0.8f, 0.8f, 0f), 2)
        assertArrayEquals(floatArrayOf(0f, 0f, 0.4f, 0.4f), mono, 0f)
    }

    @Test
    fun fivePointOneSwapsAndGainsBothPairsAndLeavesCentreAndLfe() {
        val s = ChannelSettings(swap = true, leftGainDb = 6.0, rightGainDb = -6.0)
        // FL FR FC LFE SL SR, Android's order for both 5.1 masks.
        val out = run(stage(s, channels = 6), floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f), 6)
        val want = floatArrayOf(
            (0.2 * db(6.0)).toFloat(), (0.1 * db(-6.0)).toFloat(), 0.3f, 0.4f,
            (0.6 * db(6.0)).toFloat(), (0.5 * db(-6.0)).toFloat())
        assertArrayEquals(want, out, 1e-7f)
        val mono = run(stage(ChannelSettings(mono = true), channels = 6),
            floatArrayOf(0.1f, 0.3f, 0.9f, -0.9f, 0.5f, -0.5f), 6)
        assertArrayEquals(floatArrayOf(0.2f, 0.2f, 0.9f, -0.9f, 0f, 0f), mono, 1e-7f)
    }

    @Test
    fun monoLayoutHasNoLeftRightPairAndRejectsAnythingButTheIdentity() {
        val stage = ChannelStage(fs, 1)
        assertEquals(ChannelResult.Applied(0.0), stage.apply(ChannelSettings()))
        for (s in listOf(ChannelSettings(swap = true), ChannelSettings(mono = true),
            ChannelSettings(leftGainDb = 1.0), ChannelSettings(rightGainDb = -1.0))) {
            assertEquals(s.toString(), R.NO_LEFT_RIGHT_PAIR, stage.apply(s))
        }
        val input = floatArrayOf(0.25f, -0.5f, 1f)
        assertArrayEquals(input, run(stage, input, 1), 0f)
    }

    @Test
    fun identityPassesStereoThroughBitForBit() {
        val input = FloatArray(960 * 2) { (sin(it * 0.37) * 0.9).toFloat() }
        val stage = ChannelStage(fs, 2)
        stage.apply(ChannelSettings())
        assertArrayEquals(input, run(stage, input, 2), 0f)
    }

    @Test
    fun boundariesAreAcceptedAndValuesOutsideAreRejected() {
        val stage = ChannelStage(fs, 2)
        assertEquals(ChannelResult.Applied(12.0), stage.apply(ChannelSettings(leftGainDb = 12.0,
            rightGainDb = -12.0)))
        assertEquals(ChannelResult.Applied(12.0), stage.apply(ChannelSettings(leftGainDb = -12.0,
            rightGainDb = 12.0)))
        assertEquals(ChannelResult.Applied(0.0), stage.apply(ChannelSettings(leftGainDb = -12.0,
            rightGainDb = -12.0)))
        val bad = mapOf(
            ChannelSettings(leftGainDb = 12.000001) to R.GAIN_OUT_OF_RANGE,
            ChannelSettings(rightGainDb = -12.000001) to R.GAIN_OUT_OF_RANGE,
            ChannelSettings(leftGainDb = Double.NaN) to R.NON_FINITE,
            ChannelSettings(rightGainDb = Double.POSITIVE_INFINITY) to R.NON_FINITE,
            ChannelSettings(leftGainDb = Double.NEGATIVE_INFINITY) to R.NON_FINITE,
        )
        for ((s, reason) in bad) assertEquals(s.toString(), reason, stage.apply(s))
    }

    @Test
    fun aRejectedSettingKeepsThePreviousOneBitForBit() {
        val input = FloatArray(2400 * 2) { (sin(it * 0.11) * 0.5).toFloat() }
        val reference = stage(ChannelSettings(swap = true, leftGainDb = 3.0))
        val probed = stage(ChannelSettings(swap = true, leftGainDb = 3.0))
        val want = run(reference, input, 2)
        assertEquals(R.NON_FINITE, probed.apply(ChannelSettings(rightGainDb = Double.NaN)))
        assertEquals(R.GAIN_OUT_OF_RANGE, probed.apply(ChannelSettings(mono = true, leftGainDb = 20.0)))
        assertArrayEquals(want, run(probed, input, 2), 0f)
    }

    @Test
    fun aChangeMovesLinearlyFromTheOldToTheNewMatrixOver10Ms() {
        val stage = ChannelStage(fs, 2)
        stage.apply(ChannelSettings(swap = true))
        val frames = 1000
        val input = FloatArray(frames * 2) { if (it % 2 == 0) 0.75f else -0.25f }
        val out = run(stage, input, 2, block = 333)
        for (n in 0 until frames) {
            val w = min(1.0, (n + 1).toDouble() / fade)
            assertEquals("L $n", (1 - w) * 0.75 + w * -0.25, out[2 * n].toDouble(), 1e-7)
            assertEquals("R $n", (1 - w) * -0.25 + w * 0.75, out[2 * n + 1].toDouble(), 1e-7)
        }
        // The largest step is one fade increment of the gap, 1/480 of 1.0.
        var step = 0.0
        for (n in 1 until frames) step = maxOf(step, abs(out[2 * n] - out[2 * n - 2]).toDouble())
        assertTrue(step <= 1.0 / fade + 1e-6)
    }

    @Test
    fun aSettingArrivingDuringAFadeWaitsAndTheLatestOneWins() {
        val stage = ChannelStage(fs, 2)
        stage.apply(ChannelSettings(swap = true))
        val first = FloatArray(240 * 2) { if (it % 2 == 0) 1f else 0f }
        stage.process(first, 240)
        stage.apply(ChannelSettings(leftGainDb = -6.0))
        stage.apply(ChannelSettings(mono = true))
        val rest = run(stage, FloatArray(1200 * 2) { if (it % 2 == 0) 1f else 0f }, 2)
        // The swap fade ends inside the first block of `rest`; the mono fade starts at the next one,
        // from the swap matrix, and the -6 dB setting it replaced never takes effect.
        assertEquals((240 + 1) / 480.0, 1 - rest[0].toDouble(), 1e-7)
        assertEquals(0.0, rest[2 * 239].toDouble(), 1e-7)
        assertEquals(0.0, rest[2 * 479].toDouble(), 0.0)
        assertEquals(1.0 / 480 * 0.5, rest[2 * 480].toDouble(), 1e-7)
        assertEquals(0.5, rest[2 * 1199].toDouble(), 1e-7)
        assertEquals(0.5, rest[2 * 1199 + 1].toDouble(), 1e-7)
    }

    @Test
    fun maxPositiveGainIsTheLargerSideGainAndBoundsEveryFullScaleInput() {
        for (s in settings) {
            val applied = ChannelStage(fs, 2).apply(s) as ChannelResult.Applied
            assertEquals(s.toString(), maxOf(0.0, s.leftGainDb, s.rightGainDb), applied.maxPositiveGainDb,
                0.0)
            val attenuation = db(-applied.maxPositiveGainDb)
            var peak = 0.0
            for (l in listOf(-1.0, 0.0, 1.0)) for (r in listOf(-1.0, 0.0, 1.0)) {
                val (a, b) = expected(s, l, r)
                peak = maxOf(peak, abs(a) * attenuation, abs(b) * attenuation)
            }
            assertTrue("$s $peak", peak <= 1.0 + 1e-12)
        }
    }

    /** Records the largest |sample| that reaches it, after every stage before the clip guard. */
    private class Probe : DspStage {
        var peak = 0.0
        var skip = 0
        override fun process(samples: FloatArray, frames: Int) {
            if (skip > 0) {
                skip -= frames
                return
            }
            for (i in 0 until frames * 2) peak = maxOf(peak, abs(samples[i].toDouble()))
        }
    }

    @Test
    fun cumulativeGainMonoAndCrossfeedStayAtOrBelowFullScaleBeforeTheGuard() {
        val channels = ChannelStage(fs, 2)
        val crossfeed = CrossfeedStage(fs, 2)
        val peq = PeqStage(fs, 2)
        val gains = listOf(
            (channels.apply(ChannelSettings(mono = true, leftGainDb = 6.0, rightGainDb = 3.0))
                as ChannelResult.Applied).maxPositiveGainDb,
            (crossfeed.apply(CrossfeedSettings(true, 2_000, 1.0)) as CrossfeedResult.Applied)
                .maxPositiveGainDb,
            (peq.apply(PeqPreset(fs, listOf(PeqBand(EqBandType.LOW_SHELF, 200.0, 6.0, 0.7)), 0.0))
                as PeqResult.Applied).maxPositiveGainDb,
        )
        val total = gains.sum()
        val required = requiredHeadroomAttenuationDb(total, 1.0)
        fun open(attenuation: Double, probe: Probe) = DspChain.open(DspConfig(OutputMode.PROCESSED,
            fs, 2, DspOutput.FLOAT32, 0.0, total, headroomAttenuationDb = attenuation,
            stages = DspStages(channels = channels, peq = peq, crossfeed = crossfeed, apodizing = probe)))
        assertEquals(DspOpenResult.Rejected.HEADROOM_INSUFFICIENT, open(required - 0.01, Probe()))
        // Full-scale 50 Hz in phase, anti phase and on one side; the peak is read in steady state.
        for ((l, r) in listOf(1.0 to 1.0, 1.0 to -1.0, 1.0 to 0.0, 0.0 to 1.0)) {
            val probe = Probe().apply { skip = fs / 2 }
            val chain = (open(required, probe) as DspOpenResult.Ready).chain
            val frames = 480
            val out = ByteBuffer.allocate(frames * 8)
            for (block in 0 until 100) {
                val x = FloatArray(frames * 2) {
                    val n = block * frames + it / 2
                    (sin(2 * PI * 50 * n / fs) * if (it % 2 == 0) l else r).toFloat()
                }
                out.clear()
                assertEquals(DspBlockResult.PROCESSED, chain.process(x, frames, out))
            }
            assertTrue("$l/$r ${probe.peak}", probe.peak <= 1.0 + 1e-6)
            // Adding the stages' peaks in dB is an upper bound; here it leaves about 1.3 dB unused.
            if (l == r) assertTrue("in phase comes near the bound ${probe.peak}", probe.peak > 0.8)
        }
    }

    @Test
    fun dspSettingsMapToChannelSettingsWithoutChangingValues() {
        assertEquals(ChannelSettings(), DspSettings().toChannelSettings())
        assertEquals(ChannelSettings(swap = true, leftGainDb = -1.0, rightGainDb = 2.0),
            DspSettings(channelMode = ChannelMode.SWAP_STEREO, leftGainDb = -1.0, rightGainDb = 2.0)
                .toChannelSettings())
        assertEquals(ChannelSettings(mono = true),
            DspSettings(channelMode = ChannelMode.MONO).toChannelSettings())
        assertEquals(ChannelSettings(), DspSettings(channelMode = ChannelMode.DOWNMIX_5_1).toChannelSettings())
    }
}
