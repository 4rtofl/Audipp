package com.audipp.data.local

import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAudioReaderTest {
    @Test
    fun readsRealFlacMp3AdtsAndM4aWithoutAMimeHint() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val codecs = mapOf(
            AudioContainer.FLAC to ("flac" to "audio/flac"),
            AudioContainer.MP3 to ("mp3" to "audio/mpeg"),
            AudioContainer.ADTS to ("aac" to "audio/mp4a-latm"),
            AudioContainer.MP4 to ("m4a" to "audio/mp4a-latm"),
        )
        for ((container, extensionAndCodec) in codecs) {
            val (extension, codec) = extensionAndCodec
            val bytes = assets.open("audio-format/tone.$extension").use { it.readBytes() }
            withSlice(bytes, prefix = 21) { descriptor ->
                val result = readSafAudio(descriptor, "opaque.bin", CancellationSignal())!!
                assertEquals(extension, container, result.container)
                assertEquals(extension, codec, result.codec)
                assertEquals(extension, 44_100, result.sampleRate)
                assertEquals(extension, 1, result.channels)
                assertEquals(extension, if (extension == "flac") 16 else null, result.bitDepth)
                assertTrue(extension, result.standardPath)
                if (extension == "m4a") {
                    val cover = result.artwork!!
                    assertEquals("image/png", cover.mimeType)
                    val start = cover.offset.toInt()
                    assertArrayEquals(
                        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47),
                        bytes.copyOfRange(start, start + 4),
                    )
                }
                if (extension == "aac") {
                    assertNull(result.title)
                    assertNull(result.track)
                } else {
                    assertTrue(extension, (result.durationMs ?: 0) > 0)
                    assertEquals(extension, "Fixture", result.title)
                    assertEquals(extension, 2, result.track)
                }
            }
        }
    }

    @Test
    fun readsLinearPcmWaveFromASlice() {
        val samples = ByteArray(1600)
        val wave = ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + samples.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples.size).put(samples).array()
        withSlice(wave, prefix = 21) { descriptor ->
            val audio = readSafAudio(descriptor, null, CancellationSignal())!!
            assertEquals(AudioContainer.WAVE, audio.container)
            assertEquals(16, audio.bitDepth)
            assertEquals(PcmLayout(16, false), audio.pcm)
            assertEquals(100L, audio.durationMs)
            assertTrue(audio.standardPath)
        }
    }

    @Test
    fun readsDsfSliceAndRejectsSpoofingEmptyAndTruncation() {
        val dsf = ByteBuffer.allocate(92 + 8192).order(ByteOrder.LITTLE_ENDIAN)
            .put("DSD ".toByteArray()).putLong(28).putLong(92 + 8192).putLong(0)
            .put("fmt ".toByteArray()).putLong(52).putInt(1).putInt(0).putInt(2).putInt(2)
            .putInt(2_822_400).putInt(1).putLong(28_224).putInt(4096).putInt(0)
            .put("data".toByteArray()).putLong(12 + 8192).array()
        withSlice(dsf, prefix = 17) { descriptor ->
            val audio = readSafAudio(descriptor, "opaque", CancellationSignal())!!
            assertEquals(AudioContainer.DSF, audio.container)
            assertEquals(2_822_400, audio.sampleRate)
            assertEquals(10L, audio.durationMs)
            assertFalse(audio.standardPath)
            assertNull(audio.bitDepth)
            assertNull(audio.title)
        }
        val cases = listOf(
            dsf to ("fake.wav" to LibraryScanIssue.FORMAT_MISMATCH),
            byteArrayOf() to ("empty.dsf" to LibraryScanIssue.EMPTY_AUDIO),
            dsf.copyOf(dsf.size - 1) to ("truncated.dsf" to LibraryScanIssue.CORRUPT_AUDIO),
        )
        for ((bytes, nameAndIssue) in cases) {
            val (name, reason) = nameAndIssue
            withSlice(bytes) { descriptor ->
                val rejected = assertThrows(SafAudioRejected::class.java) {
                    readSafAudio(descriptor, name, CancellationSignal())
                }
                assertEquals(reason, rejected.issue)
            }
        }
        withSlice(dsf) { descriptor ->
            assertThrows(OperationCanceledException::class.java) {
                readSafAudio(descriptor, null, CancellationSignal().apply { cancel() })
            }
        }
    }

    @Test
    fun platformDecodersProducePcmFromTheCompressedFixtures() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (extension in listOf("flac", "mp3", "aac", "m4a")) {
            val bytes = assets.open("audio-format/tone.$extension").use { it.readBytes() }
            withSlice(bytes) { descriptor ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                    val track = (0 until extractor.trackCount).first {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                    extractor.selectTrack(track)
                    val format = extractor.getTrackFormat(track)
                    val decoder = requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)) {
                        "No decoder for $extension ($format)"
                    }
                    val codec = MediaCodec.createByCodecName(decoder)
                    try {
                        codec.configure(format, null, null, 0)
                        codec.start()
                        assertTrue("$extension: $decoder emitted no PCM", producesPcm(codec, extractor))
                    } finally {
                        codec.release()
                    }
                } finally {
                    extractor.release()
                }
            }
        }
    }

    private fun producesPcm(codec: MediaCodec, extractor: MediaExtractor): Boolean {
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var inputEnded = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(5_000)
                if (index >= 0) {
                    val buffer = checkNotNull(codec.getInputBuffer(index))
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val output = codec.dequeueOutputBuffer(info, 5_000)
            if (output >= 0) {
                codec.releaseOutputBuffer(output, false)
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) return true
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
            }
        }
        return false
    }

    private fun withSlice(bytes: ByteArray, prefix: Int = 0, test: (AssetFileDescriptor) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("format-fixture", ".bin", cache)
        try {
            file.writeBytes(ByteArray(prefix) + bytes + ByteArray(11))
            val open = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            AssetFileDescriptor(open, prefix.toLong(), bytes.size.toLong()).use(test)
            assertFalse(open.fileDescriptor.valid())
        } finally {
            file.delete()
        }
    }
}
