package com.audipp.audio.dsp

import com.audipp.domain.device.OutputMode
import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Where the chain ends. Native and DoP DSD have no value here: they never enter this chain. */
internal enum class DspOutput(val integerBits: Int) {
    INT16(16), INT24(24), INT32(32), FLOAT32(0), DSD_MODULATOR(0),
}

/** In-place processing of interleaved Float32 frames. A null stage is skipped. */
internal fun interface DspStage {
    fun process(samples: FloatArray, frames: Int)
}

/** Stage slots in their fixed order; the stages themselves belong to #89-#93, #67 and #90. */
internal data class DspStages(
    val map: DspStage? = null,
    val peq: DspStage? = null,
    val ir: DspStage? = null,
    val crossfeed: DspStage? = null,
    val apodizing: DspStage? = null,
    val src: DspStage? = null,
)

/**
 * [maxPositiveGainDb] is the sum of every positive gain that can apply at once (ReplayGain, PEQ
 * boosts); [irAbsCoefficientSum] is the sum of |IR coefficients|, 1.0 without an IR. [gainDb] is
 * the ReplayGain/R128 plus preamp gain at start. [headroomAttenuationDb] is the attenuation the
 * user has been shown and accepted; the chain does not pick one by itself.
 */
internal data class DspConfig(
    val mode: OutputMode,
    val sampleRateHz: Int,
    val channelCount: Int,
    val output: DspOutput,
    val gainDb: Double,
    val maxPositiveGainDb: Double,
    val irAbsCoefficientSum: Double = 1.0,
    val headroomAttenuationDb: Double = 0.0,
    val stages: DspStages = DspStages(),
    val dither: Boolean = true,
)

internal sealed interface DspOpenResult {
    data class Ready(val chain: DspChain) : DspOpenResult
    enum class Rejected : DspOpenResult {
        NOT_PROCESSED_PATH, UNSUPPORTED_FORMAT, INVALID_PARAMETER, HEADROOM_INSUFFICIENT, OVERLOAD,
    }
}

internal enum class DspBlockResult { PROCESSED, INVALID_BLOCK, NON_FINITE_SAMPLE, OVERLOAD }

/** Attenuation that keeps a full-scale input below 0 dBFS after the worst-case gain. */
internal fun requiredHeadroomAttenuationDb(maxPositiveGainDb: Double, irAbsCoefficientSum: Double) =
    maxOf(0.0, maxPositiveGainDb + 20 * log10(irAbsCoefficientSum))

/**
 * map -> ReplayGain/R128 + preamp (one smoothed gain) -> PEQ -> IR -> Crossfeed -> Apodizing -> SRC
 * -> clip guard -> quantizer. [process] runs on the audio thread: it allocates and locks nothing.
 */
internal class DspChain private constructor(
    private val config: DspConfig,
    private val attenuationDb: Double,
) {
    private val stagesBeforeGain = listOfNotNull(config.stages.map)
    private val stagesAfterGain = listOfNotNull(
        config.stages.peq, config.stages.ir, config.stages.crossfeed, config.stages.apodizing,
        config.stages.src,
    )
    private val guard = ClipGuard(config.sampleRateHz, config.channelCount, CEILING_DB)
    private val quantizer = PcmQuantizer(config.output, config.dither)
    private val rampFrames = (config.sampleRateHz * RAMP_MS / 1_000.0).roundToInt()
    private val bytesPerFrame =
        config.channelCount * (if (config.output.integerBits == 0) 4 else config.output.integerBits / 8)

    @Volatile
    private var targetGain = linear(config.gainDb - attenuationDb)
    private var gain = targetGain
    private var rampTarget = targetGain
    private var rampStep = 0.0
    private var rampLeft = 0

    val latencyFrames get() = guard.latencyFrames

    /** Applies from the next block, reaching the new gain in 10 ms. */
    fun setGainDb(gainDb: Double): Boolean {
        if (!gainDb.isFinite() || gainDb > config.maxPositiveGainDb) return false
        targetGain = linear(gainDb - attenuationDb)
        return true
    }

    /**
     * Processes [frames] frames of [samples] in place and writes the quantized frames at
     * [output]'s position. Nothing is written unless the result is PROCESSED.
     */
    fun process(samples: FloatArray, frames: Int, output: ByteBuffer): DspBlockResult {
        val count = frames * config.channelCount
        if (frames < 1 || count > samples.size || output.remaining() < frames * bytesPerFrame) {
            return DspBlockResult.INVALID_BLOCK
        }
        if (!allFinite(samples, count)) return DspBlockResult.NON_FINITE_SAMPLE

        for (stage in stagesBeforeGain) stage.process(samples, frames)
        applyGain(samples, frames)
        for (stage in stagesAfterGain) stage.process(samples, frames)
        if (!allFinite(samples, count)) return DspBlockResult.OVERLOAD
        guard.process(samples, frames)
        quantizer.write(samples, count, output)
        return DspBlockResult.PROCESSED
    }

    private fun applyGain(samples: FloatArray, frames: Int) {
        val target = targetGain
        if (target != rampTarget) {
            rampTarget = target
            rampLeft = rampFrames
            rampStep = (target - gain) / rampFrames
        }
        val channels = config.channelCount
        for (frame in 0 until frames) {
            if (rampLeft > 0) {
                gain = if (--rampLeft == 0) rampTarget else gain + rampStep
            }
            val g = gain.toFloat()
            for (c in 0 until channels) samples[frame * channels + c] *= g
        }
    }

    private fun allFinite(samples: FloatArray, count: Int): Boolean {
        for (i in 0 until count) if (!samples[i].isFinite()) return false
        return true
    }

    companion object {
        const val CEILING_DB = -1.0
        const val RAMP_MS = 10
        const val MAX_ATTENUATION_DB = 60.0

        fun open(config: DspConfig): DspOpenResult {
            if (config.mode != OutputMode.PROCESSED) return DspOpenResult.Rejected.NOT_PROCESSED_PATH
            if (config.sampleRateHz !in 1..384_000 || config.channelCount !in setOf(1, 2, 6)) {
                return DspOpenResult.Rejected.UNSUPPORTED_FORMAT
            }
            val numbers = doubleArrayOf(config.gainDb, config.maxPositiveGainDb,
                config.irAbsCoefficientSum, config.headroomAttenuationDb)
            if (!numbers.all(Double::isFinite) || config.irAbsCoefficientSum <= 0.0 ||
                config.headroomAttenuationDb < 0.0 || config.gainDb > config.maxPositiveGainDb
            ) return DspOpenResult.Rejected.INVALID_PARAMETER
            val required = requiredHeadroomAttenuationDb(config.maxPositiveGainDb,
                config.irAbsCoefficientSum)
            if (required > MAX_ATTENUATION_DB) return DspOpenResult.Rejected.OVERLOAD
            if (config.headroomAttenuationDb < required) {
                return DspOpenResult.Rejected.HEADROOM_INSUFFICIENT
            }
            return DspOpenResult.Ready(DspChain(config, config.headroomAttenuationDb))
        }

        private fun linear(db: Double) = 10.0.pow(db / 20.0)
    }
}
