package com.audipp.data.local

import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException

/** Borrows a read-only descriptor on the caller's worker and leaves its file position untouched. */
internal fun readSafAudio(
    descriptor: AssetFileDescriptor,
    displayName: String?,
    signal: CancellationSignal,
): SafAudio? {
    val file = descriptor.fileDescriptor
    val start = descriptor.startOffset
    signal.throwIfCanceled()
    try {
        val stat = Os.fstat(file)
        val regular = OsConstants.S_ISREG(stat.st_mode)
        val length = when {
            descriptor.declaredLength >= 0 -> descriptor.declaredLength
            regular -> stat.st_size - start
            else -> throw IOException()
        }
        if (start < 0 || length < 0 || length > Long.MAX_VALUE - start) throw IOException()
        if (regular && start + length > stat.st_size) throw SafAudioRejected(LibraryScanIssue.CORRUPT_AUDIO)

        fun read(offset: Long, count: Int): ByteArray {
            signal.throwIfCanceled()
            if (offset !in 0..length || count.toLong() > length - offset) return byteArrayOf()
            val buffer = ByteArray(count)
            var copied = 0
            while (copied < count) {
                signal.throwIfCanceled()
                val read = Os.pread(file, buffer, copied, count - copied, start + offset + copied)
                if (read == 0) break
                copied += read
            }
            return if (copied == count) buffer else buffer.copyOf(copied)
        }

        val result = when (val probe = probeAudioContainer(length, displayName, ::read)) {
            is AudioProbeResult.Identified -> {
                val tagged = probe.id3?.let { readId3Metadata(it, ::read) }
                analyzeIdentified(descriptor, probe, tagged?.first ?: AudioTags(), probe.artwork ?: tagged?.second, signal)
            }
            is AudioProbeResult.Rejected -> when (probe.problem) {
                AudioProbeProblem.NON_MEDIA -> null
                AudioProbeProblem.UNRECOGNIZED -> (platformStream(descriptor) as? PlatformStream.Audio)
                    ?.let { readTags(descriptor, it.metadata) }
                AudioProbeProblem.UNSUPPORTED -> throw SafAudioRejected(LibraryScanIssue.UNSUPPORTED_AUDIO)
                AudioProbeProblem.EMPTY -> throw SafAudioRejected(LibraryScanIssue.EMPTY_AUDIO)
                AudioProbeProblem.MALFORMED -> throw SafAudioRejected(LibraryScanIssue.CORRUPT_AUDIO)
                AudioProbeProblem.EXTENSION_MISMATCH -> throw SafAudioRejected(LibraryScanIssue.FORMAT_MISMATCH)
                AudioProbeProblem.INSPECTION_LIMIT -> throw SafAudioRejected(LibraryScanIssue.FORMAT_INSPECTION_LIMIT)
            }
        }
        signal.throwIfCanceled()
        return result
    } catch (failure: ErrnoException) {
        throw IOException(failure)
    }
}

private fun analyzeIdentified(
    descriptor: AssetFileDescriptor,
    header: AudioProbeResult.Identified,
    tags: AudioTags,
    artwork: EmbeddedArtwork?,
    signal: CancellationSignal,
): SafAudio? {
    val source = header.format
    val dsd = header.container == AudioContainer.DSF || header.container == AudioContainer.DFF
    val stream = if (dsd) null else platformStream(descriptor)
    signal.throwIfCanceled()
    if (stream == PlatformStream.Video) return null
    val platform = (stream as? PlatformStream.Audio)?.metadata
    val codec = when (header.container) {
        AudioContainer.MP3 -> platform?.codec?.takeIf { it == "audio/mpeg" }
        AudioContainer.ADTS -> platform?.codec?.takeIf { it == "audio/mp4a-latm" }
        AudioContainer.MP4 -> platform?.codec
        AudioContainer.WAVE -> source.codec ?: platform?.codec
        else -> source.codec
    } ?: throw SafAudioRejected(when (header.container) {
        AudioContainer.MP4 -> LibraryScanIssue.PLATFORM_AUDIO_UNAVAILABLE
        AudioContainer.WAVE -> LibraryScanIssue.UNSUPPORTED_AUDIO
        else -> LibraryScanIssue.CORRUPT_AUDIO
    })
    val audio = SafAudio(
        codec = codec,
        sampleRate = source.sampleRateHz ?: platform?.sampleRate,
        channels = source.channelCount ?: platform?.channels,
        durationMs = header.durationMs ?: platform?.durationMs,
        title = tags.title,
        artist = tags.artist,
        album = tags.album,
        albumArtist = tags.albumArtist,
        track = tags.track,
        disc = tags.disc,
        container = header.container,
        bitDepth = source.bitDepth,
        pcm = header.pcm,
        artwork = artwork,
        standardPath = !dsd && platform != null,
        musicBrainzReleaseId = header.releaseId ?: tags.releaseId,
    )
    return if (platform == null) audio else readTags(descriptor, audio)
}

private sealed interface PlatformStream {
    data class Audio(val metadata: SafAudio) : PlatformStream
    data object Video : PlatformStream
}

private fun platformStream(descriptor: AssetFileDescriptor): PlatformStream? {
    val extractor = MediaExtractor()
    try {
        try {
            if (descriptor.declaredLength < 0 && descriptor.startOffset == 0L) {
                extractor.setDataSource(descriptor.fileDescriptor)
            } else {
                extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
        } catch (_: IOException) {
            return null
        }
        val formats = List(extractor.trackCount, extractor::getTrackFormat)
        if (formats.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) {
            return PlatformStream.Video
        }
        for ((index, format) in formats.withIndex()) {
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                extractor.selectTrack(index)
                if (extractor.sampleTrackIndex != index) {
                    extractor.unselectTrack(index)
                    continue
                }
                return PlatformStream.Audio(SafAudio(
                    codec = mime,
                    sampleRate = format.positiveInt(MediaFormat.KEY_SAMPLE_RATE),
                    channels = format.positiveInt(MediaFormat.KEY_CHANNEL_COUNT),
                    durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION).takeIf { it >= 0 }?.div(1000)
                    } else null,
                ))
            }
        }
        return null
    } finally {
        extractor.release()
    }
}

private fun MediaFormat.positiveInt(key: String): Int? =
    if (containsKey(key)) getInteger(key).takeIf { it > 0 } else null

private fun readTags(descriptor: AssetFileDescriptor, audio: SafAudio): SafAudio {
    val retriever = MediaMetadataRetriever()
    try {
        if (descriptor.declaredLength < 0 && descriptor.startOffset == 0L) {
            retriever.setDataSource(descriptor.fileDescriptor)
        } else {
            retriever.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
        }
        fun tag(key: Int): String? =
            retriever.extractMetadata(key)?.take(4096)?.trim()?.takeIf { it.isNotEmpty() }

        return audio.copy(
            title = audio.title ?: tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = audio.artist ?: tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = audio.album ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            albumArtist = audio.albumArtist ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            track = audio.track ?: tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).tagNumber(),
            disc = audio.disc ?: tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER).tagNumber(),
        )
    } catch (_: RuntimeException) {
        return audio
    } finally {
        try {
            retriever.release()
        } catch (_: IOException) {
            // Tags that were already read remain valid.
        }
    }
}
