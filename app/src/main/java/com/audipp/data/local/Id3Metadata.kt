package com.audipp.data.local

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

internal data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
)

/** Reads the common ID3v2.3/v2.4 text frames and locates an APIC image without loading it. */
internal fun readId3Metadata(
    location: Id3Location,
    read: (offset: Long, count: Int) -> ByteArray,
): Pair<AudioTags, EmbeddedArtwork?> {
    val header = read(location.offset, 10)
    val version = header.getOrNull(3)?.toInt()
    if (header.size != 10 || header.ascii(0, 3) != "ID3" || version !in 3..4) return AudioTags() to null
    // Whole-tag unsynchronisation changes physical offsets, so an artwork byte range would be invalid.
    if (header.octet(5) and 0xc0 != 0) return AudioTags() to null
    val size = header.length(6, synchsafe = true) ?: return AudioTags() to null
    var position = location.offset + 10
    if (size > location.limit - position) return AudioTags() to null
    val end = position + size
    val text = mutableMapOf<String, String>()
    var artwork: EmbeddedArtwork? = null
    for (index in 0 until 256) {
        if (end - position < 10) break
        val frame = read(position, 10)
        if (frame.size != 10 || frame[0] == 0.toByte()) break
        val frameSize = frame.length(4, synchsafe = version == 4)
        val start = position + 10
        if (frameSize == null || frameSize > end - start) break
        position = start + frameSize
        if (frame.octet(9) != 0 || frameSize == 0L) continue
        val key = frame.ascii(0, 4)
        if (key in ID3_TEXT_KEYS && frameSize <= 16_385 && key !in text) {
            read(start, frameSize.toInt()).id3Text()?.let { text[key] = it }
        }
        if (key == "APIC" && artwork == null) {
            val prefix = read(start, minOf(4096, frameSize).toInt())
            artwork = prefix.pictureOffset()?.let { (offset, mime) ->
                if (offset < frameSize) EmbeddedArtwork(start + offset, frameSize - offset, mime) else null
            }
        }
    }
    return AudioTags(
        title = text["TIT2"],
        artist = text["TPE1"],
        album = text["TALB"],
        albumArtist = text["TPE2"],
        track = text["TRCK"].tagNumber(),
        disc = text["TPOS"].tagNumber(),
    ) to artwork
}

internal fun String?.tagNumber(): Int? = this?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

private val ID3_TEXT_KEYS = setOf("TIT2", "TPE1", "TALB", "TPE2", "TRCK", "TPOS")
private val ID3_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

private fun ByteArray.id3Text(): String? {
    val charset = when (firstOrNull()?.toInt()) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> return null
    }
    val value = try {
        charset.newDecoder().decode(ByteBuffer.wrap(this, 1, size - 1)).toString()
    } catch (_: CharacterCodingException) {
        return null
    }
    return value.substringBefore('\u0000').trim().take(4096).takeIf { it.isNotEmpty() }
}

private fun ByteArray.pictureOffset(): Pair<Int, String>? {
    if (size < 5) return null
    val mimeEnd = (1 until minOf(size - 2, 65)).firstOrNull { this[it] == 0.toByte() } ?: return null
    val mime = ascii(1, mimeEnd - 1).lowercase()
    if (mime !in ID3_IMAGE_TYPES) return null
    val description = mimeEnd + 2
    return when (octet(0)) {
        0, 3 -> (description until size).firstOrNull { this[it] == 0.toByte() }?.let { it + 1 to mime }
        1, 2 -> (description until size - 1 step 2)
            .firstOrNull { this[it] == 0.toByte() && this[it + 1] == 0.toByte() }?.let { it + 2 to mime }
        else -> null
    }
}

private fun ByteArray.ascii(offset: Int, count: Int): String = String(this, offset, count, Charsets.ISO_8859_1)
private fun ByteArray.octet(offset: Int): Int = this[offset].toInt() and 255

private fun ByteArray.length(offset: Int, synchsafe: Boolean): Long? {
    var length = 0L
    for (index in offset until offset + 4) {
        val value = octet(index)
        if (synchsafe && value > 127) return null
        length = (length shl (if (synchsafe) 7 else 8)) + value
    }
    return length
}
