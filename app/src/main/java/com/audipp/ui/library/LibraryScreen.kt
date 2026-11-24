package com.audipp.ui.library

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audipp.R
import com.audipp.data.db.LibraryField
import com.audipp.data.db.LibraryGroup
import com.audipp.data.db.LibraryItem
import com.audipp.data.local.Artwork
import com.audipp.data.local.LibraryScanResult
import com.audipp.data.local.LibraryScanStatus
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.VariantId
import com.audipp.ui.navigation.FolderRegistration
import com.audipp.ui.navigation.HomeSources
import com.audipp.ui.nowplaying.ArtworkBox
import com.audipp.ui.nowplaying.rememberArtwork
import com.audipp.ui.theme.AudippSpacing

/** What the library must say about its sources before the list; null when the list speaks for itself. */
internal sealed interface LibraryNotice {
    data object SourcesFailed : LibraryNotice
    data object Empty : LibraryNotice
    data class Scanning(val visited: Int) : LibraryNotice
    data object Denied : LibraryNotice
    data object AccessLost : LibraryNotice
    data class Cancelled(val rootId: String) : LibraryNotice
    data class Incomplete(val rootId: String) : LibraryNotice
}

internal fun libraryNotice(
    sources: HomeSources,
    registration: FolderRegistration,
    lastScan: LibraryScanResult?,
): LibraryNotice? {
    val ready = sources as? HomeSources.Ready
    return when {
        registration is FolderRegistration.Scanning -> LibraryNotice.Scanning(registration.visited)
        registration == FolderRegistration.Registering -> LibraryNotice.Scanning(0)
        registration == FolderRegistration.Denied -> LibraryNotice.Denied
        registration == FolderRegistration.AccessLost || ready?.accessLost == true -> LibraryNotice.AccessLost
        registration is FolderRegistration.ScanFailed ->
            if (lastScan?.status == LibraryScanStatus.CANCELLED) LibraryNotice.Cancelled(registration.rootId)
            else LibraryNotice.Incomplete(registration.rootId)
        sources == HomeSources.Failed -> LibraryNotice.SourcesFailed
        ready?.retryRootId != null -> LibraryNotice.Incomplete(ready.retryRootId)
        ready != null && ready.summary.registered == 0L -> LibraryNotice.Empty
        else -> null
    }
}

internal data class LibraryUi(
    val browse: LibraryBrowse = LibraryBrowse(),
    val songs: LibraryList<LibraryItem> = LibraryList.Loading,
    val groups: LibraryList<LibraryGroup> = LibraryList.Loading,
    val selection: TrackSelection = TrackSelection.None,
    val lastScan: LibraryScanResult? = null,
    val acceptedEntry: QueueEntryId? = null,
)

/** Library requests from the UI; every default does nothing so screens can be shown over fixed states. */
internal interface LibraryActions {
    fun show(view: LibraryView) {}
    fun open(group: LibraryGroup) {}
    fun close() {}
    fun search(keyword: String) {}
    fun sort(field: LibraryField, descending: Boolean) {}
    fun showFrom(firstVisible: Int) {}
    fun retry() {}
    fun select(item: LibraryItem) {}
    fun retrySelection() {}
    fun addMusic() {}
    fun manageSources() {}
    fun retrySources() {}
    fun retryScan(rootId: String) {}
    fun cancelScan() {}
}

@Composable
internal fun LibraryScreen(
    library: LibraryUi,
    notice: LibraryNotice?,
    actions: LibraryActions,
    loadArtwork: suspend (VariantId, Int) -> Artwork,
) {
    val browse = library.browse
    val latestActions by rememberUpdatedState(actions)
    BackHandler(enabled = browse.opened != null) { actions.close() }
    // Each choice has its own scroll position; rotation restores it through the saver.
    val listState = rememberSaveable(browse.view, browse.opened, browse.sort, browse.descending, saver = LazyListState.Saver) {
        LazyListState()
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { latestActions.showFrom((it - 1).coerceAtLeast(0)) }
    }
    val list: LibraryList<Any> = if (browse.listsSongs) library.songs else library.groups
    Column(Modifier.widthIn(max = AudippSpacing.contentMaxWidth).fillMaxSize()) {
        // Kept above the list so the failure stays in view where the song was chosen.
        (library.selection as? TrackSelection.Failed)?.let { failed ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = AudippSpacing.extraLarge, vertical = AudippSpacing.small)) {
                    Text(
                        failed.item.title ?: stringResource(R.string.playback_unknown_title),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(failed.reason.label))
                    TextButton(onClick = actions::retrySelection) { Text(stringResource(R.string.retry)) }
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = AudippSpacing.extraLarge, vertical = AudippSpacing.screenVertical),
            verticalArrangement = Arrangement.spacedBy(AudippSpacing.small),
        ) {
            item(contentType = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(AudippSpacing.large)) {
                    LibraryHeader(library, notice, actions)
                }
            }
            when (list) {
                LibraryList.Loading -> item(contentType = "state") { Loading(R.string.library_loading) }
                LibraryList.Failed -> item(contentType = "state") {
                    Column {
                        Text(stringResource(R.string.library_failed))
                        TextButton(onClick = actions::retry) { Text(stringResource(R.string.retry)) }
                    }
                }
                is LibraryList.Ready -> {
                    if (list.total == 0 && notice != LibraryNotice.Empty) {
                        item(contentType = "state") { Text(stringResource(R.string.library_no_match)) }
                    }
                    items(list.total, contentType = { "row" }) { index ->
                        when (val row = list.rowAt(index)) {
                            null -> PendingRow()
                            is LibraryItem -> SongRow(row, loadArtwork) { actions.select(row) }
                            is LibraryGroup -> GroupRow(row, browse.view, loadArtwork) { actions.open(row) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(library: LibraryUi, notice: LibraryNotice?, actions: LibraryActions) {
    val browse = library.browse
    Text(
        stringResource(R.string.navigation_library),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineLarge,
    )
    notice?.let { Notice(it, actions) }
    TextButton(onClick = actions::manageSources) { Text(stringResource(R.string.library_manage_sources)) }
    FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(AudippSpacing.small),
    ) {
        LibraryView.entries.forEach { view ->
            FilterChip(
                selected = browse.view == view,
                onClick = { actions.show(view) },
                label = { Text(stringResource(view.label)) },
                modifier = Modifier.heightIn(min = AudippSpacing.minimumTouchTarget),
            )
        }
    }
    browse.opened?.let { group ->
        Column {
            Text(
                groupName(group, browse.view),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            group.albumArtist?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = actions::close) { Text(stringResource(R.string.library_back_to_list)) }
        }
    }
    OutlinedTextField(
        value = browse.keyword,
        onValueChange = actions::search,
        label = { Text(stringResource(R.string.library_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AudippSpacing.small)) {
        if (browse.listsSongs) SortMenu(browse, actions)
        val order = stringResource(if (browse.descending) R.string.library_order_descending else R.string.library_order_ascending)
        TextButton(
            onClick = { actions.sort(browse.sort, !browse.descending) },
            modifier = Modifier.semantics { stateDescription = order },
        ) { Text(order) }
    }
}

@Composable
private fun SortMenu(browse: LibraryBrowse, actions: LibraryActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.library_sort, stringResource(browse.sort.label)))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibraryField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(stringResource(field.label)) },
                    leadingIcon = { RadioButton(selected = browse.sort == field, onClick = null) },
                    onClick = {
                        open = false
                        actions.sort(field, browse.descending)
                    },
                    modifier = Modifier.semantics { selected = browse.sort == field },
                )
            }
        }
    }
}

@Composable
private fun Notice(notice: LibraryNotice, actions: LibraryActions) {
    Column(verticalArrangement = Arrangement.spacedBy(AudippSpacing.small)) {
        when (notice) {
            LibraryNotice.SourcesFailed -> {
                Text(stringResource(R.string.local_sources_read_failed))
                TextButton(onClick = actions::retrySources) { Text(stringResource(R.string.retry)) }
            }
            LibraryNotice.Empty -> {
                Text(stringResource(R.string.local_sources_empty))
                Button(onClick = actions::addMusic) { Text(stringResource(R.string.music_add)) }
            }
            is LibraryNotice.Scanning -> {
                if (notice.visited == 0) Loading(R.string.music_scan_loading)
                else Text(pluralStringResource(R.plurals.music_scan_visited, notice.visited, notice.visited))
                TextButton(onClick = actions::cancelScan) { Text(stringResource(android.R.string.cancel)) }
            }
            LibraryNotice.Denied, LibraryNotice.AccessLost -> {
                Text(stringResource(
                    if (notice == LibraryNotice.Denied) R.string.music_permission_denied else R.string.music_access_lost,
                ))
                Button(onClick = actions::addMusic) { Text(stringResource(R.string.music_choose_again)) }
            }
            is LibraryNotice.Cancelled -> {
                Text(stringResource(R.string.library_scan_cancelled))
                TextButton(onClick = { actions.retryScan(notice.rootId) }) { Text(stringResource(R.string.music_retry_scan)) }
            }
            is LibraryNotice.Incomplete -> {
                Text(stringResource(R.string.music_scan_failed))
                TextButton(onClick = { actions.retryScan(notice.rootId) }) { Text(stringResource(R.string.music_retry_scan)) }
            }
        }
    }
}

@Composable
private fun SongRow(item: LibraryItem, loadArtwork: suspend (VariantId, Int) -> Artwork, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = RowHeight).clickable(onClick = onClick)
            .padding(vertical = AudippSpacing.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(AudippSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkBox(rememberArtwork(VariantId(item.variantId), ArtworkSize, loadArtwork), Modifier.size(ArtworkSize))
        Column(Modifier.weight(1f)) {
            Text(
                item.title ?: stringResource(R.string.playback_unknown_title),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.artist ?: stringResource(R.string.library_unknown_artist),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupRow(
    group: LibraryGroup,
    view: LibraryView,
    loadArtwork: suspend (VariantId, Int) -> Artwork,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = RowHeight).clickable(onClick = onClick)
            .padding(vertical = AudippSpacing.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(AudippSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (view == LibraryView.ALBUMS) {
            ArtworkBox(rememberArtwork(VariantId(group.firstVariantId), ArtworkSize, loadArtwork), Modifier.size(ArtworkSize))
        }
        Column(Modifier.weight(1f)) {
            Text(groupName(group, view), maxLines = 2, overflow = TextOverflow.Ellipsis)
            group.albumArtist?.let {
                Text(
                    it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PendingRow() {
    val label = stringResource(R.string.library_row_loading)
    Box(Modifier.fillMaxWidth().heightIn(min = RowHeight).semantics { contentDescription = label })
}

@Composable
private fun Loading(@StringRes labelId: Int) {
    val label = stringResource(labelId)
    CircularProgressIndicator(Modifier.semantics { contentDescription = label })
}

@Composable
private fun groupName(group: LibraryGroup, view: LibraryView): String = group.name ?: stringResource(
    when (view) {
        LibraryView.ALBUMS -> R.string.library_unclassified
        LibraryView.ARTISTS -> R.string.library_unknown_artist
        LibraryView.FOLDERS -> R.string.library_unknown_folder
        else -> R.string.library_unknown_format
    },
)

private val ArtworkSize = 48.dp
private val RowHeight = 56.dp

private val LibraryView.label: Int
    @StringRes get() = when (this) {
        LibraryView.SONGS -> R.string.library_view_songs
        LibraryView.ALBUMS -> R.string.library_view_albums
        LibraryView.ARTISTS -> R.string.library_view_artists
        LibraryView.FOLDERS -> R.string.library_view_folders
        LibraryView.FORMATS -> R.string.library_view_formats
    }

private val LibraryField.label: Int
    @StringRes get() = when (this) {
        LibraryField.TITLE -> R.string.library_sort_title
        LibraryField.ALBUM -> R.string.library_sort_album
        LibraryField.ARTIST -> R.string.library_sort_artist
        LibraryField.GENRE -> R.string.library_sort_genre
        LibraryField.YEAR -> R.string.library_sort_year
        LibraryField.FORMAT -> R.string.library_sort_format
        LibraryField.TRACK_NUMBER -> R.string.library_sort_track_number
    }

private val SelectionFailure.label: Int
    @StringRes get() = when (this) {
        SelectionFailure.MISSING -> R.string.library_selection_missing
        SelectionFailure.DISCONNECTED -> R.string.playback_disconnected
        SelectionFailure.PERMISSION_DENIED -> R.string.playback_permission_denied
        SelectionFailure.FORMAT_UNSUPPORTED -> R.string.playback_format_unsupported
        SelectionFailure.FAILED -> R.string.playback_failed
    }
