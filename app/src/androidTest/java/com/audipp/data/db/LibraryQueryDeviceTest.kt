package com.audipp.data.db

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.domain.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryQueryDeviceTest {
    private lateinit var db: LibraryDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun findsJapaneseEnglishAndLongTagsAndKeepsTheMissingTagGroup() = runBlocking {
        val longTitle = "長".repeat(60_000) + "%_"
        db.libraryDao().save(LibraryBatch(
            sources = listOf(localSource()),
            tracks = listOf(
                TrackEntity("a", "alpha", genre = "Jazz", year = 2000),
                TrackEntity("b", "夏", genre = "Rock", year = 1990),
                TrackEntity("c", longTitle),
                TrackEntity("d"),
            ),
            albums = listOf(AlbumEntity("one", "同名"), AlbumEntity("two", "同名")),
            variants = listOf(
                VariantEntity("a", "a", "local", albumId = "one", codec = "FLAC"),
                VariantEntity("b", "b", "local", albumId = "two", codec = "MP3"),
                VariantEntity("c", "c", "local"),
                VariantEntity("d", "d", "local"),
            ),
        ))
        withContext(Dispatchers.Main) {
            assertEquals(listOf("a", "b", "c", "d"), page(LibraryQuery()).items.map { it.variantId })
            assertEquals(listOf("b", "a", "c", "d"),
                page(LibraryQuery(sort = LibraryField.YEAR)).items.map { it.variantId })
            assertEquals(listOf("one", "two"),
                page(LibraryQuery(keyword = "同名")).items.map { it.albumId })
            assertEquals("a", page(LibraryQuery(keyword = "ALPHA")).items.single().variantId)
            assertEquals("c", page(LibraryQuery(keyword = longTitle)).items.single().variantId)
            assertEquals("d", page(LibraryQuery(title = LibraryFilter.Unknown)).items.single().variantId)
            assertEquals(4L, page(LibraryQuery(offset = 4)).total)
            assertEquals(emptyList<LibraryItem>(), page(LibraryQuery(offset = 4)).items)
        }
    }

    @Test
    fun readsOneThousandVariantsInOrderedPagesFromMain(): Unit = runBlocking {
        val ids = (1000 until 2000).map(Int::toString)
        db.libraryDao().save(LibraryBatch(
            sources = listOf(localSource()),
            tracks = ids.map { TrackEntity(it, title = "Same title") },
            variants = ids.map { VariantEntity(it, it, "local") },
        ))
        val actual = mutableListOf<String>()
        val timesMs = mutableListOf<Double>()
        withContext(Dispatchers.Main) {
            for (offset in ids.indices step 50) {
                val start = SystemClock.elapsedRealtimeNanos()
                val page = page(LibraryQuery(offset = offset))
                timesMs += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                assertEquals(1000L, page.total)
                assertEquals(50, page.items.size)
                actual += page.items.map { it.variantId }
            }
        }
        assertEquals(ids, actual)
        Log.i("AudippLibraryQueryTest", "API ${Build.VERSION.SDK_INT}, 20 pages of 50, " +
            "totalMs=${timesMs.sum()}, firstMs=${timesMs.first()}, maxMs=${timesMs.max()}")
    }

    private suspend fun page(query: LibraryQuery) = db.libraryQueryDao().observePages(flowOf(query)).first()

    private fun localSource() =
        SourceEntity("local", SourceKind.LOCAL, localUri = "content://test/local", updatedAt = 0)
}
