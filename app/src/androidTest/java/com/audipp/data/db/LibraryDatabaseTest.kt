package com.audipp.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.domain.model.AudioFormat
import com.audipp.domain.model.Availability
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {
    private lateinit var context: Context
    private lateinit var db: LibraryDatabase
    private val dao get() = db.libraryDao()

    @Before
    fun createDatabase() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("library-test.db")
        db = LibraryDatabase.open(context, "library-test.db")
    }

    @After
    fun closeDatabase() {
        db.close()
        context.deleteDatabase("library-test.db")
    }

    @Test
    fun sameNamesAndVariantMetadataSurviveReopen() = runBlocking {
        val source = local("local:five")
        val five = TrackVariant(
            VariantId("five"), TrackId("song"), source.toDomain(), 300_000,
            AudioFormat("FLAC", 44_100, 16, 2),
        )
        val seven = five.copy(
            variantId = VariantId("seven"), durationMs = 420_000,
            format = AudioFormat("DSD", 2_822_400, 1, 2),
        )
        val unknown = five.copy(variantId = VariantId("unknown"), durationMs = null, format = AudioFormat())
        val albums = listOf(AlbumEntity("album-a", "Same"), AlbumEntity("album-b", "Same"))
        val artists = listOf(ArtistEntity("artist-a", "Same"), ArtistEntity("artist-b", "Same"))
        val variants = listOf(
            VariantEntity.from(five, "album-a", 1, 1, "Original"),
            VariantEntity.from(seven, "album-b", 2, 3, "Extended"),
            VariantEntity.from(unknown),
        )
        val credits = listOf(
            TrackArtistEntity("song", "artist-a", "performer", 0),
            TrackArtistEntity("song", "artist-b", "performer", 1),
        )
        dao.save(LibraryBatch(
            sources = listOf(source), tracks = listOf(TrackEntity("song")),
            albums = albums, artists = artists, variants = variants, trackArtists = credits,
        ))
        db.close()
        db = LibraryDatabase.open(context, "library-test.db")
        assertEquals(five, dao.trackVariant(five.variantId))
        assertEquals(seven, dao.trackVariant(seven.variantId))
        assertEquals(unknown, dao.trackVariant(unknown.variantId))
        assertEquals(variants.sortedBy { it.variantId }, dao.variantsForTrack("song"))
        for (album in albums) assertEquals(album, dao.album(album.albumId))
        for (artist in artists) assertEquals(artist, dao.artist(artist.artistId))
        assertEquals(credits, dao.artistsForTrack("song"))
        assertNull(dao.track("song")!!.title)
        assertNull(dao.trackVariant(VariantId("absent")))
    }

    @Test
    fun providerUniquenessExcludesNullAndIncludesMissingSources() = runBlocking {
        dao.save(LibraryBatch(sources = listOf(
            local("local:a"), local("local:b"),
            provider("provider:a", "qobuz", "same"),
            provider("provider:b", "spotify", "same"),
            provider("provider:c", "qobuz", "different"),
        )))
        assertTrue(dao.markSourceMissing("provider:a", 2))
        expectConstraint {
            dao.save(LibraryBatch(sources = listOf(provider("provider:duplicate", "qobuz", "same"))))
        }
        assertNull(dao.source("provider:duplicate"))
        expectConstraint {
            dao.save(LibraryBatch(sources = listOf(
                local("local:rolled-back"), provider("provider:c", "qobuz", "same"),
            )))
        }
        assertNull(dao.source("local:rolled-back"))
        assertEquals("different", dao.source("provider:c")!!.providerItemId)
        assertNotNull(dao.source("local:a"))
        assertNotNull(dao.source("local:b"))
        assertEquals(Availability.MISSING, dao.source("provider:a")!!.availability)
    }

    @Test
    fun invalidSourceTrackOrAlbumForeignKeyRollsBackEntireBatch() = runBlocking {
        val source = local("local:one")
        val track = TrackEntity("track")
        val variant = VariantEntity("variant", "track", source.sourceId)
        for (invalid in listOf(
            variant.copy(sourceId = "absent"), variant.copy(trackId = "absent"),
            variant.copy(albumId = "absent"),
        )) {
            expectConstraint {
                dao.save(LibraryBatch(sources = listOf(source), tracks = listOf(track), variants = listOf(invalid)))
            }
            assertNull(dao.source(source.sourceId))
            assertNull(dao.track(track.trackId))
            assertNull(dao.variant(variant.variantId))
        }
        dao.save(LibraryBatch(sources = listOf(source), tracks = listOf(track), variants = listOf(variant)))
        assertEquals(variant, dao.variant(variant.variantId))
        assertEquals(0, db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { it.count })
    }

    @Test
    fun invalidArtistLinkOrDuplicateCreditPositionRollsBack() = runBlocking {
        val track = TrackEntity("track")
        val artists = listOf(ArtistEntity("a"), ArtistEntity("b"))
        val credit = TrackArtistEntity("track", "a", "performer", 0)
        for (links in listOf(
            listOf(credit.copy(trackId = "absent")), listOf(credit.copy(artistId = "absent")),
            listOf(credit, credit.copy(artistId = "b")),
        )) {
            expectConstraint { dao.save(LibraryBatch(tracks = listOf(track), artists = artists, trackArtists = links)) }
            assertNull(dao.track("track"))
            assertNull(dao.artist("a"))
            assertEquals(emptyList<TrackArtistEntity>(), dao.artistsForTrack("track"))
        }
    }

    @Test
    fun sourceAndVariantUpdatesRetainReferencesAndMissingCanBeRestored() = runBlocking {
        val source = local("local:one")
        val variant = VariantEntity("variant", "track", source.sourceId, albumId = "album")
        val artist = ArtistEntity("artist", "Before")
        val credit = TrackArtistEntity("track", "artist", "performer", 0)
        dao.save(LibraryBatch(
            sources = listOf(source), tracks = listOf(TrackEntity("track", "Before")),
            albums = listOf(AlbumEntity("album", "Before")), artists = listOf(artist),
            variants = listOf(variant), trackArtists = listOf(credit),
        ))
        assertTrue(dao.markSourceMissing(source.sourceId, 2))
        assertFalse(dao.markSourceMissing("absent", 2))
        assertEquals(Availability.MISSING, dao.trackVariant(VariantId("variant"))!!.source.availability)
        assertEquals(variant, dao.variant("variant"))
        assertEquals(listOf(credit), dao.artistsForTrack("track"))
        dao.save(LibraryBatch(
            sources = listOf(source.copy(availability = Availability.PLAYABLE, updatedAt = 3)),
            tracks = listOf(TrackEntity("track", "After")),
            albums = listOf(AlbumEntity("album", "After")), artists = listOf(artist.copy(name = "After")),
            variants = listOf(variant.copy(durationMs = 420_000)),
        ))
        assertEquals(420_000L, dao.trackVariant(VariantId("variant"))!!.durationMs)
        assertEquals(Availability.PLAYABLE, dao.source(source.sourceId)!!.availability)
        assertEquals(listOf(credit), dao.artistsForTrack("track"))
        assertEquals("After", dao.track("track")!!.title)
    }

    @Test
    fun deletingReferencedRowsIsRejectedWithoutCascading() = runBlocking {
        dao.save(LibraryBatch(
            sources = listOf(local("local:one")), tracks = listOf(TrackEntity("track")),
            albums = listOf(AlbumEntity("album")), artists = listOf(ArtistEntity("artist")),
            variants = listOf(VariantEntity("variant", "track", "local:one", albumId = "album")),
            trackArtists = listOf(TrackArtistEntity("track", "artist", "performer", 0)),
        ))
        for (table in listOf("sources", "tracks", "albums", "artists")) {
            expectConstraint { db.openHelper.writableDatabase.execSQL("DELETE FROM $table") }
        }
        assertNotNull(dao.variant("variant"))
        assertEquals(1, dao.artistsForTrack("track").size)
    }

    @Test
    fun cancellationRollsBackAndRetryCanCommit() = runBlocking {
        val written = CompletableDeferred<Unit>()
        val job = launch {
            db.withTransaction {
                dao.save(LibraryBatch(tracks = listOf(TrackEntity("cancelled"))))
                written.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(5_000) { written.await() }
        job.cancelAndJoin()
        assertNull(dao.track("cancelled"))
        dao.save(LibraryBatch(tracks = listOf(TrackEntity("cancelled"))))
        assertNotNull(dao.track("cancelled"))
    }

    @Test
    fun migratesExportedVersionOneWithoutChangingExistingIds() = verifyMigration(1)

    @Test
    fun migratesExportedVersionFourWithoutChangingExistingIds() = verifyMigration(4)

    private fun verifyMigration(version: Int) = runBlocking {
        db.close()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val schema = assets.open("com.audipp.data.db.LibraryDatabase/$version.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }
        context.openOrCreateDatabase("library-test.db", 0, null).use { raw ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val table = entities.getJSONObject(index)
                raw.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                val indices = table.optJSONArray("indices") ?: continue
                for (i in 0 until indices.length()) {
                    raw.execSQL(indices.getJSONObject(i).getString("createSql")
                        .replace("\${TABLE_NAME}", table.getString("tableName")))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) raw.execSQL(setup.getString(index))
            raw.execSQL("INSERT INTO sources (source_id, kind, availability, updated_at) VALUES ('old-source', 'LOCAL', 'MISSING', 1)")
            raw.execSQL("INSERT INTO tracks (track_id, title) VALUES ('old-track', 'Existing')")
            raw.execSQL("INSERT INTO track_variants (variant_id, track_id, source_id) VALUES ('old-variant', 'old-track', 'old-source')")
            if (version == 4) {
                raw.execSQL("INSERT INTO playlists (playlist_id, name, created_at, updated_at, revision) VALUES ('list', 'List', 1, 1, 1)")
                raw.execSQL("INSERT INTO playlist_items (entry_id, playlist_id, ordinal, variant_id) VALUES ('entry', 'list', 0, 'old-variant')")
            }
            raw.version = version
        }
        db = LibraryDatabase.open(context, "library-test.db")
        val variant = dao.trackVariant(VariantId("old-variant"))!!
        assertEquals(SourceId("old-source"), variant.source.sourceId)
        assertEquals(TrackId("old-track"), variant.trackId)
        assertEquals(Availability.MISSING, variant.source.availability)
        assertEquals("Existing", dao.track("old-track")?.title)
        val entries = if (version == 4) listOf(PlaylistEntry("entry", VariantId("old-variant"))) else null
        assertEquals(entries, db.playlistDao().read("list")?.entries)
        assertNull(dao.variant("old-variant")?.localMetadata)
        assertTrue(db.safScanDao().roots().isEmpty())
        assertEquals(7, db.openHelper.writableDatabase.version)
    }

    @Test
    fun unsupportedDatabaseVersionFailsWithoutDestroyingRows() = runBlocking {
        dao.save(LibraryBatch(tracks = listOf(TrackEntity("retained"))))
        db.openHelper.writableDatabase.version = 8
        db.close()
        db = LibraryDatabase.open(context, "library-test.db")
        try {
            dao.track("retained")
            fail("A missing migration must fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Migration"))
        }
        db.close()
        val raw = context.openOrCreateDatabase("library-test.db", 0, null)
        raw.use {
            assertEquals(8, it.version)
            it.rawQuery("SELECT track_id FROM tracks", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("retained", cursor.getString(0))
            }
        }
    }

    @Test
    fun localSourceSummaryUpdatesAndIgnoresProviderState() = runBlocking {
        val updates = Channel<LocalSourceSummary>(Channel.UNLIMITED)
        val observer = launch {
            dao.observeLocalSourceSummary().distinctUntilChanged().collect { updates.send(it) }
        }
        suspend fun next() = withTimeout(5_000) { updates.receive() }
        try {
            assertEquals(LocalSourceSummary(0, 0, 0), next())
            val known = local("local:known").copy(availability = Availability.PLAYABLE)
            val unknown = local("local:unknown")
            val expired = provider("provider:expired", "fixture", "expired").copy(availability = Availability.EXPIRED)
            dao.save(LibraryBatch(sources = listOf(known, unknown, expired)))
            assertEquals(LocalSourceSummary(2, 0, 1), next())
            assertTrue(dao.markSourceMissing(known.sourceId, 2))
            assertEquals(LocalSourceSummary(2, 1, 1), next())
        } finally {
            observer.cancelAndJoin()
            updates.close()
        }
    }

    private fun local(id: String) = SourceEntity.from(
        SourceRef(SourceId(id), SourceKind.LOCAL, localDocumentUri = "content://fixture/$id"),
        updatedAt = 1, grantMode = "PERSISTED_READ",
    )

    private fun provider(id: String, key: String, item: String) = SourceEntity.from(
        SourceRef(SourceId(id), SourceKind.PROVIDER, providerKey = key, providerItemId = item), updatedAt = 1,
    )

    private suspend fun expectConstraint(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected an SQLite constraint violation")
        } catch (_: SQLiteConstraintException) {
            // Constraint failures must reach the caller so a failed batch can be retried.
        }
    }
}
