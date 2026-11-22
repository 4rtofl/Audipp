package com.audipp.audio.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmQuantizerTest {
    private fun codes(output: DspOutput, dither: Boolean, input: FloatArray): LongArray {
        val bytes = output.integerBits / 8
        val buffer = ByteBuffer.allocate(input.size * bytes).order(ByteOrder.LITTLE_ENDIAN)
        PcmQuantizer(output, dither).write(input.copyOf(), input.size, buffer)
        return LongArray(input.size) { i ->
            var v = 0L
            for (b in 0 until bytes) v = v or ((buffer.get(i * bytes + b).toLong() and 0xff) shl (8 * b))
            (v shl (64 - 8 * bytes)) shr (64 - 8 * bytes) // sign-extend
        }
    }

    @Test
    fun withoutDitherCodesAreRoundedClampedAndLittleEndian() {
        // Independent expectations: round-half-even of x * 2^(N-1), clamped to the N-bit range.
        val x = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 2f, 1.5f / 32768, 2.5f / 32768, -1.5f / 32768)
        assertArrayEquals(longArrayOf(0, 16384, -16384, 32767, -32768, 32767, 2, 2, -2),
            codes(DspOutput.INT16, false, x))
        assertArrayEquals(longArrayOf(0, 4194304, -4194304, 8388607, -8388608, 8388607, 384, 640, -384),
            codes(DspOutput.INT24, false, x))
        assertArrayEquals(longArrayOf(0, 1073741824, -1073741824, 2147483647, -2147483648, 2147483647,
            98304, 163840, -98304),
            codes(DspOutput.INT32, false, x))
    }

    @Test
    fun tpdfDitherLinearizesSubLsbSignalsOnSixteenAndTwentyFourBit() {
        for (output in listOf(DspOutput.INT16, DspOutput.INT24)) {
            val lsb = 1.0 / (1L shl (output.integerBits - 1))
            val input = FloatArray(200_000) { (0.25 * lsb).toFloat() }
            // Plain rounding erases a 0.25 LSB signal; TPDF dither keeps it as the mean.
            assertTrue(codes(output, false, input).all { it == 0L })
            val dithered = codes(output, true, input)
            assertEquals(0.25, dithered.average(), 0.01)
            assertTrue(dithered.any { it != 0L })
        }
    }

    @Test
    fun ditherNoiseIsTriangularWithQuarterLsbSquaredPower() {
        // Error power of rounding (1/12) plus TPDF (1/6) = 1/4 LSB^2, mean 0, extremes within +-2 LSB.
        val n = 400_000
        val input = FloatArray(n) { ((it % 1000) / 1000.0 * 0.5 + 0.1).toFloat() }
        val q = codes(DspOutput.INT16, true, input)
        val errors = DoubleArray(n) { q[it] - input[it] * 32768.0 }
        assertEquals(0.0, errors.average(), 0.01)
        assertEquals(0.25, errors.map { it * it }.average(), 0.01)
        assertTrue(errors.all { kotlin.math.abs(it) <= 1.5 + 1e-9 })
        val ones = errors.count { floor(kotlin.math.abs(it)) >= 1.0 }
        assertTrue(ones > 0)
    }

    @Test
    fun thirtyTwoBitIntegerFloatAndDsdOutputsAreNeverDithered() {
        val input = FloatArray(1_000) { (it - 500) / 700f }
        assertArrayEquals(codes(DspOutput.INT32, false, input), codes(DspOutput.INT32, true, input))

        for (output in listOf(DspOutput.FLOAT32, DspOutput.DSD_MODULATOR)) {
            val buffer = ByteBuffer.allocate(input.size * 4)
            PcmQuantizer(output, true).write(input.copyOf(), input.size, buffer)
            val bits = buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().let { b ->
                IntArray(input.size) { b.get(it) }
            }
            assertArrayEquals(IntArray(input.size) { java.lang.Float.floatToRawIntBits(input[it]) }, bits)
        }
    }

    @Test
    fun chainAddsNoDitherBeforeDsdModulation() {
        fun output(target: DspOutput, dither: Boolean): ByteArray {
            val config = DspConfig(com.audipp.domain.device.OutputMode.PROCESSED, 48_000, 1, target,
                0.0, 0.0, dither = dither)
            val chain = (DspChain.open(config) as DspOpenResult.Ready).chain
            val out = ByteBuffer.allocate(4 * 2_000)
            chain.process(FloatArray(2_000) { 0.3f * kotlin.math.sin(it * 0.05f) }, 2_000, out)
            return out.array()
        }
        assertArrayEquals(output(DspOutput.DSD_MODULATOR, false), output(DspOutput.DSD_MODULATOR, true))
        assertArrayEquals(output(DspOutput.FLOAT32, false), output(DspOutput.DSD_MODULATOR, true))
    }
}
