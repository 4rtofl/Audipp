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
