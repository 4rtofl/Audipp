package com.audipp.data.local

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.data.db.LibraryDatabase
import com.audipp.domain.model.Availability
import com.audipp.domain.model.VariantId
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafLibraryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: LibraryDatabase
    private val access = FixtureAccess()
    private val dao get() = db.libraryDao()
    private val library get() = SafLibrary(db, access, ScanLimits())

    @Before
    fun open() {
        context.deleteDatabase(DB_NAME)
        db = LibraryDatabase.open(context, DB_NAME)
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun acceptsOnlyPersistableReadFromThePickerAndRetainsTheRootAcrossReopen() = runBlocking {
        val scanner = library
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, SafLibrary.openTreeIntent().action)
        assertEquals(LocalTreeRegistration.Rejected.INVALID_SELECTION, scanner.registerLocalTree(null))
        assertEquals(LocalTreeRegistration.Rejected.INVALID_SELECTION,
            scanner.registerLocalTree(selection().setData(Uri.parse("file:///tree/root"))))
        assertEquals(LocalTreeRegistration.Rejected.INVALID_SELECTION, scanner.registerLocalTree(
            selection().setData(DocumentsContract.buildDocumentUriUsingTree(tree(), "file"))))
        assertEquals(LocalTreeRegistration.Rejected.READ_GRANT_REQUIRED,
            scanner.registerLocalTree(selection().setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)))
        assertEquals(LocalTreeRegistration.Rejected.PERSISTABLE_GRANT_REQUIRED,
            scanner.registerLocalTree(selection().setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
        assertEquals(0, access.grantAttempts)
        assertTrue(scanner.registeredRoots().isEmpty())

        assertEquals(LocalTreeRegistration.Rejected.ACCESS_DENIED,
            SafLibrary(context, db).registerLocalTree(selection()))
        assertTrue(scanner.registeredRoots().isEmpty())

        access.denyGrant = true
        assertEquals(LocalTreeRegistration.Rejected.ACCESS_DENIED, scanner.registerLocalTree(selection()))
        assertTrue(scanner.registeredRoots().isEmpty())

        access.denyGrant = false
        val root = register(scanner)
        db.close()
        db = LibraryDatabase.open(context, DB_NAME)
        assertEquals(listOf(root), library.registeredRoots())
        assertEquals(root, register(library))
    }

    @Test
    fun completesOnlyWithinTheScannedRootAndRestoresExistingVariantReferences() = runBlocking {
        val scanner = library
        val (root, song) = indexSong(scanner, this)
        val otherRoot = register(scanner, "other")
        access.entries["other"] = listOf(file("song"))
        assertEquals(1, scanner.startScan(otherRoot, this).await().progress.indexed)
        val otherSong = variant("other")
        access.entries["root"] = emptyList()

        val missing = scanner.startScan(root, this).await()
        assertEquals(LibraryScanStatus.COMPLETE, missing.status)
        assertEquals(1, missing.missing)
        assertEquals(Availability.MISSING, availability(song))
        assertEquals(Availability.PLAYABLE, availability(otherSong))
        val repeated = scanner.startScan(root, this).await()
        assertNotEquals(missing.scanId, repeated.scanId)
        assertEquals(0, repeated.missing)

        access.grants.remove(tree())
        val revoked = scanner.startScan(root, this).await()
        assertEquals(LibraryScanStatus.ACCESS_LOST, revoked.status)
        assertEquals(0, revoked.missing)
        assertEquals(Availability.MISSING, availability(song))
        assertEquals(root, register(scanner))
        access.entries["root"] = listOf(file("song"))
        val before = dao.variant(song.value)!!
        val restored = scanner.startScan(root, this).await()
        assertEquals(LibraryScanStatus.COMPLETE, restored.status)
        assertEquals(1, restored.progress.updated)
        assertEquals(before, dao.variant(song.value))
        assertEquals(Availability.PLAYABLE, availability(song))
    }

    @Test
    fun theLastFinalizedScanStatusSurvivesReopenSoAccessLossRemainsVisible() = runBlocking {
        val (root, _) = indexSong(library, this)
        assertEquals(LibraryScanStatus.COMPLETE, library.lastScanStatus(root))
        val inListing = CompletableDeferred<Unit>()
        access.afterListing = {
            if (inListing.complete(Unit)) awaitCancellation()
        }
        val cancelled = library.startScan(root, this)
        withTimeout(5_000) { inListing.await() }
        cancelled.cancel()
        assertEquals(LibraryScanStatus.CANCELLED, cancelled.await().status)
        assertEquals(LibraryScanStatus.COMPLETE, library.lastScanStatus(root))
        access.afterListing = {}

        access.grants.remove(tree())
        assertEquals(LibraryScanStatus.ACCESS_LOST, library.startScan(root, this).await().status)
        db.close()
        db = LibraryDatabase.open(context, DB_NAME)
        assertEquals(LibraryScanStatus.ACCESS_LOST, library.lastScanStatus(root))
        assertEquals(root, register(library))
        assertEquals(LibraryScanStatus.ACCESS_LOST, library.lastScanStatus(root))
        assertEquals(LibraryScanStatus.COMPLETE, library.startScan(root, this).await().status)
        assertEquals(LibraryScanStatus.COMPLETE, library.lastScanStatus(root))
    }

    @Test
    fun aListedButUnanalyzableSourceRemainsPresentEvenAfterAPreviousMissingScan() = runBlocking {
        val scanner = library
        val (root, song) = indexSong(scanner, this)
        val oldVariant = dao.variant(song.value)
        access.entries["root"] = emptyList()
        assertEquals(1, scanner.startScan(root, this).await().missing)

        access.entries["root"] = listOf(file("song"), file("unseen-unsupported"))
        access.analysisFailures["song"] = IOException()
        access.analysisFailures["unseen-unsupported"] = SafTimeout()
        val scan = scanner.startScan(root, this)
        val result = scan.await()
        assertEquals(LibraryScanStatus.COMPLETE, result.status)
        assertEquals(2, result.progress.failed)
        assertEquals(setOf(LibraryScanIssue.ANALYSIS_FAILED, LibraryScanIssue.TIMEOUT), result.issues)
        assertEquals(0, result.missing)
        assertEquals(Availability.UNKNOWN, availability(song))
        assertEquals(oldVariant, dao.variant(song.value))
        assertEquals(result.progress, scan.progress.value)
        assertEquals(1, result.progress.directories)
    }

    @Test
    fun limitsKeepUnvisitedSourcesAndRetainIndexedWork() = runBlocking {
        val scanner = library
        val (root, song) = indexSong(scanner, this)
        access.entries["root"] = listOf(file("new"), directory("sub"))
        access.entries["sub"] = listOf(file("third"))
        val cases = listOf(
            ScanLimits(entries = 1) to LibraryScanIssue.ENTRY_LIMIT,
            ScanLimits(depth = 0) to LibraryScanIssue.DEPTH_LIMIT,
            ScanLimits(retainedBytes = 270) to LibraryScanIssue.MEMORY_LIMIT,
        )
        for ((limits, issue) in cases) {
            val result = SafLibrary(db, access, limits).startScan(root, this).await()
            assertEquals(LibraryScanStatus.PARTIAL, result.status)
            assertEquals(setOf(issue), result.issues)
            assertEquals(0, result.missing)
            assertEquals(1, result.progress.indexed + result.progress.updated)
            assertEquals(Availability.PLAYABLE, availability(song))
            assertEquals(LibraryScanStatus.PARTIAL, library.lastScanStatus(root))
        }
    }

    @Test
    fun aTooDeepSubtreeIsSkippedWhileTheRemainingFoldersAreIndexed() = runBlocking {
        val (root, song) = indexSong(library, this)
        access.entries["root"] = listOf(directory("music"), directory("deep"))
        access.entries["music"] = listOf(file("a"))
        access.entries["deep"] = listOf(directory("d2"))
        access.entries["d2"] = listOf(file("too-deep"))

        val result = SafLibrary(db, access, ScanLimits(depth = 1)).startScan(root, this).await()
        assertEquals(LibraryScanStatus.PARTIAL, result.status)
        assertEquals(setOf(LibraryScanIssue.DEPTH_LIMIT), result.issues)
        assertEquals(0, result.missing)
        assertEquals(1, result.progress.indexed)
        assertEquals(3, result.progress.directories)
        assertEquals(Availability.PLAYABLE, availability(variant("root", "a")))
        assertEquals(Availability.PLAYABLE, availability(song))
    }

    @Test
    fun aSharedDirectoryIsIndexedThroughItsShallowPath() = runBlocking {
        val root = register(library)
        access.entries["root"] = listOf(directory("shallow"), directory("deep"))
        access.entries["shallow"] = listOf(directory("shared"))
        access.entries["deep"] = listOf(directory("nested"))
        access.entries["nested"] = listOf(directory("shared"))
        access.entries["shared"] = listOf(file("a"))

        val result = SafLibrary(db, access, ScanLimits(depth = 2)).startScan(root, this).await()
        assertEquals(1, result.progress.indexed)
        assertEquals(Availability.PLAYABLE, availability(variant("root", "a")))
        assertEquals(5, result.progress.directories)
        assertEquals(LibraryScanStatus.COMPLETE, result.status)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun subfolderFailureAndTimeoutRetainUnvisitedSourcesAndPermitRetry() = runBlocking {
        val scanner = library
        val (root, song) = indexSong(scanner, this)
        access.entries["root"] = listOf(directory("sub"))
        access.entries["sub"] = listOf(file("new"))
        for ((failure, issue) in listOf(
            IOException() to LibraryScanIssue.DIRECTORY_READ,
            SafTimeout() to LibraryScanIssue.TIMEOUT,
        )) {
            access.listingFailures["sub"] = failure
            val result = scanner.startScan(root, this).await()
            assertEquals(LibraryScanStatus.PARTIAL, result.status)
            assertEquals(setOf(issue), result.issues)
            assertEquals(0, result.missing)
            assertEquals(1, result.progress.indexed + result.progress.updated)
            assertEquals(Availability.PLAYABLE, availability(song))
        }
        access.listingFailures.clear()
        assertEquals(1, scanner.startScan(root, this).await().missing)
        assertEquals(Availability.MISSING, availability(song))
    }

    @Test
    fun disappearanceOfTheRootDuringAnApparentlySuccessfulListingIsAccessLoss() = runBlocking {
        val scanner = library
        val (root, song) = indexSong(scanner, this)
        access.entries["root"] = listOf(file("new"))
        access.afterListing = { access.readable = false }

        val result = scanner.startScan(root, this).await()
        assertEquals(LibraryScanStatus.ACCESS_LOST, result.status)
        assertEquals(setOf(LibraryScanIssue.ROOT_UNAVAILABLE), result.issues)
        assertEquals(1, result.progress.indexed)
        assertEquals(0, result.missing)
        assertEquals(Availability.PLAYABLE, availability(song))
    }

    @Test
    fun cancellationStopsTheActiveScanAndUnblocksTheNextScanForTheSameRoot() = runBlocking {
        val scanner = library
        val (root, song) = indexSong(scanner, this)
        val inListing = CompletableDeferred<Unit>()
        access.entries["root"] = listOf(file("new"))
        access.afterListing = {
            if (inListing.complete(Unit)) awaitCancellation()
        }

        val active = scanner.startScan(root, this)
        withTimeout(5_000) { inListing.await() }
        assertEquals(1, active.progress.value.indexed)
        val next = library.startScan(root, this)
        assertNotEquals(active.scanId, next.scanId)
        // Wait until another root has completed to verify that the first root remains serialized.
        val otherRoot = register(scanner, "other")
        assertEquals(LibraryScanStatus.COMPLETE, scanner.startScan(otherRoot, this).await().status)
        assertEquals(active.scanId, db.safScanDao().root(root)?.scanId)
        assertEquals(Availability.PLAYABLE, availability(song))

        active.cancel()
        assertEquals(LibraryScanStatus.CANCELLED, active.await().status)
        assertEquals(0, active.await().missing)
        assertEquals(1, next.await().progress.updated)
        assertEquals(1, next.await().missing)
        assertEquals(Availability.MISSING, availability(song))
    }

    @Test
    fun supersededScanCannotWriteAnyLateVisitOrConfirmMissing() = runBlocking {
        val scanner = library
        val (root, song) = indexSong(scanner, this)
        for (entries in listOf(listOf(file("new")), emptyList())) {
            access.entries["root"] = entries
            val listed = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            access.beforeListing = {
                listed.complete(Unit)
                resume.await()
            }
            val old = scanner.startScan(root, this)
            withTimeout(5_000) { listed.await() }
            db.safScanDao().beginScan(root, "newer-scan")
            resume.complete(Unit)

            val result = old.await()
            assertEquals(LibraryScanStatus.PARTIAL, result.status)
            assertEquals(setOf(LibraryScanIssue.SUPERSEDED), result.issues)
            assertEquals(0, result.progress.indexed)
            assertEquals(0, result.missing)
            assertEquals(Availability.PLAYABLE, availability(song))
            assertEquals(LibraryScanStatus.COMPLETE, scanner.lastScanStatus(root))
        }
    }

    private suspend fun indexSong(scanner: SafLibrary, scope: CoroutineScope): Pair<String, VariantId> {
        val root = register(scanner)
        access.entries["root"] = listOf(file("song"))
        val first = scanner.startScan(root, scope).await()
        assertEquals(LibraryScanStatus.COMPLETE, first.status)
        assertEquals(1, first.progress.indexed)
        return root to variant("root")
    }

    private fun variant(rootDocument: String, document: String = "song"): VariantId = db.openHelper.readableDatabase.query(
        "SELECT variant_id FROM track_variants JOIN sources USING (source_id) WHERE local_uri = ?",
        arrayOf(DocumentsContract.buildDocumentUriUsingTree(tree(rootDocument), document).toString()),
    ).use {
        assertTrue(it.moveToFirst())
        VariantId(it.getString(0))
    }

    private suspend fun availability(variantId: VariantId): Availability =
        dao.trackVariant(variantId)!!.source.availability

    private suspend fun register(scanner: SafLibrary, root: String = "root"): String =
        (scanner.registerLocalTree(selection(root)) as LocalTreeRegistration.Registered).rootId

    private fun selection(root: String = "root") = SafLibrary.openTreeIntent().setData(tree(root))

    private fun tree(root: String = "root"): Uri = DocumentsContract.buildTreeDocumentUri("fixture", root)

    private fun file(id: String) = SafEntry(id, directory = false)
    private fun directory(id: String) = SafEntry(id, directory = true)

    private class FixtureAccess : SafAccess {
        val grants = mutableSetOf<Uri>()
        val entries = mutableMapOf<String, List<SafEntry>>()
        val analysisFailures = mutableMapOf<String, Exception>()
        val listingFailures = mutableMapOf<String, Exception>()
        var beforeListing: suspend () -> Unit = {}
        var afterListing: suspend () -> Unit = {}
        var denyGrant = false
        var grantAttempts = 0
        var readable = true

        override fun hasReadGrant(tree: Uri) = tree in grants

        override fun takeReadGrant(tree: Uri) {
            grantAttempts++
            if (denyGrant) throw SecurityException()
            grants += tree
        }

        override fun releaseReadGrant(tree: Uri) {
            grants -= tree
        }

        override suspend fun rootReadable(tree: Uri) = readable

        override fun children(tree: Uri, directoryId: String) = flow {
            beforeListing()
            for (entry in entries[directoryId].orEmpty()) emit(entry)
            afterListing()
            listingFailures[directoryId]?.let { throw it }
        }

        override suspend fun analyze(document: Uri): SafAudio {
            val id = DocumentsContract.getDocumentId(document)
            analysisFailures[id]?.let { throw it }
            return SafAudio(codec = "audio/flac", title = id, sampleRate = 44_100)
        }
    }

    private companion object {
        const val DB_NAME = "saf-scan-test.db"
    }
}
