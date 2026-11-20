package com.audipp.data.local

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioContainerTest {
    @Test
    fun waveDistinguishesValidBitsStorageWidthAndFloat() {
        val packed = read(wave(encoding = 1, storage = 24, valid = 20), "sample.wAv")
        assertEquals(AudioContainer.WAVE, packed.container)
        assertEquals(20, packed.format.bitDepth)
        assertEquals(24, packed.pcm?.storageBits)
        assertEquals(false, packed.pcm?.floatingPoint)
        assertEquals(48_000, packed.format.sampleRateHz)
        assertEquals(2, packed.format.channelCount)
        assertEquals(1L, packed.durationMs)

        val floating = read(wave(encoding = 3, storage = 32, valid = 32))
        assertNull(floating.format.bitDepth)
        assertEquals(PcmLayout(32, true), floating.pcm)
        assertEquals("audio/x-wav-float", floating.format.codec)

        assertNull(read(wave(encoding = 1, storage = 24, valid = 0)).format.bitDepth)
        assertRejected(wave(encoding = 1, storage = 24, valid = 25), AudioProbeProblem.MALFORMED)
        assertRejected(wave(encoding = 6, storage = 8, valid = 8), AudioProbeProblem.UNSUPPORTED)
    }

    @Test
    fun emptyTruncatedAndSpoofedFilesHaveSeparateReasons() {
        assertRejected(byteArrayOf(), AudioProbeProblem.EMPTY, "empty.wav")
        assertRejected(ascii("ordinary text"), AudioProbeProblem.EXTENSION_MISMATCH, "false.mp3")
        assertRejected(wave(1, 24, 20), AudioProbeProblem.EXTENSION_MISMATCH, "renamed.flac")
        assertRejected(ascii("ordinary text"), AudioProbeProblem.UNSUPPORTED)
        assertRejected(wave(1, 24, 20).dropLast(1).toByteArray(), AudioProbeProblem.MALFORMED)
        assertRejected(ascii("fLaC"), AudioProbeProblem.MALFORMED)
        assertRejected(ascii("FRM8"), AudioProbeProblem.MALFORMED)
        assertRejected(ascii("DSD "), AudioProbeProblem.MALFORMED)
        assertEquals(AudioContainer.WAVE, read(wave(1, 24, 20), "no-hint.bin").container)
    }

    @Test
    fun recognizesMpegLayerThreeAdtsAndMp4WithoutFileNames() {
        val mp3 = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64) + ByteArray(413)
        val adts = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x50, 0x80.toByte(), 0x01, 0x1f, 0xfc.toByte(), 0)
        val mp4 = be32(16) + ascii("ftypM4A ") + be32(0)
        assertEquals(AudioContainer.MP3, read(mp3).container)
        assertEquals(AudioContainer.ADTS, read(adts).container)
        assertEquals(AudioContainer.MP4, read(mp4).container)
        assertNull(read(mp3).format.bitDepth)
        assertNull(read(adts).format.bitDepth)
        assertRejected(mp3.copyOf(416), AudioProbeProblem.MALFORMED)
        assertRejected(adts.copyOf(7), AudioProbeProblem.MALFORMED)
        assertRejected(mp4.copyOf(15), AudioProbeProblem.MALFORMED)
    }

    @Test
    fun flacReadsStreamInfoAndSkipsImagePayload() {
        val png = ascii("image/png")
        val pictureHeader = be32(3) + be32(png.size) + png + be32(0) +
            be32(3000) + be32(3000) + be32(24) + be32(0) + be32(2_000_000)
        val imageOffset = 42 + 4 + pictureHeader.size
        val fileSize = imageOffset + 2_000_000 + 2
        val packed = (96_000L shl 44) or (1L shl 41) or (23L shl 36) or 192_000L
        val streamInfo = ByteArray(10) + be64(packed) + ByteArray(16)
        val metadata = ascii("fLaC") + byteArrayOf(0, 0, 0, 34) + streamInfo +
            be32(0x86000000.toInt() or (pictureHeader.size + 2_000_000)) + pictureHeader
        val probed = probeAudioContainer(fileSize.toLong(), null) { offset, count ->
            assertTrue(count <= 64)
            when {
                offset < metadata.size -> metadata.copyOfRange(offset.toInt(), offset.toInt() + count)
                offset == (fileSize - 2).toLong() -> byteArrayOf(0xff.toByte(), 0xf8.toByte())
                else -> error("Unexpected access inside artwork")
            }
        } as AudioProbeResult.Identified
        assertEquals(96_000, probed.format.sampleRateHz)
        assertEquals(24, probed.format.bitDepth)
        assertEquals(2_000L, probed.durationMs)
        assertEquals(EmbeddedArtwork(imageOffset.toLong(), 2_000_000, "image/png"), probed.artwork)
    }

    @Test
    fun readsDsdSampleRateAndLengthWithoutDescribingDsdAsPcm() {
        val dsf = ascii("DSD ") + le64(28) + le64(92 + 8192) + le64(0) +
            ascii("fmt ") + le64(52) + le32(1) + le32(0) + le32(2) + le32(2) +
            le32(2_822_400) + le32(1) + le64(28_224) + le32(4096) + le32(0) +
            ascii("data") + le64(12 + 8192) + ByteArray(8192)
        val properties = ascii("SND ") +
            iff("FS  ", be32(2_822_400)) +
            iff("CHNL", byteArrayOf(0, 2) + ascii("SLFTSRGT")) +
            iff("CMPR", ascii("DSD ") + byteArrayOf(0))
        val dff = iff("FRM8", ascii("DSD ") + iff("PROP", properties) + iff("DSD ", ByteArray(7056)))
        assertEquals(AudioContainer.DSF, read(dsf).container)
        assertEquals(AudioContainer.DFF, read(dff).container)
        for (bytes in listOf(dsf, dff)) {
            val result = read(bytes)
            assertEquals("audio/x-dsd", result.format.codec)
            assertEquals(2_822_400, result.format.sampleRateHz)
            assertEquals(2, result.format.channelCount)
            assertEquals(10L, result.durationMs)
            assertNull(result.format.bitDepth)
            assertNull(result.pcm)
            assertRejected(bytes.copyOf(bytes.size - 1), AudioProbeProblem.MALFORMED)
        }
    }

    @Test
    fun inspectionBudgetAndCancellationAreExplicit() {
        val chunks = List(514) { ascii("JUNK") + le32(0) }.fold(byteArrayOf(), ByteArray::plus)
        val wave = ascii("RIFF") + le32(4 + chunks.size) + ascii("WAVE") + chunks
        assertRejected(wave, AudioProbeProblem.INSPECTION_LIMIT)

        val cancelled = CancellationException()
        assertEquals(cancelled, assertThrows(CancellationException::class.java) {
            probeAudioContainer(12, null) { _, _ -> throw cancelled }
        })
    }

    private fun assertRejected(bytes: ByteArray, reason: AudioProbeProblem, name: String? = null) {
        assertEquals(AudioProbeResult.Rejected(reason), probe(bytes, name))
    }

    private fun read(bytes: ByteArray, name: String? = null): AudioProbeResult.Identified =
        probe(bytes, name) as AudioProbeResult.Identified

    private fun probe(bytes: ByteArray, name: String?): AudioProbeResult =
        probeAudioContainer(bytes.size.toLong(), name) { offset, count ->
            bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        }

    private fun wave(encoding: Int, storage: Int, valid: Int): ByteArray {
        val alignment = storage / 8 * 2
        val format = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        format.putShort(0xfffe.toShort()).putShort(2).putInt(48_000).putInt(48_000 * alignment)
            .putShort(alignment.toShort()).putShort(storage.toShort()).putShort(22)
            .putShort(valid.toShort()).putInt(3).putShort(encoding.toShort())
            .put(byteArrayOf(0, 0, 0, 0, 16, 0, 128.toByte(), 0, 0, 170.toByte(), 0, 56, 155.toByte(), 113))
        val dataSize = alignment * 48
        return ascii("RIFF") + le32(4 + 8 + format.capacity() + 8 + dataSize) +
            ascii("WAVEfmt ") + le32(format.capacity()) + format.array() +
            ascii("data") + le32(dataSize) + ByteArray(dataSize)
    }

    private fun iff(id: String, bytes: ByteArray) =
        ascii(id) + be64(bytes.size.toLong()) + bytes + ByteArray(bytes.size % 2)

    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
    private fun be32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun be64(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
    private fun le32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun le64(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
}
