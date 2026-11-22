package com.audipp.audio.dsp

import java.nio.ByteBuffer

/**
 * Float to little-endian PCM bytes. TPDF dither (±1 LSB) is applied only to 16/24-bit output.
 * 32-bit integer output is not dithered: rounding a Float32 to the int32 grid errs by at most half
 * an LSB (about -193 dBFS), far below any DAC's noise. (Below |x| < 2^-7 the Float32 step is finer
 * than the int32 LSB, so the step does discard bits; they are just inaudible.) Float32 and
 * DSD-modulator output pass the float through.
 */
internal class PcmQuantizer(private val output: DspOutput, private val dither: Boolean) {
    private var random = 0x2545F491

    fun write(samples: FloatArray, count: Int, target: ByteBuffer) {
        var at = target.position()
        val bits = output.integerBits
        if (bits == 0) {
            for (i in 0 until count) {
                val raw = java.lang.Float.floatToRawIntBits(samples[i])
                for (b in 0 until 4) target.put(at++, (raw ushr (8 * b)).toByte())
            }
            return
        }
        val scale = (1L shl (bits - 1)).toDouble()
        val dithered = dither && bits < 32
        for (i in 0 until count) {
            var value = samples[i] * scale
            if (dithered) value += uniform() - uniform()
            val code = Math.rint(value).coerceIn(-scale, scale - 1).toLong()
            for (b in 0 until bits / 8) target.put(at++, (code shr (8 * b)).toByte())
        }
    }

    // xorshift32; a fixed seed keeps a block's output reproducible.
    private fun uniform(): Double {
        random = random xor (random shl 13)
        random = random xor (random ushr 17)
        random = random xor (random shl 5)
        return (random ushr 8) / 16_777_216.0
    }
}
