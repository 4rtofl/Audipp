package com.audipp.data.local

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

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

    private fun list(rows: Cursor?): List<SafEntry> = runBlocking {
        register { rows }.children(tree, rootId).toList()
    }

    private fun cursor(extras: Bundle = Bundle.EMPTY): MatrixCursor =
        MatrixCursor(arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE)).apply {
            setExtras(extras)
        }

    private fun register(rows: (Uri) -> Cursor?): AndroidSafAccess {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(
                uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                sortOrder: String?,
            ) = rows(uri)
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
