package com.audipp.data.local

import android.content.ContentResolver
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal class AndroidSafAccess(private val resolver: ContentResolver) : SafAccess {
    override fun hasReadGrant(tree: Uri): Boolean = resolver.persistedUriPermissions.any {
        it.uri == tree && it.isReadPermission
    }

    override fun takeReadGrant(tree: Uri) {
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun releaseReadGrant(tree: Uri) {
        resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override suspend fun rootReadable(tree: Uri): Boolean {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return query(document).use { cursor ->
            if (cursor.incomplete()) throw SafIncompleteRead()
            val idColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            cursor.moveToFirst() && cursor.getString(idColumn) == id &&
                cursor.getString(mimeColumn) == Document.MIME_TYPE_DIR && !cursor.moveToNext()
        }
    }

    override fun children(tree: Uri, directoryId: String) = flow {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId)
        query(children).use { cursor ->
            val incompleteAtStart = cursor.incomplete()
            val idColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!cursor.moveToNext()) break
                val id = cursor.getString(idColumn)
                val mime = cursor.getString(mimeColumn)
                if (id.isNullOrBlank() || mime.isNullOrBlank()) throw SafIncompleteRead()
                emit(SafEntry(id, mime == Document.MIME_TYPE_DIR))
            }
            if (incompleteAtStart || cursor.incomplete()) throw SafIncompleteRead()
        }
    }

    override suspend fun analyze(document: Uri): SafAudio? = timedRequest { signal ->
        val descriptor = resolver.openAssetFileDescriptor(document, "r", signal)
            ?: throw IOException()
        descriptor.use {
            signal.throwIfCanceled()
            val audio = audioStream(it) ?: return@use null
            signal.throwIfCanceled()
            readTags(it, audio)
        }
    }.value

    private suspend fun query(uri: Uri): Cursor {
        var cursor: Cursor? = null
        try {
            timedRequest { signal ->
                cursor = resolver.query(uri, PROJECTION, null, null, null, signal)
            }
            return cursor ?: throw IOException()
        } catch (failure: Throwable) {
            cursor?.close()
            throw failure
        }
    }

    private fun Cursor.incomplete(): Boolean =
        extras.getBoolean(DocumentsContract.EXTRA_LOADING) || extras.containsKey(DocumentsContract.EXTRA_ERROR)

    private fun audioStream(descriptor: AssetFileDescriptor): SafAudio? {
        val extractor = MediaExtractor()
        try {
            if (descriptor.declaredLength < 0 && descriptor.startOffset == 0L) {
                extractor.setDataSource(descriptor.fileDescriptor)
            } else {
                extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    return SafAudio(
                        codec = mime,
                        sampleRate = format.positiveInt(MediaFormat.KEY_SAMPLE_RATE),
                        channels = format.positiveInt(MediaFormat.KEY_CHANNEL_COUNT),
                        durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            format.getLong(MediaFormat.KEY_DURATION).takeIf { it >= 0 }?.div(1000)
                        } else null,
                    )
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

            fun number(key: Int): Int? = tag(key)?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

            return audio.copy(
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                track = number(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                disc = number(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
            )
        } catch (_: RuntimeException) {
            return audio
        } finally {
            retriever.release()
        }
    }

    private data class Response<T>(val value: T)

    private suspend fun <T> timedRequest(request: (CancellationSignal) -> T): Response<T> =
        withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    continuation.resume(Response(request(signal)))
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                }
            }
        } ?: throw SafTimeout()

    private companion object {
        val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE)
    }
}
