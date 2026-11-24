package com.audipp.data.db

import androidx.room.Room
import com.audipp.data.db.LibraryFilter.Exact
import com.audipp.data.db.LibraryFilter.Unknown
import com.audipp.domain.model.SourceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
class LibraryQueryDaoTest {
    private lateinit var db: LibraryDatabase
    private val longTitle = "長".repeat(60_000) + "%_"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun keywordsAndExactFiltersUseStoredValues() = runBlocking {
        seed()
        for ((keyword, expected) in listOf(
            "aLPHA" to "a", "同名" to "a", "歌手" to "b", "Jazz" to "a", "199" to "b",
            "waVE" to "b", "%_" to "c", longTitle to "c", "credited" to "a", "' OR 1=1 --" to null,
        )) {
            assertPage(LibraryQuery(keyword = keyword), listOfNotNull(expected))
        }
        assertPage(LibraryQuery(
            title = Exact("alpha"), album = Exact("同名"), artist = Exact("Zed"),
            genre = Exact("jazz"), year = Exact(2000), format = Exact("FLAC"),
        ), listOf("a"))
        assertPage(LibraryQuery(artist = Exact("CREDITED")), listOf("a"))
        assertPage(LibraryQuery(genre = Exact("jazz"), year = Exact(1990)), emptyList())
        assertPage(LibraryQuery(keyword = "   "), listOf("a", "b", "c", "d"))
    }

    @Test
    fun sortsAllFieldsWithUnknownLastAndYearsNumerically() = runBlocking {
        seed()
        val orders = mapOf(
            LibraryField.TITLE to listOf("a", "b", "c", "d"),
            LibraryField.ALBUM to listOf("b", "a", "c", "d"),
            LibraryField.ARTIST to listOf("a", "b", "c", "d"),
            LibraryField.GENRE to listOf("a", "b", "c", "d"),
            LibraryField.YEAR to listOf("c", "b", "a", "d"),
            LibraryField.FORMAT to listOf("a", "b", "c", "d"),
        )
        for ((field, expected) in orders) {
            assertPage(LibraryQuery(sort = field), expected)
        }
        assertPage(LibraryQuery(sort = LibraryField.YEAR, descending = true), listOf("a", "b", "c", "d"))
        assertPage(LibraryQuery(
            title = Unknown, album = Unknown, artist = Unknown,
            genre = Unknown, year = Unknown, format = Unknown,
        ), listOf("d"))
        assertPage(LibraryQuery(album = Unknown), listOf("c", "d"))
        assertPage(LibraryQuery(format = Unknown), listOf("c", "d"))
        assertPage(LibraryQuery(year = Unknown), listOf("d"))
        assertPage(LibraryQuery(keyword = "親タグ"), emptyList())
    }

    @Test
    fun keepsCountsAndStableTiesAcrossPageBoundaries() = runBlocking {
        seed()
        assertPage(LibraryQuery(limit = 1), listOf("a"), total = 4)
        assertPage(LibraryQuery(limit = 2, offset = 1), listOf("b", "c"), total = 4)
        assertPage(LibraryQuery(limit = 2, offset = 3), listOf("d"), total = 4)
        assertPage(LibraryQuery(limit = 2, offset = 4), emptyList(), total = 4)
        assertThrows(IllegalArgumentException::class.java) { LibraryQuery(offset = -1) }
        assertThrows(IllegalArgumentException::class.java) { LibraryQuery(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { LibraryQuery(limit = 101) }
        Unit
    }

    @Test
    fun activePageRefreshesAfterDatabaseChanges() = runBlocking {
        withTimeout(10_000) {
            val emptyPage = CompletableDeferred<Unit>()
            val results = async {
                val totals = mutableListOf<Long>()
                db.libraryQueryDao().observePages(flowOf(LibraryQuery())).take(2).collect {
                    totals += it.total
                    emptyPage.complete(Unit)
                }
                totals
            }
            emptyPage.await()
            seed()
            assertEquals(listOf(0L, 4L), results.await())
        }
    }

    @Test
    fun groupsKeepSameNamedAlbumsAndFoldersApartAndCollectUnknownValues() = runBlocking {
        seedBrowsing()
        val albums = groups(LibraryGrouping.ALBUM)
        assertEquals(listOf("album-1", "album-2", null), albums.groups.map { it.albumId })
        assertEquals(listOf("同名", "同名", null), albums.groups.map { it.name })
        assertEquals(listOf("Band", null, null), albums.groups.map { it.albumArtist })
        assertEquals(listOf(2L, 1L, 2L), albums.groups.map { it.tracks })
        assertEquals("v1", albums.groups.first().firstVariantId)

        assertEquals(listOf("Band" to 2L, "Solo" to 1L, null to 2L),
            groups(LibraryGrouping.ARTIST).groups.map { it.name to it.tracks })
        val folders = groups(LibraryGrouping.FOLDER).groups
        assertEquals(listOf("root-a" to "disc", "root-b" to "disc", null to null), folders.map { it.rootId to it.name })
        assertEquals(listOf(2L, 2L, 1L), folders.map { it.tracks })
        assertEquals(listOf("FLAC", "MP3", null), groups(LibraryGrouping.FORMAT).groups.map { it.name })

        assertEquals(listOf("Solo", "Band", null),
            groups(LibraryGrouping.ARTIST, descending = true).groups.map { it.name })
        assertEquals(listOf("Solo"), groups(LibraryGrouping.ARTIST, keyword = "sol").groups.map { it.name })
        val window = groups(LibraryGrouping.ALBUM, offset = 2, limit = 1)
        assertEquals(3L, window.total)
        assertEquals(listOf(null), window.groups.map { it.albumId })
        assertEquals(0L, groups(LibraryGrouping.ALBUM, keyword = "none").total)
    }

    @Test
    fun songsFilterByAlbumIdAndFolderAndSortByDiscAndTrack() = runBlocking {
        seedBrowsing()
        assertPage(LibraryQuery(albumId = Exact("album-1"), sort = LibraryField.TRACK_NUMBER), listOf("v2", "v1"))
        assertPage(LibraryQuery(albumId = Unknown), listOf("v4", "v5"))
        assertPage(LibraryQuery(folder = Exact(LibraryFolder("root-a", "disc"))), listOf("v1", "v2"))
        assertPage(LibraryQuery(folder = Exact(LibraryFolder("root-b", "disc"))), listOf("v3", "v4"))
        assertPage(LibraryQuery(folder = Unknown), listOf("v5"))
        assertPage(LibraryQuery(sort = LibraryField.TRACK_NUMBER), listOf("v2", "v1", "v4", "v3", "v5"))
        assertPage(LibraryQuery(artist = Exact("BAND")), listOf("v1", "v2"))
    }

    private suspend fun groups(
        grouping: LibraryGrouping, keyword: String = "", descending: Boolean = false, offset: Int = 0, limit: Int = 50,
    ): LibraryGroupPage {
        val query = LibraryGroupQuery(grouping, keyword, descending, offset, limit)
        return db.libraryQueryDao().observeGroups(flowOf(query)).first().also { assertEquals(query, it.query) }
    }

    /** Two scan roots each holding a folder named "disc"; the albums share a title but not an ID. */
    private suspend fun seedBrowsing() {
        fun variant(id: String, album: String?, disc: Int?, track: Int?, artist: String?, folder: String?, codec: String?) =
            VariantEntity(id, id, "s-$id", albumId = album, discNo = disc, trackNo = track, codec = codec,
                localMetadata = VariantLocalMetadata(title = "t-$id", artist = artist, folder = folder))
        val ids = listOf("v1", "v2", "v3", "v4", "v5")
        db.libraryDao().save(LibraryBatch(
            sources = ids.map { SourceEntity("s-$it", SourceKind.LOCAL, localUri = "content://test/$it", updatedAt = 0) },
            tracks = ids.map { TrackEntity(it) },
            albums = listOf(AlbumEntity("album-1", "同名", albumArtist = "Band"), AlbumEntity("album-2", "同名")),
            variants = listOf(
                variant("v1", "album-1", 1, 2, "Band", "disc", "FLAC"),
                variant("v2", "album-1", 1, 1, "band", "disc", "FLAC"),
                variant("v3", "album-2", 2, null, "Solo", "disc", "MP3"),
                variant("v4", null, 2, 1, null, "disc", null),
                variant("v5", null, null, null, null, null, null),
            ),
        ))
        val scans = db.safScanDao()
        scans.register(LocalScanRoot("root-a", "content://tree/a"))
        scans.register(LocalScanRoot("root-b", "content://tree/b"))
        listOf("v1" to "root-a", "v2" to "root-a", "v3" to "root-b", "v4" to "root-b", "v5" to "root-b").forEach { (id, root) ->
            scans.recordVisit(LocalScanSource("s-$id", root, "scan"))
        }
    }

    private suspend fun assertPage(query: LibraryQuery, ids: List<String>, total: Long = ids.size.toLong()) {
        val page = db.libraryQueryDao().observePages(flowOf(query)).first()
        assertEquals(query, page.query)
        assertEquals(ids, page.items.map { it.variantId })
        assertEquals(total, page.total)
    }

    private suspend fun seed() {
        db.libraryDao().save(LibraryBatch(
            sources = listOf(
                SourceEntity("local", SourceKind.LOCAL, localUri = "content://test/local", updatedAt = 0),
                SourceEntity("provider", SourceKind.PROVIDER, "provider", "item", updatedAt = 0),
            ),
            tracks = listOf(
                TrackEntity("a", title = "alpha", artistDisplay = "Zed", year = 2000, genre = "Jazz"),
                TrackEntity("b", year = 1990, genre = "Rock"),
                TrackEntity("c", title = longTitle, year = 99),
                TrackEntity("d", title = "親タグ", artistDisplay = "親タグ", genre = "  "),
            ),
            albums = listOf(AlbumEntity("a", "同名"), AlbumEntity("b", "同名")),
            artists = listOf(ArtistEntity("a", "Credited"), ArtistEntity("c", "輪"), ArtistEntity("d", "親タグ")),
            trackArtists = listOf(
                TrackArtistEntity("a", "a", "artist", 0),
                TrackArtistEntity("c", "c", "artist", 0),
                TrackArtistEntity("d", "d", "artist", 0),
            ),
            variants = listOf(
                VariantEntity("a", "a", "local", albumId = "a", codec = "FLAC"),
                VariantEntity("b", "b", "local", albumId = "b", codec = "PCM",
                    localMetadata = VariantLocalMetadata("WAVE", "夏", "歌手", "Beta")),
                VariantEntity("c", "c", "local"),
                VariantEntity("d", "d", "local", albumId = "b",
                    localMetadata = VariantLocalMetadata(artworkRef = "embedded:8:16:image/png")),
                VariantEntity("provider-variant", "a", "provider", albumId = "a", codec = "FLAC"),
            ),
        ))
    }
}

class LibraryQueryFlowTest {
    @Test
    fun replacementCancelsTheOldQueryAndDropsItsPendingPage() = runBlocking {
        withTimeout(10_000) {
            val oldPending = CompletableDeferred<Unit>()
            val oldCancelled = CompletableDeferred<Unit>()
            val dao = object : LibraryQueryDao() {
                override fun observeRows(
                    keyword: String,
                    title: String?, unknownTitle: Boolean,
                    album: String?, unknownAlbum: Boolean,
                    artist: String?, unknownArtist: Boolean,
                    genre: String?, unknownGenre: Boolean,
                    year: Int?, unknownYear: Boolean,
                    format: String?, unknownFormat: Boolean,
                    albumId: String?, unknownAlbumId: Boolean,
                    folderRoot: String?, folderPath: String?, unknownFolder: Boolean,
                    sort: String, descending: Boolean, limit: Int, offset: Int,
                ) = flow {
                    if (keyword == "old") {
                        try {
                            emit(listOf(QueryRow(1, null)))
                            oldPending.complete(Unit)
                            emit(listOf(QueryRow(99, null)))
                            awaitCancellation()
                        } finally {
                            oldCancelled.complete(Unit)
                        }
                    } else {
                        emit(listOf(QueryRow(2, null)))
                    }
                }

                override fun observeGroupRows(grouping: String, keyword: String, descending: Boolean, limit: Int, offset: Int) =
                    flowOf(emptyList<GroupRow>())
            }
            val queries = MutableStateFlow(LibraryQuery(keyword = "old"))
            val received = mutableListOf<Pair<String, Long>>()
            dao.observePages(queries).take(2).collect {
                received += it.query.keyword to it.total
                if (received.size == 1) {
                    oldPending.await()
                    queries.value = LibraryQuery(keyword = "new")
                    oldCancelled.await()
                }
            }
            assertEquals(listOf("old" to 1L, "new" to 2L), received)
        }
    }
}
