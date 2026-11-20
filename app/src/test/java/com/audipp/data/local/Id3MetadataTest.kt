package com.audipp.data.local

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Id3MetadataTest {
    @Test
    fun readsOnlyCurrentTagsAndReturnsImageRangeWithoutReadingImage() {
        val imageHeader = byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3, 0)
        val imageSize = 1_000_000
        val tagBody = frame("TIT2", byteArrayOf(3) + "曲名".toByteArray()) +
            frame("TRCK", byteArrayOf(0) + "2/12".toByteArray()) +
            frame("TPOS", byteArrayOf(0) + "0/2".toByteArray()) +
            frameHeader("APIC", imageHeader.size + imageSize) + imageHeader
        val prefix = header(tagBody.size + imageSize) + tagBody
        val (tags, artwork) = readId3Metadata(Id3Location(0, (prefix.size + imageSize).toLong())) { offset, count ->
            assertTrue(count <= 4096)
            assertTrue(offset < prefix.size)
            if (offset + count > prefix.size) {
                prefix.copyOfRange(offset.toInt(), prefix.size).copyOf(count)
            } else {
                prefix.copyOfRange(offset.toInt(), offset.toInt() + count)
            }
        }
        assertEquals(AudioTags(title = "曲名", track = 2), tags)
        assertEquals(EmbeddedArtwork(prefix.size.toLong(), imageSize.toLong(), "image/jpeg"), artwork)
    }

    @Test
    fun malformedSizeAndUnsupportedTagFlagsRemainUnknown() {
        val frame = frame("TIT2", byteArrayOf(3) + "ignored".toByteArray())
        val oversized = header(frame.size + 1) + frame
        val unsynchronised = (header(frame.size) + frame).apply { this[5] = 0x80.toByte() }
        for (bytes in listOf(oversized, unsynchronised, header(0))) {
            val (tags, image) = readId3Metadata(Id3Location(0, bytes.size.toLong())) { offset, count ->
                bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
            }
            assertEquals(AudioTags(), tags)
            assertNull(image)
        }
    }

    private fun frame(key: String, body: ByteArray) = frameHeader(key, body.size) + body

    private fun frameHeader(key: String, size: Int) =
        key.toByteArray() + ByteBuffer.allocate(4).putInt(size).array() + byteArrayOf(0, 0)

    private fun header(size: Int) = "ID3".toByteArray() + byteArrayOf(3, 0, 0) +
        ByteArray(4) { index -> ((size ushr (7 * (3 - index))) and 127).toByte() }
}
