package com.audipp.data.local

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
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
            val nameColumn = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!cursor.moveToNext()) break
                val id = cursor.getString(idColumn)
                val mime = cursor.getString(mimeColumn)
                if (id.isNullOrBlank() || mime.isNullOrBlank()) throw SafIncompleteRead()
                val displayName = if (nameColumn >= 0) cursor.getString(nameColumn) else null
                emit(SafEntry(id, mime == Document.MIME_TYPE_DIR, displayName))
            }
            if (incompleteAtStart || cursor.incomplete()) throw SafIncompleteRead()
        }
    }

    override suspend fun analyze(document: Uri, displayName: String?): SafAudio? = timedRequest { signal ->
        val descriptor = resolver.openAssetFileDescriptor(document, "r", signal) ?: throw IOException()
        descriptor.use { readSafAudio(it, displayName, signal) }
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
        val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME)
    }
}
