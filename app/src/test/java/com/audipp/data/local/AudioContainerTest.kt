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
    }

    @Test
    fun nonPcmWaveRequiresStructuralValidationButHasNoPcmLayout() {
        val alaw = wave(encoding = 6, storage = 8, valid = 8)
        val result = read(alaw, "track.wav")
        assertEquals(AudioContainer.WAVE, result.container)
        assertNull(result.format.codec)
        assertNull(result.format.bitDepth)
        assertNull(result.pcm)
        assertNull(result.durationMs)
        assertRejected(alaw.copyOf(alaw.size - 1), AudioProbeProblem.MALFORMED)
    }

    @Test
    fun emptyTruncatedAndSpoofedFilesHaveSeparateReasons() {
        assertRejected(byteArrayOf(), AudioProbeProblem.EMPTY, "empty.wav")
        assertRejected(ascii("ordinary text"), AudioProbeProblem.EXTENSION_MISMATCH, "false.mp3")
        assertRejected(wave(1, 24, 20), AudioProbeProblem.EXTENSION_MISMATCH, "renamed.flac")
        assertRejected(ascii("ordinary text"), AudioProbeProblem.UNRECOGNIZED)
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
        val mp4 = box("ftyp", ascii("M4A ") + be32(0)) + box("moov", byteArrayOf())
        assertEquals(AudioContainer.MP3, read(mp3).container)
        assertEquals(AudioContainer.ADTS, read(adts).container)
        assertEquals(AudioContainer.MP4, read(mp4).container)
        assertNull(read(mp3).format.bitDepth)
        assertNull(read(adts).format.bitDepth)
        assertRejected(mp3.copyOf(416), AudioProbeProblem.MALFORMED)
        assertRejected(adts.copyOf(7), AudioProbeProblem.MALFORMED)
        assertRejected(mp4.copyOf(15), AudioProbeProblem.MALFORMED)
        assertRejected(mp4.copyOf(16), AudioProbeProblem.MALFORMED)
    }

    @Test
    fun imageBrandsBypassMovieValidation() {
        for (brand in listOf("avif", "avis", "heic", "heix", "mif1")) {
            val image = box("ftyp", ascii(brand) + be32(0)) + box("meta", be32(0))
            assertRejected(image, AudioProbeProblem.NON_MEDIA)
        }
        val audio = box("ftyp", ascii("M4A ") + be32(0))
        assertRejected(audio, AudioProbeProblem.MALFORMED, "track.m4a")
    }

    @Test
    fun flacReadsStreamInfoAndSkipsImagePayload() {
        val png = ascii("image/png")
        val pictureHeader = be32(3) + be32(png.size) + png + be32(0) +
            be32(3000) + be32(3000) + be32(24) + be32(0) + be32(2_000_000)
        val imageOffset = 42 + 4 + pictureHeader.size
        val fileSize = imageOffset + 2_000_000 + 9
        val metadata = ascii("fLaC") + byteArrayOf(0, 0, 0, 34) + streamInfo(192, 192) +
            be32(0x86000000.toInt() or (pictureHeader.size + 2_000_000)) + pictureHeader
        val probed = probeAudioContainer(fileSize.toLong(), null) { offset, count ->
            assertTrue(count <= 64)
            when {
                offset < metadata.size -> metadata.copyOfRange(offset.toInt(), offset.toInt() + count)
                offset == (fileSize - 9).toLong() -> byteArrayOf(0xff.toByte(), 0xf8.toByte(), 0x19, 0x18)
                else -> error("Unexpected access inside artwork")
            }
        } as AudioProbeResult.Identified
        assertEquals(96_000, probed.format.sampleRateHz)
        assertEquals(24, probed.format.bitDepth)
        assertEquals(2_000L, probed.durationMs)
        assertEquals(EmbeddedArtwork(imageOffset.toLong(), 2_000_000, "image/png"), probed.artwork)
    }

    @Test
    fun flacRejectsInvalidStreamInfoBlockSizes() {
        for ((min, max) in listOf(0 to 0, 15 to 192, 192 to 16)) {
            val bytes = ascii("fLaC") + byteArrayOf(0x80.toByte(), 0, 0, 34) + streamInfo(min, max) +
                byteArrayOf(0xff.toByte(), 0xf8.toByte(), 0x19, 0x18) + ByteArray(5)
            assertRejected(bytes, AudioProbeProblem.MALFORMED, "track.flac")
        }
    }

    @Test
    fun mp4LocatesCoverWithoutReadingItsPayload() {
        val data = box("data", be32(14) + be32(0) + ByteArray(32_768))
        val cover = box("ilst", box("covr", data))
        val movie = box("moov", box("udta", box("meta", be32(0) + cover)))
        val bytes = box("ftyp", ascii("M4A ") + be32(0)) + movie
        val imageStart = bytes.size - 32_768
        val result = probeAudioContainer(bytes.size.toLong(), "track.m4a") { offset, count ->
            assertTrue(offset + count <= imageStart)
            bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        } as AudioProbeResult.Identified
        assertEquals(EmbeddedArtwork(imageStart.toLong(), 32_768, "image/png"), result.artwork)
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
        val form = ascii("DSD ") + iff("FVER", be32(0x01050000))
        val dff = iff("FRM8", form + iff("PROP", properties) + iff("DSD ", ByteArray(7056)))
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
    fun dffDistinguishesDstFromRawDsd() {
        val frames = iff("DSTF", byteArrayOf(1)) + iff("DSTC", be32(0)) + iff("DSTF", byteArrayOf(2))
        val result = read(dst(2, frames), "track.dff")
        assertEquals("audio/x-dst", result.format.codec)
        assertEquals(26L, result.durationMs)
        assertNull(result.format.bitDepth)
    }

    @Test
    fun dstRejectsFrameCountMismatchAndOutOfBoundsFrames() {
        val frame = iff("DSTF", byteArrayOf(1))
        assertRejected(dst(2, frame), AudioProbeProblem.MALFORMED)
        assertRejected(dst(1, frame + frame), AudioProbeProblem.MALFORMED)
        assertRejected(dst(1, ascii("DSTF") + be64(4) + byteArrayOf(1, 0)), AudioProbeProblem.MALFORMED)
    }

    @Test
    fun dstLeavesDurationUnknownWhenFramesExceedTheInspectionBudget() {
        val frames = List(520) { iff("DSTF", byteArrayOf(1)) }.reduce(ByteArray::plus)
        val result = read(dst(520, frames, iff("ID3 ", ByteArray(10))))
        assertEquals("audio/x-dst", result.format.codec)
        assertNull(result.durationMs)
        assertEquals(10L, result.id3?.let { it.limit - it.offset })
    }

    private fun dst(declared: Int, frames: ByteArray, suffix: ByteArray = byteArrayOf()): ByteArray {
        val properties = ascii("SND ") +
            iff("FS  ", be32(2_822_400)) +
            iff("CHNL", byteArrayOf(0, 2) + ascii("SLFTSRGT")) +
            iff("CMPR", ascii("DST ") + byteArrayOf(0))
        val data = iff("FRTE", be32(declared) + byteArrayOf(0, 75)) + frames
        return iff("FRM8", ascii("DSD ") + iff("FVER", be32(0x01050000)) +
            iff("PROP", properties) + iff("DST ", data) + suffix)
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

    private fun box(id: String, bytes: ByteArray) = be32(bytes.size + 8) + ascii(id) + bytes

    private fun streamInfo(min: Int, max: Int): ByteArray {
        val packed = (96_000L shl 44) or (1L shl 41) or (23L shl 36) or 192_000L
        return be32((min shl 16) or max) + ByteArray(6) + be64(packed) + ByteArray(16)
    }

    private fun iff(id: String, bytes: ByteArray) =
        ascii(id) + be64(bytes.size.toLong()) + bytes + ByteArray(bytes.size % 2)

    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
    private fun be32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun be64(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
    private fun le32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun le64(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
}
