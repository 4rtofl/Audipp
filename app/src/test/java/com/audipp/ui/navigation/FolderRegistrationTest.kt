package com.audipp.ui.navigation

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.local.LibraryScanIssue
import com.audipp.data.local.LibraryScanStatus
import com.audipp.data.local.SafAccess
import com.audipp.data.local.SafAudio
import com.audipp.data.local.SafEntry
import com.audipp.data.local.SafIncompleteRead
import com.audipp.data.local.SafLibrary
import com.audipp.data.local.ScanLimits
import com.audipp.ui.library.LibraryNotice
import com.audipp.ui.library.libraryNotice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FolderRegistrationTest {
    @Test
    fun cancellingAndDenyingThePickerLeavesTheFolderUnregistered() = withModel { model, library, access ->
        assertTrue(model.openPicker())
        assertFalse(model.openPicker())
        model.onFolderResult(resultOk = false, selection = null)
        assertEquals(FolderRegistration.Idle, model.registration.value)
        assertTrue(runBlocking { library.registeredRoots() }.isEmpty())

        model.onFolderResult(resultOk = true, selection = selection().setFlags(0))
        await { model.registration.value == FolderRegistration.Denied }
        assertFalse(access.granted)
        assertTrue(runBlocking { library.registeredRoots() }.isEmpty())
    }

    @Test
    fun incompleteScanRetriesTheSameFolderAndLostAccessRequiresPickingAgain() = withModel { model, library, access ->
        access.failRead = true
        model.onFolderResult(resultOk = true, selection = selection())
        await { model.registration.value is FolderRegistration.ScanFailed }
        val root = (model.registration.value as FolderRegistration.ScanFailed).rootId
        assertEquals(LibraryScanStatus.PARTIAL, runBlocking { library.lastScanStatus(root) })

        assertEquals(LibraryScanStatus.PARTIAL, model.lastScan.value?.status)
        assertEquals(setOf(LibraryScanIssue.DIRECTORY_READ), model.lastScan.value?.issues)

        access.failRead = false
        model.retryScan(root)
        await { model.registration.value == FolderRegistration.Idle }
        assertEquals(LibraryScanStatus.COMPLETE, model.lastScan.value?.status)
        assertEquals(1, model.lastScan.value?.progress?.indexed)
        assertEquals(listOf(root), runBlocking { library.registeredRoots() })
        assertEquals(LibraryScanStatus.COMPLETE, runBlocking { library.lastScanStatus(root) })

        access.granted = false
        model.retryScan(root)
        await { model.registration.value == FolderRegistration.AccessLost }
        assertTrue(model.openPicker())
        model.onFolderResult(resultOk = true, selection = selection())
        await { model.registration.value == FolderRegistration.Idle }
        assertEquals(LibraryScanStatus.COMPLETE, runBlocking { library.lastScanStatus(root) })
    }

    @Test
    fun aNewScanClearsTheEarlierResultSoItCannotStandInForAFailureOrCancellation() = withModel { model, library, access ->
        model.onFolderResult(resultOk = true, selection = selection())
        await { model.registration.value == FolderRegistration.Idle }
        assertEquals(LibraryScanStatus.COMPLETE, model.lastScan.value?.status)
        val root = runBlocking { library.registeredRoots() }.single()

        // While the next scan runs (and if it ends without a result), the earlier COMPLETE is not shown as the last scan.
        val gate = CompletableDeferred<Unit>()
        access.gate = gate
        model.retryScan(root)
        await { model.registration.value is FolderRegistration.Scanning }
        assertNull(model.lastScan.value)

        model.cancelScan()
        await { model.registration.value == FolderRegistration.ScanFailed(root) }
        assertEquals(LibraryScanStatus.CANCELLED, model.lastScan.value?.status)
        assertEquals(
            LibraryNotice.Cancelled(root),
            libraryNotice(HomeSources.Loading, model.registration.value, model.lastScan.value),
        )
    }

    private fun selection() = SafLibrary.openTreeIntent()
        .setData(DocumentsContract.buildTreeDocumentUri("audipp.fixture", "music"))

    private fun withModel(block: (NavigationViewModel, SafLibrary, FolderAccess) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
        val access = FolderAccess()
        val library = SafLibrary(database, access, ScanLimits())
        val model = NavigationViewModel(app, database, library)
        try {
            block(model, library, access)
        } finally {
            model.viewModelScope.cancel()
            database.close()
        }
    }

    private fun await(condition: () -> Boolean) {
        val main = shadowOf(android.os.Looper.getMainLooper())
        val deadline = System.nanoTime() + 10_000_000_000
        while (!condition() && System.nanoTime() < deadline) {
            main.idle()
            Thread.sleep(10)
        }
        assertTrue("Folder registration did not reach the expected state", condition())
    }

    private class FolderAccess : SafAccess {
        @Volatile var granted = false
        @Volatile var failRead = false
        @Volatile var gate: CompletableDeferred<Unit>? = null

        override fun hasReadGrant(tree: Uri) = granted
        override fun takeReadGrant(tree: Uri) { granted = true }
        override fun releaseReadGrant(tree: Uri) { granted = false }
        override suspend fun rootReadable(tree: Uri) = true
        override fun children(tree: Uri, directoryId: String) = flow {
            gate?.await()
            if (failRead) throw SafIncompleteRead()
            emit(SafEntry("recording", directory = false))
        }
        override suspend fun analyze(document: Uri, displayName: String?) = SafAudio("WAV")
    }
}
