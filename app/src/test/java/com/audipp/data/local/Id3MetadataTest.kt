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

    @Test
    fun readsTheMusicBrainzReleaseFromItsTxxxFrameInAnyEncoding() {
        val mbid = "0e7a9c1b-2f4d-4c3b-9a8e-1d2c3b4a5f60"
        val utf16 = Charsets.UTF_16
        val bodies = listOf(
            byteArrayOf(0) + "MusicBrainz Album Id".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + mbid.uppercase().toByteArray(),
            byteArrayOf(1) + "MusicBrainz Album Id".toByteArray(utf16) + byteArrayOf(0, 0) + mbid.toByteArray(utf16),
        )
        for (body in bodies) {
            val tag = frame("TXXX", byteArrayOf(0) + "MusicBrainz Artist Id".toByteArray() + byteArrayOf(0) + "x".toByteArray()) +
                frame("TXXX", body)
            val bytes = header(tag.size) + tag
            val (tags, _) = readId3Metadata(Id3Location(0, bytes.size.toLong())) { offset, count ->
                bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
            }
            assertEquals(mbid, tags.releaseId)
        }
        val other = "11111111-2f4d-4c3b-9a8e-1d2c3b4a5f60"
        for (value in listOf("1234", "$mbid\u0000$other")) {
            val invalid = frame("TXXX", byteArrayOf(3) + "MusicBrainz Album Id".toByteArray() + byteArrayOf(0) + value.toByteArray())
            val bytes = header(invalid.size) + invalid
            assertNull(readId3Metadata(Id3Location(0, bytes.size.toLong())) { offset, count ->
                bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
            }.first.releaseId)
        }
    }

    private fun frame(key: String, body: ByteArray) = frameHeader(key, body.size) + body

    private fun frameHeader(key: String, size: Int) =
        key.toByteArray() + ByteBuffer.allocate(4).putInt(size).array() + byteArrayOf(0, 0)

    private fun header(size: Int) = "ID3".toByteArray() + byteArrayOf(3, 0, 0) +
        ByteArray(4) { index -> ((size ushr (7 * (3 - index))) and 127).toByte() }
}
