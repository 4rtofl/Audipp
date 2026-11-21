package com.audipp.data.local

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class AndroidSafAccessTest {
    private val tree = DocumentsContract.buildTreeDocumentUri("fixture", "volume:Music")
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun acceptsAnEmptyListingOnlyWhenTheProviderReportsItFinished() {
        assertEquals(emptyList<SafEntry>(), list(cursor()))
        assertThrows(SafIncompleteRead::class.java) {
            list(cursor(Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }))
        }
        val failed = cursor(Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "sensitive") })
        assertThrows(SafIncompleteRead::class.java) { list(failed) }
        assertTrue(failed.isClosed)
        assertThrows(IOException::class.java) { list(null) }
    }

    @Test
    fun partialListingYieldsAlreadyReadRowsAndClosesTheCursor() {
        val loading = cursor(Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }).apply {
            addRow(arrayOf("received", "audio/flac"))
        }
        val access = register { loading }
        val entries = mutableListOf<SafEntry>()
        assertThrows(SafIncompleteRead::class.java) {
            runBlocking { access.children(tree, rootId).toList(entries) }
        }
        assertEquals(listOf(SafEntry("received", false)), entries)
        assertTrue(loading.isClosed)
    }

    @Test
    fun usesTheTreeGrantForDescendantsAndClosesAStoppedListing() = runBlocking {
        val rows = cursor().apply {
            addRow(arrayOf("child", Document.MIME_TYPE_DIR))
            addRow(arrayOf("second", "audio/flac"))
        }
        val expected = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "sub/dir")
        val access = register { uri ->
            assertEquals(expected, uri)
            rows
        }
        assertEquals(listOf(SafEntry("child", true)), access.children(tree, "sub/dir").take(1).toList())
        assertTrue(rows.isClosed)
    }

    @Test
    fun rootProbeChecksThatTheProviderCanStillReadTheSelectedDirectory() = runBlocking {
        var response = cursor().apply { addRow(arrayOf(rootId, Document.MIME_TYPE_DIR)) }
        val access = register { uri ->
            assertEquals(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), uri)
            response
        }
        assertTrue(access.rootReadable(tree))
        assertTrue(response.isClosed)
        response = cursor()
        assertFalse(access.rootReadable(tree))
        assertTrue(response.isClosed)
    }

    @Test
    fun skipsNonMediaFilesAndContainersWithAVideoTrack() = runBlocking {
        val content = File.createTempFile("saf-document", ".bin", context.cacheDir)
        try {
            val access = register(content) { null }
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, "album/file")
            // Robolectric does not parse media, so the opened descriptor is mapped to fixture tracks.
            DataSource.setFileDescriptorTransform { _, _ -> "document" }
            val source = DataSource.toDataSource("document")
            ShadowMediaExtractor.addTrack(source, MediaFormat.createAudioFormat("audio/flac", 44_100, 2), ByteArray(0))
            assertEquals("audio/flac", access.analyze(document)?.codec)

            ShadowMediaExtractor.addTrack(source, MediaFormat.createVideoFormat("video/avc", 16, 16), ByteArray(0))
            assertNull(access.analyze(document))

            // MediaExtractor rejects images and text files by throwing IOException from setDataSource.
            DataSource.setFileDescriptorTransform { _, _ -> throw IOException() }
            assertNull(access.analyze(document))
        } finally {
            content.delete()
        }
    }

    private fun list(rows: Cursor?): List<SafEntry> = runBlocking {
        register { rows }.children(tree, rootId).toList()
    }

    private fun cursor(extras: Bundle = Bundle.EMPTY): MatrixCursor =
        MatrixCursor(arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE)).apply {
            setExtras(extras)
        }

    private fun register(file: File? = null, rows: (Uri) -> Cursor?): AndroidSafAccess {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(
                uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                sortOrder: String?,
            ) = rows(uri)
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
                ParcelFileDescriptor.open(requireNotNull(file), ParcelFileDescriptor.MODE_READ_ONLY)
            override fun getType(uri: Uri): String? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only")
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read-only")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
                error("read-only")
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = tree.authority })
        ShadowContentResolver.registerProviderInternal(tree.authority, provider)
        return AndroidSafAccess(context.contentResolver)
    }
}
