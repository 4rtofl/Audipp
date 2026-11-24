package com.audipp.ui.library

import com.audipp.data.db.LibraryField
import com.audipp.data.db.LibraryFilter
import com.audipp.data.db.LibraryFolder
import com.audipp.data.db.LibraryGroup
import com.audipp.data.db.LibraryGroupPage
import com.audipp.data.db.LibraryGroupQuery
import com.audipp.data.db.LibraryGrouping
import com.audipp.data.db.LibraryItem
import com.audipp.data.db.LibraryPage
import com.audipp.data.db.LibraryQuery
import com.audipp.data.db.LibraryQueryDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

internal enum class LibraryView(val grouping: LibraryGrouping?) {
    SONGS(null), ALBUMS(LibraryGrouping.ALBUM), ARTISTS(LibraryGrouping.ARTIST),
    FOLDERS(LibraryGrouping.FOLDER), FORMATS(LibraryGrouping.FORMAT),
}

/** What the user chose to look at. The keyword applies to songs, or to group names while a group list is shown. */
internal data class LibraryBrowse(
    val view: LibraryView = LibraryView.SONGS,
    /** The group whose songs are listed; null lists the groups themselves (or every song). */
    val opened: LibraryGroup? = null,
    val keyword: String = "",
    val sort: LibraryField = LibraryField.TITLE,
    val descending: Boolean = false,
) {
    val listsSongs get() = view == LibraryView.SONGS || opened != null

    fun songs(offset: Int) = LibraryQuery(
        keyword = keyword, sort = sort, descending = descending, offset = offset, limit = WINDOW,
        albumId = scoped(LibraryView.ALBUMS) { it.albumId },
        artist = scoped(LibraryView.ARTISTS) { it.name },
        format = scoped(LibraryView.FORMATS) { it.name },
        folder = scoped(LibraryView.FOLDERS) { group -> group.name?.let { LibraryFolder(group.rootId, it) } },
    )

    fun groups(offset: Int) = LibraryGroupQuery(
        requireNotNull(view.grouping), keyword, descending, offset = offset, limit = WINDOW,
    )

    private fun <T> scoped(view: LibraryView, value: (LibraryGroup) -> T?): LibraryFilter<T> {
        val group = opened?.takeIf { this.view == view } ?: return LibraryFilter.Any
        return value(group)?.let { LibraryFilter.Exact(it) } ?: LibraryFilter.Unknown
    }
}

/** A list read in windows of [WINDOW] rows. Ready rows belong to [Ready.offset]; other indices are not loaded yet. */
internal sealed interface LibraryList<out T> {
    data object Loading : LibraryList<Nothing>
    data object Failed : LibraryList<Nothing>
    data class Ready<T>(val total: Int, val offset: Int, val rows: List<T>) : LibraryList<T> {
        fun rowAt(index: Int): T? = rows.getOrNull(index - offset)
    }
}

internal const val WINDOW = 100

/** Starts the window a quarter before the first visible row, so short scrolls either way stay loaded. */
internal fun windowOffset(firstVisible: Int) = ((firstVisible - WINDOW / 4) / (WINDOW / 4) * (WINDOW / 4)).coerceAtLeast(0)

/**
 * Library lists for the UI. Room runs the queries off the main thread. A change of view, group, keyword or sort
 * cancels the previous query and shows Loading, so rows of an older choice never appear under a newer one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LibraryBrowser(private val dao: LibraryQueryDao, scope: CoroutineScope, started: SharingStarted) {
    private val choice = MutableStateFlow(LibraryBrowse())
    private val offset = MutableStateFlow(0)
    private val attempt = MutableStateFlow(0)
    val browse: StateFlow<LibraryBrowse> = choice

    val songs: StateFlow<LibraryList<LibraryItem>> =
        pagesOf { browse -> if (browse.listsSongs) dao.observePages(offset.map(browse::songs)).map(LibraryPage::ready) else null }
            .stateIn(scope, started, LibraryList.Loading)

    val groups: StateFlow<LibraryList<LibraryGroup>> =
        pagesOf { browse -> if (browse.listsSongs) null else dao.observeGroups(offset.map(browse::groups)).map(LibraryGroupPage::ready) }
            .stateIn(scope, started, LibraryList.Loading)

    private fun <T> pagesOf(pages: (LibraryBrowse) -> Flow<LibraryList<T>>?): Flow<LibraryList<T>> =
        combine(choice, attempt) { browse, _ -> browse }.flatMapLatest { browse ->
            val source = pages(browse) ?: return@flatMapLatest flowOf(LibraryList.Loading)
            flow {
                emit(LibraryList.Loading)
                emitAll(source)
            }.catch {
                if (it !is Exception || it is CancellationException) throw it
                emit(LibraryList.Failed)
            }
        }

    fun show(view: LibraryView) = choose { LibraryBrowse(view = view, sort = it.sort, descending = it.descending) }

    /** Albums open in disc and track order; other groups keep the chosen sort. */
    fun open(group: LibraryGroup) = choose {
        it.copy(opened = group, keyword = "", sort = if (it.view == LibraryView.ALBUMS) LibraryField.TRACK_NUMBER else it.sort)
    }

    fun close() = choose {
        it.copy(opened = null, keyword = "", sort = if (it.sort == LibraryField.TRACK_NUMBER) LibraryField.TITLE else it.sort)
    }

    fun search(keyword: String) = choose { it.copy(keyword = keyword) }

    fun sort(field: LibraryField, descending: Boolean) = choose { it.copy(sort = field, descending = descending) }

    fun showFrom(firstVisible: Int) {
        offset.value = windowOffset(firstVisible)
    }

    fun retry() = attempt.update { it + 1 }

    private fun choose(change: (LibraryBrowse) -> LibraryBrowse) {
        val next = change(choice.value)
        if (next == choice.value) return
        offset.value = 0
        choice.value = next
    }
}

private fun LibraryPage.ready() = LibraryList.Ready(total.toCount(), query.offset, items)

private fun LibraryGroupPage.ready() = LibraryList.Ready(total.toCount(), query.offset, groups)

private fun Long.toCount() = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
