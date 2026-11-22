package com.audipp.audio.dsp

import com.audipp.domain.device.OutputMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspChainTest {
    private val fs = 48_000

    private fun config(
        output: DspOutput = DspOutput.FLOAT32,
        gainDb: Double = 0.0,
        maxPositiveGainDb: Double = 0.0,
        attenuationDb: Double = 0.0,
        stages: DspStages = DspStages(),
        mode: OutputMode = OutputMode.PROCESSED,
        channels: Int = 1,
        rate: Int = fs,
    ) = DspConfig(mode, rate, channels, output, gainDb, maxPositiveGainDb,
        headroomAttenuationDb = attenuationDb, stages = stages)

    private fun chain(config: DspConfig) = (DspChain.open(config) as DspOpenResult.Ready).chain

    private fun floats(bytes: ByteBuffer, count: Int): FloatArray {
        val view = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(0)
        return FloatArray(count) { view.getFloat() }
    }

    /** Runs [input] (one channel) through the chain in one block and returns the float output. */
    private fun run(chain: DspChain, input: FloatArray): FloatArray {
        val out = ByteBuffer.allocate(input.size * 4)
        assertEquals(DspBlockResult.PROCESSED, chain.process(input.copyOf(), input.size, out))
        return floats(out, input.size)
    }

    // Independent true peak: 16x Hann-windowed sinc (+-128 samples), Float64.
    private fun truePeakDb(x: FloatArray): Double {
        var peak = x.maxOf { abs(it.toDouble()) }
        val reach = 128
        for (n in reach until x.size - reach) for (k in 1 until 16) {
            val frac = k / 16.0
            var v = 0.0
            for (i in -reach + 1..reach) {
                val d = i - frac
                v += x[n + i] * sin(PI * d) / (PI * d) * 0.5 * (1 + kotlin.math.cos(PI * d / reach))
            }
            peak = maxOf(peak, abs(v))
        }
        return 20 * log10(peak)
    }

    @Test
    fun stagesRunInTheFixedOrderAndOnlyConfiguredStagesRun() {
        val calls = mutableListOf<String>()
        fun stage(name: String) = DspStage { _, _ -> calls += name }
        // Declared out of order on purpose; the slots, not the call site, fix the order.
        val chain = chain(config(stages = DspStages(
            src = stage("src"), apodizing = stage("apodizing"), crossfeed = stage("crossfeed"),
            peq = stage("peq"), map = stage("map"),
        )))
        chain.process(floatArrayOf(0.5f), 1, ByteBuffer.allocate(4))
        assertEquals(listOf("map", "peq", "crossfeed", "apodizing", "src"), calls)
    }

    @Test
    fun gainStageComesAfterMapAndBeforePeq() {
        val seenByMap = mutableListOf<Float>()
        val seenByPeq = mutableListOf<Float>()
        val chain = chain(config(gainDb = -20.0,
            stages = DspStages(map = DspStage { s, _ -> seenByMap += s[0] },
                peq = DspStage { s, _ -> seenByPeq += s[0] })))
        chain.process(floatArrayOf(0.5f), 1, ByteBuffer.allocate(4))
        assertEquals(0.5f, seenByMap.single(), 0f)
        assertEquals(0.05f, seenByPeq.single(), 1e-7f)
    }

    @Test
    fun clipGuardRunsAfterTheLastStage() {
        // SRC stage quadruples a 0.5 tone to 2.0; only a guard placed after it can hold -1 dBTP.
        val tone = FloatArray(4_800) { (0.5 * sin(2 * PI * 1_000 * it / fs)).toFloat() }
        val out = run(chain(config(stages = DspStages(src = DspStage { s, f ->
            for (i in 0 until f) s[i] *= 4f }))), tone)
        assertTrue(truePeakDb(out.copyOfRange(2_400, 4_800)) <= -1.0 + 0.2)
    }

    @Test
    fun headroomFollowsMaxPositiveGainAndIrCoefficientSum() {
        // Expected values computed independently: 6 + 20*log10(2) = 12.0206.
        assertEquals(12.0206, requiredHeadroomAttenuationDb(6.0, 2.0), 1e-4)
        assertEquals(6.0, requiredHeadroomAttenuationDb(6.0, 1.0), 1e-12)
        assertEquals(0.0, requiredHeadroomAttenuationDb(0.0, 0.5), 0.0)
        assertEquals(0.0, requiredHeadroomAttenuationDb(0.0, 1.0), 0.0)
    }

    @Test
    fun openChecksHeadroomBeforeEnabling() {
        fun open(attenuation: Double, gain: Double = 6.0, ir: Double = 2.0) = DspChain.open(
            config().copy(gainDb = 0.0, maxPositiveGainDb = gain, irAbsCoefficientSum = ir,
                headroomAttenuationDb = attenuation))
        assertEquals(DspOpenResult.Rejected.HEADROOM_INSUFFICIENT, open(12.0))
        assertTrue(open(12.0207) is DspOpenResult.Ready)
        assertEquals(DspOpenResult.Rejected.OVERLOAD, open(100.0, gain = 240.0))
    }

    @Test
    fun invalidParametersAreRejectedAtOpen() {
        val bad = listOf(
            config(gainDb = Double.NaN), config(gainDb = Double.POSITIVE_INFINITY),
            config(maxPositiveGainDb = Double.NaN), config(attenuationDb = -1.0),
            config(gainDb = 3.0, maxPositiveGainDb = 0.0),
            config().copy(irAbsCoefficientSum = 0.0), config().copy(irAbsCoefficientSum = Double.NaN),
        )
        for (c in bad) assertEquals(DspOpenResult.Rejected.INVALID_PARAMETER, DspChain.open(c))
        assertEquals(DspOpenResult.Rejected.UNSUPPORTED_FORMAT, DspChain.open(config(channels = 3)))
        assertEquals(DspOpenResult.Rejected.UNSUPPORTED_FORMAT, DspChain.open(config(rate = 384_001)))
    }

    @Test
    fun bitPerfectAndNativeDirectPathsNeverGetAChain() {
        // STANDARD and DIRECT_ATTEMPT carry Bit-perfect/Native/DoP requests; DSD Native/DoP have no output.
        for (mode in listOf(OutputMode.STANDARD, OutputMode.DIRECT_ATTEMPT)) {
            assertEquals(DspOpenResult.Rejected.NOT_PROCESSED_PATH, DspChain.open(config(mode = mode)))
        }
        assertEquals(setOf("INT16", "INT24", "INT32", "FLOAT32", "DSD_MODULATOR"),
            DspOutput.entries.map { it.name }.toSet())
    }

    @Test
    fun nonFiniteInputIsRejectedWithoutTouchingOutputOrState() {
        val stageCalls = mutableListOf<Int>()
        val chain = chain(config(stages = DspStages(peq = DspStage { _, f -> stageCalls += f })))
        val out = ByteBuffer.allocate(16).also { b -> repeat(16) { b.put(it, 0x55) } }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(DspBlockResult.NON_FINITE_SAMPLE,
                chain.process(floatArrayOf(0f, 0.1f, bad, 0f), 4, out))
        }
        assertTrue(stageCalls.isEmpty())
        assertTrue((0 until 16).all { out.get(it) == 0x55.toByte() })

        // The rejected blocks left no trace: this equals a fresh chain's output for the same block.
        val block = FloatArray(64) { 0.3f * sin(it * 0.3f) }
        assertArrayEquals(run(chain(config()), block), run(chain, block), 0f)
    }

    @Test
    fun overloadedStageOutputIsRejectedAndInvalidBlocksAreRefused() {
        val chain = chain(config(stages = DspStages(ir = DspStage { s, f ->
            for (i in 0 until f) s[i] = Float.POSITIVE_INFINITY })))
        val out = ByteBuffer.allocate(8)
        assertEquals(DspBlockResult.OVERLOAD, chain.process(floatArrayOf(0.1f, 0.1f), 2, out))
        assertEquals(DspBlockResult.INVALID_BLOCK, chain.process(floatArrayOf(0.1f), 2, out))
        assertEquals(DspBlockResult.INVALID_BLOCK, chain.process(floatArrayOf(0.1f, 0.1f), 2,
            ByteBuffer.allocate(7)))
        assertEquals(DspBlockResult.INVALID_BLOCK, chain.process(floatArrayOf(), 0, out))
    }

    @Test
    fun gainChangeIsSmoothedLinearlyOverTenMilliseconds() {
        for (rate in listOf(48_000, 44_100)) {
            val chain = chain(config(rate = rate, maxPositiveGainDb = 0.0))
            val warm = FloatArray(2_000) { 0.5f }
            run(chain, warm)
            assertTrue(chain.setGainDb(-6.0))
            val ramp = (rate / 100.0).toInt() // 480 / 441 frames = 10 ms
            val out = run(chain, FloatArray(ramp + chain.latencyFrames + 200) { 0.5f })
            val target = 10.0.pow(-6.0 / 20)
            // Frame k after the change carries gain 1 + (target - 1) * k / ramp, delayed by the guard.
            for (k in 1..ramp) {
                val expected = 0.5 * (1 + (target - 1) * k / ramp)
                assertEquals("rate $rate frame $k", expected, out[chain.latencyFrames + k - 1].toDouble(), 2e-7)
            }
            assertEquals(0.5 * target, out[chain.latencyFrames + ramp].toDouble(), 2e-7)
        }
    }

    @Test
    fun gainRejectsNonFiniteAndAboveHeadroomChecked() {
        val chain = chain(config(maxPositiveGainDb = 3.0, attenuationDb = 3.0))
        assertTrue(!chain.setGainDb(Double.NaN))
        assertTrue(!chain.setGainDb(Double.POSITIVE_INFINITY))
        assertTrue(!chain.setGainDb(3.5))
        assertTrue(chain.setGainDb(3.0))
    }

    @Test
    fun guardPassesQuietSignalsUnchangedAfterItsLatency() {
        val input = FloatArray(3_000) { (0.5 * sin(2 * PI * 440 * it / fs)).toFloat() }
        val chain = chain(config())
        val out = run(chain, input)
        val d = chain.latencyFrames
        assertArrayEquals(input.copyOfRange(0, input.size - d), out.copyOfRange(d, out.size), 0f)
        assertTrue(out.copyOfRange(0, d).all { it == 0f })
        assertEquals(240 + 8, d) // 5 ms at 48 kHz plus the interpolator reach
    }

    @Test
    fun guardHoldsMinusOneDbtpForToneAtEveryLevelAndNearNyquist() {
        for (freq in listOf(1_000.0, 11_000.0, 20_000.0)) for (amp in listOf(1.0, 2.0, 8.0)) {
            val tone = FloatArray(6_000) { (amp * sin(2 * PI * freq * it / fs + 0.3)).toFloat() }
            val out = run(chain(config()), tone)
            val peak = truePeakDb(out.copyOfRange(1_000, 5_800))
            // Estimator lower bound is within 0.2 dB (QLT-05 true-peak tolerance); never above target.
            assertTrue("$freq Hz x$amp: $peak dBTP", peak <= -1.0 + 0.2)
            assertTrue("$freq Hz x$amp over-attenuated: $peak dBTP", peak >= -1.0 - 0.6)
        }
    }

    @Test
    fun guardStartsReducingFiveMillisecondsBeforeAnImpulseAndKeepsItUnderTheCeiling() {
        val input = FloatArray(4_000) { 0.1f }.also { it[2_000] = 4f }
        val chain = chain(config())
        val out = run(chain, input)
        val at = 2_000 + chain.latencyFrames
        assertEquals(0.1f, out[at - 400], 0f)
        val onset = (chain.latencyFrames until at).first { abs(out[it] - 0.1f) > 1e-7f }
        val lead = at - onset // frames of lookahead before the impulse
        assertTrue("lead $lead", lead in 240..242) // 5 ms = 240 frames at 48 kHz, plus its neighbours
        assertTrue(abs(out[at]) <= 10.0.pow(-1.0 / 20))
    }

    @Test
    fun guardGainIsSharedAcrossChannels() {
        val frames = 3_000
        val phase = { n: Int -> sin(2 * PI * 1_000 * n / fs + 0.3) }
        val input = FloatArray(frames * 2) { i ->
            (if (i % 2 == 0) 2.0 else 0.1).toFloat() * phase(i / 2).toFloat()
        }
        val chain = chain(config(channels = 2))
        val out = ByteBuffer.allocate(input.size * 4)
        chain.process(input.copyOf(), frames, out)
        val y = floats(out, input.size)
        val n = 2_000
        val src = n - chain.latencyFrames
        val left = y[2 * n] / input[2 * src]
        val right = y[2 * n + 1] / input[2 * src + 1]
        assertTrue(left < 0.5f)
        assertEquals(left.toDouble(), right.toDouble(), 1e-5)
    }
}
