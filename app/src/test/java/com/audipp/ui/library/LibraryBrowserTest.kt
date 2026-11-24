package com.audipp.ui.library

import com.audipp.data.db.LibraryField
import com.audipp.data.db.LibraryFilter
import com.audipp.data.db.LibraryFolder
import com.audipp.data.db.LibraryGroup
import com.audipp.data.db.LibraryItem
import com.audipp.data.db.LibraryQueryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBrowserTest {
    private fun item(id: String) = LibraryItem(id, id, null, "t-$id", null, null, null, null, null)

    private val album = LibraryGroup("album-1", null, "Album", null, 2, "v1")

    /** Answers each query from [answer]; records the offsets and keywords it was asked for. */
    private class FakeDao(val answer: (keyword: String, offset: Int) -> Flow<List<Pair<Long, LibraryItem?>>>) : LibraryQueryDao() {
        val asked = mutableListOf<Pair<String, Int>>()
        var groupsAsked = 0

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
        ): Flow<List<QueryRow>> {
            asked += keyword to offset
            return flow { answer(keyword, offset).collect { rows -> emit(rows.map { QueryRow(it.first, it.second) }) } }
        }

        override fun observeGroupRows(grouping: String, keyword: String, descending: Boolean, limit: Int, offset: Int) =
            flowOf(listOf(GroupRow(1, LibraryGroup("album-1", null, "Album", null, 2, "v1")))).also { groupsAsked++ }
    }

    private fun withBrowser(dao: FakeDao, block: suspend CoroutineScope.(LibraryBrowser) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            withTimeout(10_000) { block(LibraryBrowser(dao, scope, SharingStarted.Eagerly)) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aGroupScopesTheSongQueryAndAlbumsOpenInDiscOrder() {
        val browse = LibraryBrowse(view = LibraryView.ALBUMS, opened = album, sort = LibraryField.TRACK_NUMBER)
        assertEquals(LibraryFilter.Exact("album-1"), browse.songs(0).albumId)
        assertEquals(LibraryFilter.Unknown, browse.copy(opened = album.copy(albumId = null)).songs(0).albumId)
        assertEquals(LibraryFilter.Any, browse.songs(0).artist)
        val folder = LibraryBrowse(view = LibraryView.FOLDERS, opened = LibraryGroup(null, "root", "disc", null, 1, "v"))
        assertEquals(LibraryFilter.Exact(LibraryFolder("root", "disc")), folder.songs(0).folder)
        val artist = LibraryBrowse(view = LibraryView.ARTISTS, opened = LibraryGroup(null, null, null, null, 1, "v"))
        assertEquals(LibraryFilter.Unknown, artist.songs(0).artist)
        assertTrue(browse.listsSongs)
        assertFalse(LibraryBrowse(view = LibraryView.ALBUMS).listsSongs)
        assertEquals(listOf(0, 0, 0, 25, 275), listOf(0, 10, 49, 50, 300).map(::windowOffset))
    }

    @Test
    fun openingAndClosingAnAlbumSwitchesBetweenGroupsAndItsSongs() = withBrowser(FakeDao { _, _ -> flowOf(listOf(1L to item("v1"))) }) { browser ->
        browser.show(LibraryView.ALBUMS)
        assertEquals(LibraryList.Ready(1, 0, listOf(album)), browser.groups.first { it is LibraryList.Ready })
        browser.open(album)
        assertEquals(LibraryField.TRACK_NUMBER, browser.browse.value.sort)
        assertEquals(listOf(item("v1")), (browser.songs.first { it is LibraryList.Ready } as LibraryList.Ready).rows)
        browser.close()
        assertEquals(LibraryBrowse(view = LibraryView.ALBUMS), browser.browse.value)
    }

    @Test
    fun aNewKeywordShowsLoadingAndNeverTheOlderAnswer() {
        val old = MutableSharedFlow<List<Pair<Long, LibraryItem?>>>()
        val dao = FakeDao { keyword, _ -> if (keyword == "old") old else flowOf(listOf(1L to item("new"))) }
        withBrowser(dao) { browser ->
            val seen = mutableListOf<LibraryList<LibraryItem>>()
            val collecting = launch { browser.songs.collect { seen += it } }
            browser.search("old")
            while (old.subscriptionCount.value == 0) yield()
            old.emit(listOf(1L to item("old")))
            browser.songs.first { it is LibraryList.Ready && it.rows == listOf(item("old")) }
            browser.search("new")
            browser.songs.first { it is LibraryList.Ready && it.rows == listOf(item("new")) }
            // The old query is no longer collected, so a late answer has nowhere to go.
            assertEquals(0, old.subscriptionCount.value)
            old.emit(listOf(9L to item("late")))
            assertEquals(LibraryList.Ready(1, 0, listOf(item("new"))), browser.songs.value)
            collecting.cancel()
            val afterOld = seen.indexOfFirst { it is LibraryList.Ready && it.rows == listOf(item("old")) }
            assertEquals(LibraryList.Loading, seen[afterOld + 1])
            assertEquals(listOf(item("new")), (seen.last() as LibraryList.Ready).rows)
        }
    }

    @Test
    fun scrollingMovesTheWindowWithoutShowingLoadingAgain() {
        val dao = FakeDao { _, offset -> flowOf(listOf(1_000L to item("at-$offset"))) }
        withBrowser(dao) { browser ->
            browser.songs.first { it is LibraryList.Ready }
            val seen = mutableListOf<LibraryList<LibraryItem>>()
            val collecting = launch { browser.songs.collect { seen += it } }
            browser.showFrom(300)
            val moved = browser.songs.first { it is LibraryList.Ready && it.offset == 275 } as LibraryList.Ready
            assertEquals(item("at-275"), moved.rowAt(275))
            assertEquals(null, moved.rowAt(10))
            collecting.cancel()
            assertTrue(seen.none { it == LibraryList.Loading })
            assertEquals(0, dao.groupsAsked)
        }
    }

    @Test
    fun aFailedReadShowsFailedAndRetryReadsAgain() {
        var calls = 0
        val dao = FakeDao { _, _ ->
            flow {
                if (calls++ == 0) throw IllegalStateException("disk")
                emit(listOf(0L to null))
            }
        }
        withBrowser(dao) { browser ->
            browser.songs.first { it == LibraryList.Failed }
            browser.retry()
            assertEquals(LibraryList.Ready(0, 0, emptyList<LibraryItem>()), browser.songs.first { it is LibraryList.Ready })
        }
    }
}
