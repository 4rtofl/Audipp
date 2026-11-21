package com.audipp.audio.pcm

import android.media.AudioFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.audipp.domain.model.Availability
import com.audipp.domain.model.ExecutionPlan
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.VariantId
import com.audipp.playback.LocalPlayback
import com.audipp.playback.LocalPlaybackEngine
import com.audipp.playback.source.SourceDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePcmProcessorTest {
    @Test
    fun integerBypassPreservesAllBitsAtBufferOffsetsIncludingOverlappingViews() {
        open(PcmSample.SIGNED_32, PcmTransform.PRESERVE_INTEGER).use { pcm ->
            assertEquals(pcm.inputFormat, pcm.outputFormat)
            val samples = intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE, -1, 0, 0x40000001, 0x40000002)
            val input = direct(1 + samples.size * 4).apply {
                position(1)
                samples.forEach { putInt(it) }
                position(1)
            }.asReadOnlyBuffer()
            val output = direct(2 + samples.size * 4).apply {
                put(0, 0x55)
                position(2)
            }

            assertEquals(PcmBlockResult.PROCESSED, pcm.process(pcm.generation, 3, 42,
                input, output))
            assertEquals(1, input.position())
            assertEquals(2, output.position())
            assertEquals(0x55.toByte(), output.get(0))
            assertArrayEquals(samples, IntArray(samples.size) { output.getInt(2 + it * 4) })

            output.position(1)
            val overlapping = output.slice().order(ByteOrder.LITTLE_ENDIAN)
            output.position(2)
            assertEquals(PcmBlockResult.PROCESSED,
                pcm.process(pcm.generation, 3, 42, output, overlapping))
            assertArrayEquals(samples, IntArray(samples.size) { output.getInt(1 + it * 4) })
        }
    }

    @Test
    fun signedLittleEndianConversionMatchesExactFloat32Boundaries() {
        // Fixed binary32 results independently rounded from signed normalized values.
        val expected = mapOf(
            PcmSample.SIGNED_16 to intArrayOf(0xbf800000.toInt(), 0x3f7ffe00,
                0xb8000000.toInt(), 0, 0x3f000200, 0x3f000400),
            PcmSample.SIGNED_24 to intArrayOf(0xbf800000.toInt(), 0x3f7ffffe,
                0xb4000000.toInt(), 0, 0x3f000002, 0x3f000004),
            PcmSample.SIGNED_32 to intArrayOf(0xbf800000.toInt(), 0x3f800000,
                0xb0000000.toInt(), 0, 0x3f000000, 0x3f000000),
        )
        for ((sample, bits) in expected) {
            open(sample, PcmTransform.TO_FLOAT32).use { pcm ->
                val halfRange = 1L shl (sample.bytes * 8 - 1)
                val samples = longArrayOf(-halfRange, halfRange - 1, -1, 0,
                    halfRange / 2 + 1, halfRange / 2 + 2)
                val input = direct(samples.size * sample.bytes)
                for (value in samples) {
                    repeat(sample.bytes) { input.put((value shr (it * 8)).toByte()) }
                }
                input.flip()
                val output = direct(bits.size * 4)
                assertEquals(PcmSample.FLOAT_32, pcm.outputFormat.sample)
                assertEquals(PcmBlockResult.PROCESSED,
                    pcm.process(pcm.generation, 3, 0, input, output))
                assertArrayEquals(bits, IntArray(bits.size) { output.getInt(it * 4) })
            }
        }
    }

    @Test
    fun floatValidationIsAllOrNothingWithInterleavedSurround() {
        val format = PcmFormat(PcmSample.FLOAT_32, 384_000, AudioFormat.CHANNEL_OUT_5POINT1)
        val ready = NativePcmProcessor.open(format, PcmTransform.TO_FLOAT32) as PcmOpenResult.Ready
        ready.processor.use { pcm ->
            val samples = floatArrayOf(-0f, 0.25f, -0.5f, 0f, 1.25f, Float.POSITIVE_INFINITY)
            val input = direct(format.bytesPerFrame).apply { samples.forEach { putFloat(it) }; flip() }
            val output = direct(format.bytesPerFrame)
            output.putInt(0, 0x55443322)
            assertEquals(PcmBlockResult.NON_FINITE_SAMPLE,
                pcm.process(pcm.generation, 1, 0, input, output))
            assertEquals(0x55443322, output.getInt(0))

            input.putFloat(20, -0.75f)
            samples[5] = -0.75f
            assertEquals(PcmBlockResult.PROCESSED,
                pcm.process(pcm.generation, 1, 0, input, output))
            assertArrayEquals(samples.map { it.toRawBits() }.toIntArray(),
                IntArray(samples.size) { output.getInt(it * 4) })
        }
    }

    @Test
    fun invalidBlocksAndAliasingAreRejectedAndCanBeRetried() {
        open(PcmSample.SIGNED_16, PcmTransform.TO_FLOAT32).use { pcm ->
            val input = direct(8)
            val output = direct(8)
            val cases = listOf(
                pcm.process(pcm.generation, 0, 0, input, output),
                pcm.process(pcm.generation, pcm.maxFrames + 1, 0, input, output),
                pcm.process(pcm.generation, 1, -1, input, output),
                pcm.process(pcm.generation, 1, 0, ByteBuffer.allocate(4), output),
                pcm.process(pcm.generation, 1, 0, input, output.asReadOnlyBuffer()),
                pcm.process(pcm.generation, 1, 0, input, direct(7)),
                pcm.process(pcm.generation, 1, 0, input, input.duplicate()),
            )
            cases.forEach { assertEquals(PcmBlockResult.INVALID_BLOCK, it) }
            assertEquals(PcmBlockResult.PROCESSED,
                pcm.process(pcm.generation, 1, 0, input, output))
        }
    }

    @Test
    fun closeRetiresTheGenerationAndLateBlocksCannotReachTheReplacement() {
        val pcm = open(PcmSample.SIGNED_32, PcmTransform.TO_FLOAT32)
        val submitted = CountDownLatch(1)
        val input = direct(pcm.maxFrames * pcm.inputFormat.bytesPerFrame)
        val output = direct(pcm.maxFrames * pcm.outputFormat.bytesPerFrame)
        val worker = FutureTask {
            submitted.countDown()
            var status: PcmBlockResult
            do {
                status = pcm.process(pcm.generation, pcm.maxFrames, 0, input, output)
            } while (status == PcmBlockResult.PROCESSED)
            status
        }
        Thread(worker).start()
        try {
            check(submitted.await(5, TimeUnit.SECONDS))
        } finally {
            pcm.close()
        }
        pcm.close()
        assertEquals(PcmBlockResult.CANCELLED, worker.get(5, TimeUnit.SECONDS))

        open(PcmSample.SIGNED_32, PcmTransform.TO_FLOAT32).use { replacement ->
            assertEquals(PcmBlockResult.STALE_GENERATION,
                replacement.process(pcm.generation, 1, 0, input, output))
            assertEquals(PcmBlockResult.PROCESSED,
                replacement.process(replacement.generation, 1, 0, input, output))
        }
    }

    @Test
    fun localResolutionOwnsTheProcessorAcrossDenialDiscontinuityAndRetry() = runBlocking {
        val entry = QueueEntryId("pcm-test")
        val track = TrackVariant(VariantId("pcm-test"), TrackId("pcm-test"),
            SourceRef(SourceId("local:pcm-test"), SourceKind.LOCAL, availability = Availability.PLAYABLE,
                localDocumentUri = "content://pcm-test/generated"))
        var accessAllowed = true
        val playback = LocalPlayback(object : LocalPlaybackEngine {
            override fun prepare(entryId: QueueEntryId, transitionId: TransitionId,
                document: SourceDescriptor.LocalDocument) = Unit
            override fun reset() = Unit
        }, {
            if (!accessAllowed) throw SecurityException("test document grant revoked")
            Availability.PLAYABLE
        })
        val format = PcmFormat(PcmSample.SIGNED_32, 48_000, AudioFormat.CHANNEL_OUT_STEREO)
        suspend fun open(plan: ExecutionPlan) = playback.openPcmProcessor(
            plan.transitionId, plan.routeRevision, format, PcmTransform.PRESERVE_INTEGER,
        )

        assertNull(playback.start(entry, track))
        val firstPlan = playback.snapshot.value.executionPlan!!
        val first = (open(firstPlan) as PcmOpenResult.Ready).processor
        assertFalse(firstPlan.capabilities.canAccessPcm)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { open(firstPlan) }
        playback.invalidatePcm()
        assertFalse(first.isActive)
        assertEquals(PcmOpenResult.Rejected.STALE_PLAYBACK, pending.await())
        val afterSeek = (open(firstPlan) as PcmOpenResult.Ready).processor
        val input = direct(8)
        val output = direct(8)
        assertEquals(PcmBlockResult.STALE_GENERATION,
            afterSeek.process(first.generation, 1, 0, input, output))

        accessAllowed = false
        assertNotNull(playback.start(entry, track))
        assertFalse(afterSeek.isActive)
        assertEquals(PcmOpenResult.Rejected.STALE_PLAYBACK, open(firstPlan))

        accessAllowed = true
        assertNull(playback.start(entry, track))
        val retried = (open(playback.snapshot.value.executionPlan!!) as PcmOpenResult.Ready).processor
        assertEquals(PcmBlockResult.PROCESSED,
            retried.process(retried.generation, 1, 0, input, output))
        playback.stop()
        assertEquals(PcmBlockResult.CANCELLED,
            retried.process(retried.generation, 1, 0, input, output))
    }

    private fun open(sample: PcmSample, transform: PcmTransform): NativePcmProcessor {
        val result = NativePcmProcessor.open(
            PcmFormat(sample, 48_000, AudioFormat.CHANNEL_OUT_STEREO), transform,
        )
        return (result as PcmOpenResult.Ready).processor
    }

    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes)
        .order(ByteOrder.LITTLE_ENDIAN)
}
