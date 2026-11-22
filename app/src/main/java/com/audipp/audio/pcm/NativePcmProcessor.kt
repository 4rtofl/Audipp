package com.audipp.audio.pcm

import androidx.annotation.Keep
import java.nio.ByteBuffer

internal enum class PcmTransform { PRESERVE_INTEGER, TO_FLOAT32 }

internal sealed interface PcmOpenResult {
    data class Ready(val processor: NativePcmProcessor) : PcmOpenResult
    enum class Rejected : PcmOpenResult {
        NO_LOCAL_PLAYBACK, STALE_PLAYBACK, UNSUPPORTED_FORMAT, NATIVE_UNAVAILABLE,
    }
}

internal enum class PcmBlockResult {
    PROCESSED, CANCELLED, STALE_GENERATION, INVALID_BLOCK, NON_FINITE_SAMPLE,
}

internal class NativePcmProcessor private constructor(
    val inputFormat: PcmFormat,
    val outputFormat: PcmFormat,
    val maxFrames: Int,
    private val inputSampleCode: Int,
) : AutoCloseable {
    class Generation internal constructor()

    val generation = Generation()

    @Volatile
    private var closed = false
    val isActive: Boolean get() = !closed

    /**
     * Borrows both buffers for one synchronous call, at their current byte positions. The caller
     * owns their storage and metadata and must keep them stable and exclusive until return.
     * Only PROCESSED publishes all frames, with the same presentation time and channel order.
     */
    fun process(
        generation: Generation,
        frameCount: Int,
        presentationTimeUs: Long,
        input: ByteBuffer,
        output: ByteBuffer,
    ): PcmBlockResult {
        if (closed) return PcmBlockResult.CANCELLED
        if (generation !== this.generation) return PcmBlockResult.STALE_GENERATION
        if (frameCount !in 1..maxFrames || presentationTimeUs < 0 ||
            !input.isDirect || !output.isDirect || output.isReadOnly ||
            input.remaining() < frameCount * inputFormat.bytesPerFrame ||
            output.remaining() < frameCount * outputFormat.bytesPerFrame
        ) return PcmBlockResult.INVALID_BLOCK

        val result = PcmJni.process(input, input.position(), output, output.position(),
            frameCount * inputFormat.channelCount, inputSampleCode)
        if (closed) return PcmBlockResult.CANCELLED
        return when (result) {
            0 -> PcmBlockResult.PROCESSED
            2 -> PcmBlockResult.NON_FINITE_SAMPLE
            else -> PcmBlockResult.INVALID_BLOCK
        }
    }

    // JNI keeps no buffer or native handle alive after process returns, so close need not wait.
    override fun close() {
        closed = true
    }

    companion object {
        fun open(
            format: PcmFormat,
            transform: PcmTransform,
            bufferMs: Int = 80,
        ): PcmOpenResult {
            if (!format.isSupported || (bufferMs != 40 && bufferMs != 80 && bufferMs != 160) ||
                (transform == PcmTransform.PRESERVE_INTEGER && format.sample == PcmSample.FLOAT_32)
            ) return PcmOpenResult.Rejected.UNSUPPORTED_FORMAT
            if (!PcmJni.available) return PcmOpenResult.Rejected.NATIVE_UNAVAILABLE

            val preserve = transform == PcmTransform.PRESERVE_INTEGER
            val output = if (preserve) format else format.copy(sample = PcmSample.FLOAT_32)
            val inputSampleCode = when {
                preserve -> -format.sample.bytes
                format.sample == PcmSample.FLOAT_32 -> 0
                else -> format.sample.bytes
            }
            val maxFrames = (format.sampleRateHz * bufferMs + 999) / 1_000
            return PcmOpenResult.Ready(NativePcmProcessor(format, output, maxFrames, inputSampleCode))
        }
    }
}

@Keep
private object PcmJni {
    val available = try {
        System.loadLibrary("audipp_pcm")
        // Resolve the JNI entry point during setup, before the first audio callback.
        process(null, 0, null, 0, 0, 0) == 1
    } catch (_: LinkageError) {
        false
    } catch (_: SecurityException) {
        false
    }

    external fun process(
        input: ByteBuffer?, inputOffset: Int, output: ByteBuffer?, outputOffset: Int,
        sampleCount: Int, sampleCode: Int,
    ): Int
}
