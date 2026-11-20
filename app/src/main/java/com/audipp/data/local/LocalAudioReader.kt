package com.audipp.data.local

import android.content.res.AssetFileDescriptor
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException

internal sealed interface LocalAudioRead {
    data class Identified(
        val header: AudioProbeResult.Identified,
        val tags: AudioTags,
        val artwork: EmbeddedArtwork?,
    ) : LocalAudioRead

    data class Rejected(val problem: AudioProbeProblem) : LocalAudioRead
}

/** Borrows a read-only descriptor on the caller's worker and leaves its file position untouched. */
internal fun readLocalAudio(
    descriptor: AssetFileDescriptor,
    displayName: String?,
    signal: CancellationSignal,
): LocalAudioRead {
    val file = descriptor.fileDescriptor
    val start = descriptor.startOffset
    signal.throwIfCanceled()
    try {
        val length = if (descriptor.declaredLength >= 0) {
            descriptor.declaredLength
        } else {
            val stat = Os.fstat(file)
            if (!OsConstants.S_ISREG(stat.st_mode)) throw IOException()
            stat.st_size - start
        }
        if (start < 0 || length < 0 || length > Long.MAX_VALUE - start) throw IOException()
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
                LocalAudioRead.Identified(probe, tagged?.first ?: AudioTags(), probe.artwork ?: tagged?.second)
            }
            is AudioProbeResult.Rejected -> LocalAudioRead.Rejected(probe.problem)
        }
        signal.throwIfCanceled()
        return result
    } catch (failure: ErrnoException) {
        throw IOException(failure)
    }
}
