package com.audipp.data.local

import com.audipp.domain.model.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class AudioContainer { FLAC, WAVE, MP3, ADTS, MP4, DSF, DFF }

internal enum class AudioProbeProblem {
    EMPTY, EXTENSION_MISMATCH, MALFORMED, UNRECOGNIZED, NON_MEDIA, UNSUPPORTED, INSPECTION_LIMIT,
}

internal sealed interface AudioProbeResult {
    data class Identified(
        val container: AudioContainer,
        val format: AudioFormat = AudioFormat(),
        val durationMs: Long? = null,
        val pcm: PcmLayout? = null,
        val artwork: EmbeddedArtwork? = null,
        val id3: Id3Location? = null,
        val releaseId: String? = null,
    ) : AudioProbeResult

    data class Rejected(val problem: AudioProbeProblem) : AudioProbeResult
}

internal data class Id3Location(val offset: Long, val limit: Long)

internal data class PcmLayout(val storageBits: Int, val floatingPoint: Boolean)

// The range is relative to the start of this document, even for an asset in a shared descriptor.
internal data class EmbeddedArtwork(val offset: Long, val length: Long, val mimeType: String)

private fun AudioContainer.matches(extension: String): Boolean = when (this) {
    AudioContainer.FLAC -> extension == "flac"
    AudioContainer.WAVE -> extension == "wav" || extension == "wave"
    AudioContainer.MP3 -> extension == "mp3"
    AudioContainer.ADTS -> extension == "aac" || extension == "adts"
    AudioContainer.MP4 -> extension == "m4a" || extension == "m4b" || extension == "mp4"
    AudioContainer.DSF -> extension == "dsf"
    AudioContainer.DFF -> extension == "dff"
}

/**
 * Inspects a bounded number of headers without reading audio frames or image payloads.
 * [read] supplies bytes at document-relative offsets and may propagate I/O or cancellation errors.
 */
internal fun probeAudioContainer(
    length: Long,
    displayName: String?,
    read: (offset: Long, count: Int) -> ByteArray,
): AudioProbeResult {
    require(length >= 0)
    if (length == 0L) return AudioProbeResult.Rejected(AudioProbeProblem.EMPTY)
    val extension = displayName.orEmpty().substringAfterLast('.', "").lowercase()
    val expected = AudioContainer.entries.any { it.matches(extension) }
    return try {
        val identified = ContainerReader(length, read).identify()
        when {
            identified == null && expected -> rejected(AudioProbeProblem.EXTENSION_MISMATCH)
            identified == null -> rejected(AudioProbeProblem.UNRECOGNIZED)
            expected && !identified.container.matches(extension) -> rejected(AudioProbeProblem.EXTENSION_MISMATCH)
            else -> identified
        }
    } catch (failure: ContainerFailure) {
        if (expected && failure.problem == AudioProbeProblem.NON_MEDIA) {
            rejected(AudioProbeProblem.EXTENSION_MISMATCH)
        } else {
            rejected(failure.problem)
        }
    }
}

private fun rejected(problem: AudioProbeProblem) = AudioProbeResult.Rejected(problem)
private class ContainerFailure(val problem: AudioProbeProblem) : Exception()
private fun malformed(): Nothing = throw ContainerFailure(AudioProbeProblem.MALFORMED)
private fun unsupported(): Nothing = throw ContainerFailure(AudioProbeProblem.UNSUPPORTED)

private class ContainerReader(
    private val length: Long,
    private val read: (Long, Int) -> ByteArray,
) {
    private var reads = 0

    private fun bytes(offset: Long, size: Int): ByteBuffer {
        end(offset, size.toLong(), length)
        if (++reads > MAX_READS) throw ContainerFailure(AudioProbeProblem.INSPECTION_LIMIT)
        val bytes = read(offset, size)
        if (bytes.size != size) malformed()
        return ByteBuffer.wrap(bytes)
    }

    fun identify(): AudioProbeResult.Identified? {
        val initial = bytes(0, minOf(12L, length).toInt())
        val id3 = initial.limit() >= 3 && initial.name(0, 3) == "ID3"
        val start = if (id3) skipId3() else 0L
        val tag = if (id3) Id3Location(0, start) else null
        val header = if (id3) bytes(start, minOf(12L, length - start).toInt()) else initial
        if (header.limit() >= 4 && header.name(0) == "fLaC") return flac(start, tag)
        if (!id3 && header.limit() >= 4) {
            when (header.name(0)) {
                "RIFF" -> {
                    if (header.limit() < 12) malformed()
                    if (header.name(8) == "WAVE") return wave(header)
                }
                "DSD " -> return dsf()
                "FRM8" -> return dff()
            }
        }
        if (!id3 && header.limit() >= 8 && header.name(4) == "ftyp") {
            return mp4()
        }
        if (header.limit() >= 2 && header.u8(0) == 0xff) {
            val sync = header.u8(1)
            if (sync and 0xf6 == 0xf0) {
                if (header.limit() < 7) malformed()
                val size = ((header.u8(3) and 3) shl 11) or
                    (header.u8(4) shl 3) or (header.u8(5) ushr 5)
                if (size <= (if (sync and 1 == 0) 9 else 7) || header.u8(2) and 0x3c >= 0x34) malformed()
                end(start, size.toLong(), length)
                return AudioProbeResult.Identified(AudioContainer.ADTS, id3 = tag)
            }
            if (sync and 0xe0 == 0xe0 && (sync ushr 1) and 3 == 1) {
                if (header.limit() < 4 || sync and 0x18 == 8) malformed()
                val bitrate = header.u8(2) ushr 4
                val rate = (header.u8(2) ushr 2) and 3
                if (bitrate == 15 || rate == 3) malformed()
                if (bitrate == 0) unsupported()
                val version = (sync ushr 3) and 3
                val hz = MP3_RATES[rate] / (if (version == 3) 1 else if (version == 2) 2 else 4)
                val kbps = if (version == 3) MP3_V1_BITRATES[bitrate - 1] else MP3_V2_BITRATES[bitrate - 1]
                val frameBytes = (if (version == 3) 144_000 else 72_000) * kbps / hz + ((header.u8(2) ushr 1) and 1)
                end(start, frameBytes.toLong(), length)
                return AudioProbeResult.Identified(AudioContainer.MP3, id3 = tag)
            }
        }
        return null
    }

    private fun mp4(): AudioProbeResult.Identified {
        val file = Box(0, length)
        val fileType = file.child("ftyp") ?: malformed()
        val brandBytes = fileType.limit - fileType.start
        if (brandBytes < 8 || brandBytes % 4 != 0L) malformed()
        if (bytes(fileType.start, 4).name(0) in IMAGE_BRANDS) {
            throw ContainerFailure(AudioProbeProblem.NON_MEDIA)
        }
        val movie = file.child("moov") ?: malformed()
        val userData = movie.child("udta")
        val metadata = userData?.child("meta") ?: movie.child("meta")
        val items = metadata?.let {
            if (it.limit - it.start < 4 || bytes(it.start, 4).getInt(0) != 0) return@let null
            Box(it.start + 4, it.limit).child("ilst")
        }
        val artwork = items?.child("covr")?.child("data")?.let {
            if (it.limit - it.start <= 8) return@let null
            val mime = when (bytes(it.start, 8).getInt(0)) {
                13 -> "image/jpeg"
                14 -> "image/png"
                else -> return@let null
            }
            EmbeddedArtwork(it.start + 8, it.limit - it.start - 8, mime)
        }
        return AudioProbeResult.Identified(AudioContainer.MP4, artwork = artwork, releaseId = items?.let(::mp4ReleaseId))
    }

    private fun mp4ReleaseId(items: Box): String? {
        var position = items.start
        while (position < items.limit) {
            val item = items.boxAt(position)
            position = item.second.limit
            if (item.first != "----") continue
            val mean = item.second.child("mean")?.text(4)
            val name = item.second.child("name")?.text(4)
            if (mean != "com.apple.iTunes" || !name.equals(MUSICBRAINZ_ALBUM_ID, ignoreCase = true)) continue
            return item.second.child("data")?.text(8)?.let(::musicBrainzReleaseId)
        }
        return null
    }

    private fun Box.text(skip: Int): String? {
        val size = limit - start - skip
        if (size !in 1..256) return null
        return String(bytes(start + skip, size.toInt()).array(), Charsets.UTF_8)
    }

    private data class Box(val start: Long, val limit: Long)

    private fun Box.child(type: String): Box? {
        var position = start
        while (position < limit) {
            val (name, child) = boxAt(position)
            if (name == type) return child
            position = child.limit
        }
        return null
    }

    private fun Box.boxAt(position: Long): Pair<String, Box> {
        end(position, 8, limit)
        val header = bytes(position, 8)
        var payload = position + 8
        val size = when (val shortSize = header.u32(0)) {
            0L -> limit - position
            1L -> {
                payload = end(payload, 8, limit)
                bytes(payload - 8, 8).getLong(0)
            }
            else -> shortSize
        }
        if (size < payload - position) malformed()
        return header.name(4) to Box(payload, end(position, size, limit))
    }

    private fun skipId3(): Long {
        val header = bytes(0, 10)
        val version = header.u8(3)
        if (version !in 2..4) unsupported()
        var size = 0L
        for (index in 6..9) {
            val octet = header.u8(index)
            if (octet > 127) malformed()
            size = (size shl 7) + octet
        }
        val footer = if (version == 4 && header.u8(5) and 0x10 != 0) 10L else 0L
        val next = end(10, size + footer, length)
        if (next == length) malformed()
        return next
    }

    private fun flac(start: Long, id3: Id3Location?): AudioProbeResult.Identified {
        val block = bytes(start + 4, 4)
        if (block.u8(0) and 127 != 0 || block.u24(1) != 34L) malformed()
        val streamInfo = bytes(start + 8, 34)
        val minBlockSize = streamInfo.u16(0)
        val maxBlockSize = streamInfo.u16(2)
        if (minBlockSize < 16 || minBlockSize > maxBlockSize) malformed()
        val info = streamInfo.getLong(10)
        val rate = (info ushr 44).toInt()
        val channels = ((info ushr 41).toInt() and 7) + 1
        val bits = ((info ushr 36).toInt() and 31) + 1
        val samples = info and 0xf_ffff_ffffL
        if (rate !in 1..1_048_575 || bits < 4) malformed()

        var last = block.u8(0) and 128 != 0
        var position = start + 42
        var artwork: EmbeddedArtwork? = null
        var releaseId: String? = null
        while (!last) {
            val metadata = bytes(position, 4)
            val type = metadata.u8(0) and 127
            if (type == 0 || type == 127) malformed()
            last = metadata.u8(0) and 128 != 0
            val next = end(position + 4, metadata.u24(1), length)
            if (type == 6 && artwork == null) artwork = picture(position + 4, next)
            if (type == 4 && releaseId == null) releaseId = vorbisReleaseId(position + 4, next)
            position = next
        }
        end(position, 9, length)
        val frame = bytes(position, 4)
        if (frame.u8(0) != 255 || frame.u8(1) and 0xfe != 0xf8 ||
            frame.u8(2) ushr 4 == 0 || frame.u8(2) and 15 == 15 || frame.u8(3) and 1 != 0
        ) malformed()
        return AudioProbeResult.Identified(
            AudioContainer.FLAC,
            AudioFormat("audio/flac", rate, bits, channels),
            samples.takeIf { it > 0 }?.let { duration(it, rate) },
            artwork = artwork,
            id3 = id3,
            releaseId = releaseId,
        )
    }

    // Lyrics or images stored as comments can make this block large; such a block is not read.
    // A malformed comment leaves the release unknown instead of rejecting playable audio.
    private fun vorbisReleaseId(start: Long, limit: Long): String? {
        if (limit - start > MAX_VORBIS_COMMENT_BYTES) return null
        val block = bytes(start, (limit - start).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        fun field(): Long? = if (block.limit() - offset < 4) null else block.u32(offset).also { offset += 4 }
        val vendor = field() ?: return null
        if (vendor > block.limit() - offset) return null
        offset += vendor.toInt()
        val count = field() ?: return null
        for (index in 0 until count) {
            val size = field() ?: return null
            if (size > block.limit() - offset) return null
            val comment = String(block.array(), offset, size.toInt(), Charsets.UTF_8)
            offset += size.toInt()
            if (comment.substringBefore('=', "").equals("MUSICBRAINZ_ALBUMID", ignoreCase = true)) {
                return musicBrainzReleaseId(comment.substringAfter('='))
            }
        }
        return null
    }

    private fun picture(start: Long, limit: Long): EmbeddedArtwork? {
        if (limit - start < 32) malformed()
        val mimeLength = bytes(start + 4, 4).u32(0)
        val description = end(start + 8, mimeLength, limit)
        end(description, 4, limit)
        val dimensions = end(description + 4, bytes(description, 4).u32(0), limit)
        val image = end(dimensions, 20, limit)
        val imageLength = bytes(dimensions + 16, 4).u32(0)
        if (end(image, imageLength, limit) != limit) malformed()
        if (imageLength == 0L || mimeLength !in 1..64) return null
        val mime = bytes(start + 8, mimeLength.toInt()).name(0, mimeLength.toInt())
        return if (mime in IMAGE_MIME_TYPES) EmbeddedArtwork(image, imageLength, mime) else null
    }

    private fun wave(header: ByteBuffer): AudioProbeResult.Identified {
        val limit = end(8, header.order(ByteOrder.LITTLE_ENDIAN).u32(4), length)
        if (limit < 12) malformed()
        var position = 12L
        var format: ByteBuffer? = null
        var payload = 0L
        var id3: Id3Location? = null
        while (position < limit) {
            val chunk = bytes(position, 8).order(ByteOrder.LITTLE_ENDIAN)
            val size = chunk.u32(4)
            val body = position + 8
            position = end(body, size + (size and 1), limit)
            when (chunk.name(0)) {
                "fmt " -> {
                    if (size < 16 || format != null) malformed()
                    format = bytes(body, minOf(size, 40).toInt()).order(ByteOrder.LITTLE_ENDIAN)
                }
                "id3 ", "ID3 " -> if (size >= 10) id3 = Id3Location(body, body + size)
                "data" -> {
                    if (payload > 0L || size == 0L) malformed()
                    payload = size
                }
            }
        }
        val fmt = format ?: malformed()
        if (payload == 0L) malformed()
        val channels = fmt.u16(2).takeIf { it > 0 } ?: malformed()
        val rate = fmt.getInt(4).takeIf { it > 0 } ?: malformed()
        val alignment = fmt.u16(12).takeIf { it > 0 } ?: malformed()
        val width = fmt.u16(14)
        val tag = fmt.u16(0)
        val extensible = tag == 0xfffe
        val encoding = if (extensible) {
            if (fmt.limit() < 40 || fmt.u16(16) < 22) malformed()
            fmt.u16(24).takeIf { fmt.nameBytes(26, 14) == WAVE_GUID_TAIL }
        } else {
            tag
        }
        if (encoding != 1 && encoding != 3) {
            return AudioProbeResult.Identified(AudioContainer.WAVE, id3 = id3)
        }
        val floating = encoding == 3
        val storageBits = if (extensible) width else ((width + 7) / 8) * 8
        val validBits = if (extensible) fmt.u16(18) else width
        if (width == 0 || storageBits % 8 != 0 || validBits > storageBits ||
            alignment != channels * (storageBits / 8) || payload % alignment != 0L ||
            (floating && storageBits != 32 && storageBits != 64)
        ) malformed()
        return AudioProbeResult.Identified(
            AudioContainer.WAVE,
            AudioFormat(
                if (floating) "audio/x-wav-float" else "audio/raw", rate,
                validBits.takeIf { !floating && it > 0 }, channels,
            ),
            duration(payload / alignment, rate),
            pcm = PcmLayout(storageBits, floating),
            id3 = id3,
        )
    }

    private fun dsf(): AudioProbeResult.Identified {
        val head = bytes(4, 24).order(ByteOrder.LITTLE_ENDIAN)
        val fileSize = head.getLong(8)
        val metadata = head.getLong(16)
        if (head.getLong(0) != 28L || fileSize != length) malformed()
        val fmt = bytes(28, 52).order(ByteOrder.LITTLE_ENDIAN)
        val formatSize = fmt.getLong(4)
        if (fmt.name(0) != "fmt " || formatSize < 52L) malformed()
        val dataStart = end(28, formatSize, length)
        if (fmt.getInt(12) != 1 || fmt.getInt(16) != 0) unsupported()
        val channels = fmt.getInt(24).takeIf { it in 1..6 } ?: malformed()
        val rate = fmt.getInt(28).takeIf { it > 0 } ?: malformed()
        val samples = fmt.getLong(36).takeIf { it > 0 } ?: malformed()
        val block = fmt.getInt(44)
        if ((fmt.getInt(32) != 1 && fmt.getInt(32) != 8) || block != 4096) malformed()
        val data = bytes(dataStart, 12).order(ByteOrder.LITTLE_ENDIAN)
        val chunkSize = data.getLong(4)
        if (data.name(0) != "data" || chunkSize <= 12L) malformed()
        val dataEnd = end(dataStart, chunkSize, length)
        val blocks = (samples - 1) / (block * 8) + 1
        if (chunkSize - 12L != blocks * block * channels) malformed()
        val id3 = if (metadata == 0L) {
            if (dataEnd != length) malformed()
            null
        } else {
            if (metadata != dataEnd || bytes(metadata, 3).name(0, 3) != "ID3") malformed()
            Id3Location(metadata, length)
        }
        return AudioProbeResult.Identified(
            AudioContainer.DSF, AudioFormat("audio/x-dsd", rate, channelCount = channels),
            duration(samples, rate), id3 = id3,
        )
    }

    private fun dff(): AudioProbeResult.Identified {
        val form = bytes(4, 12)
        if (form.name(8) != "DSD ") unsupported()
        val limit = end(12, form.getLong(0), length)
        if (limit < 32) malformed()
        val versionHeader = bytes(16, 16)
        if (versionHeader.name(0) != "FVER" || versionHeader.getLong(4) != 4L) malformed()
        if (versionHeader.u8(12) != 1) unsupported()
        var position = 32L
        var rate = 0
        var channels = 0
        var compression: String? = null
        var dataType: String? = null
        var dataLength = 0L
        var dstData: Box? = null
        var id3: Id3Location? = null
        while (position < limit) {
            val chunk = bytes(position, 12)
            val size = chunk.getLong(4)
            val body = position + 12
            val chunkEnd = end(body, size, limit)
            position = end(chunkEnd, size and 1, limit)
            when (chunk.name(0)) {
                "PROP" -> {
                    if (size < 4 || bytes(body, 4).name(0) != "SND ") continue
                    var property = body + 4
                    while (property < chunkEnd) {
                        end(property, 12, chunkEnd)
                        val field = bytes(property, 12)
                        val fieldSize = field.getLong(4)
                        val value = property + 12
                        val fieldEnd = end(value, fieldSize, chunkEnd)
                        property = end(fieldEnd, fieldSize and 1, chunkEnd)
                        when (field.name(0)) {
                            "FS  " -> {
                                if (fieldSize != 4L || rate != 0) malformed()
                                rate = bytes(value, 4).getInt(0).takeIf { it > 0 } ?: malformed()
                            }
                            "CHNL" -> {
                                if (fieldSize < 2 || channels != 0) malformed()
                                channels = bytes(value, 2).u16(0)
                                if (channels == 0 || fieldSize != 2L + 4L * channels) malformed()
                            }
                            "CMPR" -> {
                                if (fieldSize < 5 || compression != null) malformed()
                                val info = bytes(value, 5)
                                if (5L + info.u8(4) > fieldSize) malformed()
                                compression = info.name(0)
                            }
                        }
                    }
                }
                "DSD ", "DST " -> {
                    if (size == 0L || dataType != null) malformed()
                    dataType = chunk.name(0)
                    dataLength = size
                    if (dataType == "DST ") {
                        dstData = Box(body, chunkEnd)
                    }
                }
                "ID3 " -> if (size >= 10) id3 = Id3Location(body, chunkEnd)
            }
        }
        if (rate == 0 || channels == 0 || compression == null || dataType == null) malformed()
        if (compression != "DSD " && compression != "DST ") unsupported()
        if (dataType != compression) malformed()
        val dst = compression == "DST "
        if (!dst && (dataLength % channels != 0L || dataLength / channels > Long.MAX_VALUE / 8)) malformed()
        return AudioProbeResult.Identified(
            AudioContainer.DFF, AudioFormat(if (dst) "audio/x-dst" else "audio/x-dsd", rate, channelCount = channels),
            if (dst) dstDuration(checkNotNull(dstData)) else duration(dataLength / channels * 8, rate), id3 = id3,
        )
    }

    private fun dstDuration(data: Box): Long? {
        if (data.limit - data.start <= 30) malformed()
        val frameInfo = bytes(data.start, 18)
        if (frameInfo.name(0) != "FRTE" || frameInfo.getLong(4) != 6L) malformed()
        val declaredFrames = frameInfo.u32(12)
        val framesPerSecond = frameInfo.u16(16)
        if (declaredFrames == 0L || framesPerSecond != 75) malformed()

        var frames = 0L
        var position = data.start + 18
        while (position < data.limit) {
            val body = end(position, 12, data.limit)
            if (reads == MAX_READS) return null
            val chunk = bytes(position, 12)
            val size = chunk.getLong(4)
            position = end(end(body, size, data.limit), size and 1, data.limit)
            if (chunk.name(0) == "DSTF") {
                if (size == 0L || ++frames > declaredFrames) malformed()
            }
        }
        if (frames != declaredFrames) malformed()
        return duration(frames, framesPerSecond)
    }

    private companion object {
        const val MAX_READS = 512
        const val MAX_VORBIS_COMMENT_BYTES = 64L * 1024
        val IMAGE_BRANDS = setOf("avif", "avis", "heic", "heix", "hevc", "hevx", "mif1", "msf1")
        val IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
        val MP3_RATES = intArrayOf(44100, 48000, 32000)
        val MP3_V1_BITRATES = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val MP3_V2_BITRATES = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        val WAVE_GUID_TAIL = listOf(0, 0, 0, 0, 16, 0, 128, 0, 0, 170, 0, 56, 155, 113)
    }
}

private fun end(start: Long, size: Long, limit: Long): Long {
    if (size < 0 || start !in 0..limit || size > limit - start) malformed()
    return start + size
}

private fun duration(samples: Long, rate: Int): Long? {
    val seconds = samples / rate
    val milliseconds = samples % rate * 1000 / rate
    return if (seconds > (Long.MAX_VALUE - milliseconds) / 1000) null else seconds * 1000 + milliseconds
}

private fun ByteBuffer.name(offset: Int, size: Int = 4): String =
    String(array(), offset, size, Charsets.ISO_8859_1)

private fun ByteBuffer.nameBytes(offset: Int, size: Int): List<Int> = (offset until offset + size).map(::u8)
private fun ByteBuffer.u8(offset: Int) = get(offset).toInt() and 255
private fun ByteBuffer.u16(offset: Int) = getShort(offset).toInt() and 65535
private fun ByteBuffer.u24(offset: Int) =
    (u8(offset).toLong() shl 16) or (u8(offset + 1).toLong() shl 8) or u8(offset + 2).toLong()
private fun ByteBuffer.u32(offset: Int) = getInt(offset).toLong() and 0xffff_ffffL
