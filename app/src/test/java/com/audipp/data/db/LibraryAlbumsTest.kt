package com.audipp.data.db

import android.net.Uri
import android.provider.DocumentsContract
import com.audipp.data.local.LibraryScanStatus
import com.audipp.data.local.LocalTreeRegistration
import com.audipp.data.local.SafAccess
import com.audipp.data.local.SafAudio
import com.audipp.data.local.SafEntry
import com.audipp.data.local.SafLibrary
import com.audipp.data.local.ScanLimits
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LibraryAlbumsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: LibraryDatabase
    private val access = FixtureAccess()
    private val albums get() = LibraryAlbums(db)

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
    fun aTwoDiscReleaseInTwoFoldersBecomesOneAlbumInDiscOrder() = runBlocking {
        val root = register()
        twoDiscs(release = RELEASE)
        scan(root)
        val album = albumOf("d1t1")!!
        assertEquals(listOf("d1t1", "d1t2", "d2t1", "d2t2").map(::variant), tracksOf(album))
        assertEquals(listOf(1, 1, 2, 2), albums.observeTracks(album).first().map { it.discNo })
        assertEquals(RELEASE, db.libraryDao().album(album)?.releaseId)
        assertTrue(albums.candidates().isEmpty())
    }

    @Test
    fun sameNamedReleasesAndConflictingTagsAreNotJoinedUntilTheTagsAgree() = runBlocking {
        val root = register()
        access.entries["root"] = listOf(directory("A"), directory("B"))
        access.entries["A"] = listOf(file("a1"), file("a2"))
        access.entries["B"] = listOf(file("b1"))
        access.audio["a1"] = song("Same", disc = 1, track = 1, release = RELEASE)
        access.audio["a2"] = song("Same, but retitled", disc = 1, track = 2, release = RELEASE)
        access.audio["b1"] = song("Same", disc = 1, track = 1, release = OTHER_RELEASE)
        scan(root)
        assertNull(albumOf("a1"))
        assertNull(albumOf("a2"))
        assertEquals(AlbumCandidateReason.CONFLICTING_TAGS, albums.candidates().single().reason)

        access.audio["a2"] = song("Same", disc = 1, track = 2, release = RELEASE)
        scan(root)
        assertEquals(albumOf("a1"), albumOf("a2"))
        assertNotEquals(albumOf("a1"), albumOf("b1"))
        assertTrue(albums.candidates().isEmpty())
    }

    @Test
    fun aMergeAndASplitSurviveRescansAndCanBeUndoneInAnyOrder() = runBlocking {
        val root = register()
        twoDiscs(release = null)
        scan(root)
        val (disc1, disc2) = albumOf("d1t1")!! to albumOf("d2t1")!!
        assertNotEquals(disc1, disc2)
        val candidate = albums.candidates().single()
        assertEquals(AlbumCandidateReason.SAME_TITLE, candidate.reason)
        assertEquals(setOf(disc1, disc2), candidate.albumIds.toSet())

        val merge = albums.merge(candidate.variantIds, now = 10) as AlbumEditResult.Applied
        assertEquals(listOf("d1t1", "d1t2", "d2t1", "d2t2").map(::variant), tracksOf(merge.albumId))
        assertEquals("Two discs", db.libraryDao().album(merge.albumId)?.title)
        scan(root)
        assertEquals(4, tracksOf(merge.albumId).size)
        assertTrue(albums.candidates().isEmpty())

        val split = albums.split(listOf(variant("d2t2")), now = 20) as AlbumEditResult.Applied
        scan(root)
        assertEquals(listOf(variant("d2t2")), tracksOf(split.albumId))
        assertEquals(3, tracksOf(merge.albumId).size)
        assertEquals(listOf(split.editId, merge.editId), albums.observeEdits().first().map { it.editId })

        // Undoing the older merge leaves the newer split in force; the rest return to automatic albums.
        assertTrue(albums.undo(merge.editId))
        assertEquals(disc1, albumOf("d1t1"))
        assertEquals(listOf(variant("d2t1")), tracksOf(disc2))
        assertEquals(split.albumId, albumOf("d2t2"))
        assertNull(db.libraryDao().album(merge.albumId))

        assertTrue(albums.undo(split.editId))
        assertFalse(albums.undo(split.editId))
        assertEquals(listOf("d2t1", "d2t2").map(::variant), tracksOf(disc2))
        assertNull(db.libraryDao().album(split.albumId))
        assertEquals(AlbumCandidateReason.SAME_TITLE, albums.candidates().single().reason)
    }

    @Test
    fun aMergedVariantStaysInTheUserAlbumWhenItsTagsChangeOnRescan() = runBlocking {
        val root = register()
        twoDiscs(release = null)
        scan(root)
        val merge = albums.merge(albums.candidates().single().variantIds) as AlbumEditResult.Applied
        access.audio["d2t1"] = song("Retitled", disc = 2, track = 1, release = OTHER_RELEASE)
        scan(root)
        assertEquals("Retitled", db.libraryDao().variant(variant("d2t1"))?.localMetadata?.album)
        assertEquals(merge.albumId, albumOf("d2t1"))
        assertEquals(4, tracksOf(merge.albumId).size)
        assertTrue(albums.candidates().isEmpty())
    }

    @Test
    fun undoingTheNewestEditRestoresThePreviousEdit() = runBlocking {
        val root = register()
        twoDiscs(release = null)
        scan(root)
        val merge = albums.merge(albums.candidates().single().variantIds) as AlbumEditResult.Applied
        val split = albums.split(listOf(variant("d2t1"), variant("d2t2"))) as AlbumEditResult.Applied
        assertEquals(split.albumId, albumOf("d2t1"))
        assertTrue(albums.undo(split.editId))
        assertEquals(merge.albumId, albumOf("d2t1"))
        assertEquals(4, tracksOf(merge.albumId).size)
    }

    @Test
    fun ambiguousDiscsAreOfferedAndAMergeConfirmsThemAcrossRescans() = runBlocking {
        val root = register()
        access.entries["root"] = listOf(file("t1"), file("t2"))
        access.audio["t1"] = song("Two discs", disc = 1, track = 1)
        access.audio["t2"] = song("Two discs", disc = null, track = 1)
        scan(root)
        val candidate = albums.candidates().single()
        assertEquals(AlbumCandidateReason.DISC_CONFLICT, candidate.reason)
        assertNull(albumOf("t1"))
        val merge = albums.merge(candidate.variantIds) as AlbumEditResult.Applied
        scan(root)
        // A missing disc number is not guessed; it sorts after the known discs.
        assertEquals(listOf("t1", "t2").map(::variant), tracksOf(merge.albumId))
        assertEquals(listOf(1, null), albums.observeTracks(merge.albumId).first().map { it.discNo })
    }

    @Test
    fun invalidEditsAreRejectedWithoutRecordingAnything() = runBlocking {
        val root = register()
        twoDiscs(release = RELEASE)
        scan(root)
        val album = albumOf("d1t1")!!
        val all = tracksOf(album)
        assertEquals(AlbumEditResult.Rejected.UNKNOWN_VARIANT, albums.merge(emptyList()))
        assertEquals(AlbumEditResult.Rejected.UNKNOWN_VARIANT, albums.merge(listOf(variant("d1t1"), "absent")))
        assertEquals(AlbumEditResult.Rejected.ALREADY_TOGETHER, albums.merge(all))
        assertEquals(AlbumEditResult.Rejected.NOT_PART_OF_ONE_ALBUM, albums.split(all))
        // A Variant saved without a local scan has no folder to group by and cannot be edited here.
        val source = db.libraryDao().variant(variant("d1t1"))!!.sourceId
        db.libraryDao().save(LibraryBatch(
            tracks = listOf(TrackEntity("manual")),
            variants = listOf(VariantEntity("manual", "manual", source)),
        ))
        assertEquals(AlbumEditResult.Rejected.UNKNOWN_VARIANT, albums.merge(listOf(variant("d1t1"), "manual")))
        assertTrue(albums.observeEdits().first().isEmpty())
        assertEquals(album, albumOf("d1t1"))
    }

    @Test
    fun migratesV7KeepingLegacyAlbumsUntilTheirFilesAreRescanned() = runBlocking {
        db.close()
        context.deleteDatabase(DB_NAME)
        context.createLibraryDatabaseFromSchema(DB_NAME, version = 7) { old ->
            old.execSQL("INSERT INTO sources (source_id, kind, local_uri, availability, updated_at) VALUES ('s', 'LOCAL', 'content://song', 'PLAYABLE', 1)")
            old.execSQL("INSERT INTO tracks (track_id, title) VALUES ('t', 'Song')")
            old.execSQL("INSERT INTO albums (album_id, title) VALUES ('saf-album:x', 'Album')")
            old.execSQL("INSERT INTO track_variants (variant_id, track_id, source_id, album_id, disc_no, track_no, local_album) VALUES ('v', 't', 's', 'saf-album:x', 2, 3, 'Album')")
        }
        db = LibraryDatabase.open(context, DB_NAME)
        assertEquals(8, db.openHelper.writableDatabase.version)
        val variant = db.libraryDao().variant("v")!!
        assertEquals("saf-album:x" to (2 to 3), variant.albumId to (variant.discNo to variant.trackNo))
        assertNull(variant.localMetadata?.folder)
        albums.organize()
        assertEquals("saf-album:x", db.libraryDao().variant("v")?.albumId)
        assertEquals("Album", db.libraryDao().album("saf-album:x")?.title)
        assertTrue(albums.observeEdits().first().isEmpty())
    }

    private suspend fun albumOf(document: String): String? = db.libraryDao().variant(variant(document))?.albumId

    private suspend fun tracksOf(albumId: String?): List<String> =
        albums.observeTracks(checkNotNull(albumId)).first().map { it.variantId }

    private fun twoDiscs(release: String?) {
        access.entries["root"] = listOf(directory("CD2"), directory("CD1"))
        access.entries["CD1"] = listOf(file("d1t2"), file("d1t1"))
        access.entries["CD2"] = listOf(file("d2t1"), file("d2t2"))
        for (disc in 1..2) for (track in 1..2) {
            access.audio["d${disc}t$track"] = song("Two discs", disc, track, release)
        }
    }

    private fun song(album: String, disc: Int?, track: Int, release: String? = null) = SafAudio(
        codec = "audio/flac", title = "Song $disc-$track", album = album, albumArtist = "Artist",
        track = track, disc = disc, musicBrainzReleaseId = release,
    )

    private suspend fun scan(root: String) = coroutineScope {
        assertEquals(LibraryScanStatus.COMPLETE, scanner.startScan(root, this).await().status)
    }

    private val scanner get() = SafLibrary(db, access, ScanLimits())

    private suspend fun register(): String {
        val selection = SafLibrary.openTreeIntent().setData(tree())
        return (scanner.registerLocalTree(selection) as LocalTreeRegistration.Registered).rootId
    }

    private fun variant(document: String): String = db.openHelper.readableDatabase.query(
        "SELECT variant_id FROM track_variants JOIN sources USING (source_id) WHERE local_uri = ?",
        arrayOf(DocumentsContract.buildDocumentUriUsingTree(tree(), document).toString()),
    ).use { if (it.moveToFirst()) it.getString(0) else document }

    private fun tree(): Uri = DocumentsContract.buildTreeDocumentUri("fixture", "root")
    private fun file(id: String) = SafEntry(id, directory = false)
    private fun directory(id: String) = SafEntry(id, directory = true)

    private class FixtureAccess : SafAccess {
        val grants = mutableSetOf<Uri>()
        val entries = mutableMapOf<String, List<SafEntry>>()
        val audio = mutableMapOf<String, SafAudio>()

        override fun hasReadGrant(tree: Uri) = tree in grants
        override fun takeReadGrant(tree: Uri) { grants += tree }
        override fun releaseReadGrant(tree: Uri) { grants -= tree }
        override suspend fun rootReadable(tree: Uri) = true
        override fun children(tree: Uri, directoryId: String) = flow { entries[directoryId].orEmpty().forEach { emit(it) } }
        override suspend fun analyze(document: Uri, displayName: String?): SafAudio =
            audio.getValue(DocumentsContract.getDocumentId(document))
    }

    private companion object {
        const val DB_NAME = "library-albums-test.db"
        const val RELEASE = "0e7a9c1b-2f4d-4c3b-9a8e-1d2c3b4a5f60"
        const val OTHER_RELEASE = "11111111-2f4d-4c3b-9a8e-1d2c3b4a5f60"
    }
}
