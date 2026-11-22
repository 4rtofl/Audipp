package com.audipp.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lookahead limiter that holds the estimated true peak of all channels at [ceilingDb] with one
 * linked gain. Output is [latencyFrames] behind the input. Not thread-safe; no allocation in [process].
 *
 * True peak: 8x windowed-sinc interpolation over 16 input samples. The estimate is a lower bound,
 * at most about 0.17 dB low for a full-scale tone at Nyquist.
 * Gain: sliding minimum of the required gain over the lookahead, smoothed by a moving average of the
 * same length, so the gain has fallen to the requirement when the peak reaches the output.
 */
internal class ClipGuard(sampleRateHz: Int, private val channels: Int, ceilingDb: Double) {
    private val ceiling = Math.pow(10.0, ceilingDb / 20.0)
    private val lookahead = (sampleRateHz * LOOKAHEAD_MS + 999) / 1_000
    val latencyFrames = lookahead + HALF_TAPS

    private val history = FloatArray((lookahead + 2 * HALF_TAPS + 1) * channels)
    private val historyFrames = history.size / channels
    private var position = 0L

    // phases[(k - 1) * TAPS + j] weighs x[a - HALF_TAPS + 1 + j] for the point a + k / OVERSAMPLE.
    private val phases = DoubleArray((OVERSAMPLE - 1) * 2 * HALF_TAPS).also { table ->
        for (k in 1 until OVERSAMPLE) for (j in 0 until 2 * HALF_TAPS) {
            val distance = (j - HALF_TAPS + 1) - k.toDouble() / OVERSAMPLE
            val sinc = sin(PI * distance) / (PI * distance)
            table[(k - 1) * 2 * HALF_TAPS + j] = sinc * 0.5 * (1 + cos(PI * distance / HALF_TAPS))
        }
    }

    private val minWindow = lookahead + 2
    private val minPositions = LongArray(minWindow + 1)
    private val minValues = DoubleArray(minWindow + 1)
    private var minHead = 0
    private var minSize = 0

    private val gains = DoubleArray(lookahead + 1) { 1.0 }
    private var gainIndex = 0
    private var gainSum = (lookahead + 1).toDouble()

    fun process(samples: FloatArray, frames: Int) {
        for (frame in 0 until frames) {
            val slot = (position % historyFrames).toInt() * channels
            for (c in 0 until channels) {
                history[slot + c] = samples[frame * channels + c]
            }

            val gain = gainFor(requiredGain(position - HALF_TAPS))
            val out = position - latencyFrames
            val outSlot = (Math.floorMod(out, historyFrames.toLong())).toInt() * channels
            for (c in 0 until channels) {
                samples[frame * channels + c] =
                    if (out < 0) 0f else (history[outSlot + c] * gain).toFloat()
            }
            position++
        }
    }

    // Peak between input frames a and a + 1, which needs x[a + HALF_TAPS] to have arrived.
    private fun requiredGain(a: Long): Double {
        var peak = 0.0
        for (c in 0 until channels) {
            peak = maxOf(peak, abs(sampleAt(a, c).toDouble()))
            for (k in 1 until OVERSAMPLE) {
                var value = 0.0
                for (j in 0 until 2 * HALF_TAPS) {
                    value += phases[(k - 1) * 2 * HALF_TAPS + j] *
                        sampleAt(a - HALF_TAPS + 1 + j, c)
                }
                peak = maxOf(peak, abs(value))
            }
        }
        return if (peak > ceiling) ceiling / peak else 1.0
    }

    private fun sampleAt(frame: Long, channel: Int): Float =
        if (frame < 0) 0f else history[(frame % historyFrames).toInt() * channels + channel]

    private fun gainFor(required: Double): Double {
        while (minSize > 0 && minValues[(minHead + minSize - 1) % minValues.size] >= required) minSize--
        val tail = (minHead + minSize) % minValues.size
        minPositions[tail] = position
        minValues[tail] = required
        minSize++
        while (minPositions[minHead] <= position - minWindow) {
            minHead = (minHead + 1) % minValues.size
            minSize--
        }
        gainSum += minValues[minHead] - gains[gainIndex]
        gains[gainIndex] = minValues[minHead]
        gainIndex = (gainIndex + 1) % gains.size
        return gainSum / gains.size
    }

    private companion object {
        const val LOOKAHEAD_MS = 5
        const val OVERSAMPLE = 8
        const val HALF_TAPS = 8
    }
}
