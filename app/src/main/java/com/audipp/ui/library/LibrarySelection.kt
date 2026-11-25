package com.audipp.ui.library

import com.audipp.data.db.LibraryItem
import com.audipp.domain.model.ErrorDomain
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId
import com.audipp.ui.navigation.NavigationPlaybackState
import com.audipp.ui.navigation.PlayRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal enum class SelectionFailure { MISSING, DISCONNECTED, PERMISSION_DENIED, FORMAT_UNSUPPORTED, FAILED }

/** The song chosen in the library, from the request until the service reports it as the current entry. */
internal sealed interface TrackSelection {
    data object None : TrackSelection
    data class Starting(val item: LibraryItem, val entryId: QueueEntryId) : TrackSelection
    /** The service accepted the Variant and is preparing it. */
    data class Accepted(val item: LibraryItem, val entryId: QueueEntryId) : TrackSelection
    data class Failed(val item: LibraryItem, val reason: SelectionFailure) : TrackSelection
}

/**
 * Sends a chosen Variant to the shared play entry. A newer choice cancels the older request, so its late answer
 * cannot replace the newer state.
 */
internal class LibrarySelection(
    private val scope: CoroutineScope,
    private val lookup: suspend (VariantId) -> TrackVariant?,
    private val send: suspend (QueueEntryId, TrackVariant) -> PlayRequest,
    private val playback: Flow<NavigationPlaybackState>,
    private val newEntryId: () -> QueueEntryId,
) {
    private val selection = MutableStateFlow<TrackSelection>(TrackSelection.None)
    val state: StateFlow<TrackSelection> = selection
    private val acceptedEntry = MutableStateFlow<QueueEntryId?>(null)
    /** The latest entry the service accepted. It outlives [TrackSelection.Accepted], which the service may end at once. */
    val accepted: StateFlow<QueueEntryId?> = acceptedEntry
    private var job: Job? = null

    fun select(item: LibraryItem) {
        job?.cancel()
        val entry = newEntryId()
        selection.value = TrackSelection.Starting(item, entry)
        job = scope.launch {
            val variant = try {
                lookup(VariantId(item.variantId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                selection.value = TrackSelection.Failed(item, SelectionFailure.FAILED)
                return@launch
            }
            if (variant == null) {
                selection.value = TrackSelection.Failed(item, SelectionFailure.MISSING)
                return@launch
            }
            when (val request = send(entry, variant)) {
                PlayRequest.Disconnected -> selection.value = TrackSelection.Failed(item, SelectionFailure.DISCONNECTED)
                is PlayRequest.Rejected -> selection.value = TrackSelection.Failed(item, request.domain.failure())
                PlayRequest.Accepted -> {
                    selection.value = TrackSelection.Accepted(item, entry)
                    acceptedEntry.value = entry
                    val reported = playback.first {
                        it == NavigationPlaybackState.Disconnected ||
                            it is NavigationPlaybackState.Current && it.entryId == entry ||
                            it is NavigationPlaybackState.Failed && it.entryId == entry
                    }
                    selection.value = when (reported) {
                        is NavigationPlaybackState.Failed -> TrackSelection.Failed(item, reported.domain.failure())
                        NavigationPlaybackState.Disconnected -> TrackSelection.Failed(item, SelectionFailure.DISCONNECTED)
                        else -> TrackSelection.None
                    }
                }
            }
        }
    }

    fun retry() {
        (selection.value as? TrackSelection.Failed)?.let { select(it.item) }
    }
}

private fun ErrorDomain.failure() = when (this) {
    ErrorDomain.PERMISSION_DENIED -> SelectionFailure.PERMISSION_DENIED
    ErrorDomain.FORMAT_UNSUPPORTED -> SelectionFailure.FORMAT_UNSUPPORTED
    else -> SelectionFailure.FAILED
}

/**
 * Until the service reports the chosen entry, the player shows the chosen song as preparing with only the library's
 * title and artist for that Variant, instead of the previous song's position, format, output or artwork.
 */
internal fun NavigationPlaybackState.withSelection(selection: TrackSelection): NavigationPlaybackState {
    val (item, entry) = when (selection) {
        is TrackSelection.Starting -> selection.item to selection.entryId
        is TrackSelection.Accepted -> selection.item to selection.entryId
        else -> return this
    }
    if (this is NavigationPlaybackState.Current && entryId == entry) return this
    return NavigationPlaybackState.Current(
        phase = PlaybackPhase.RESOLVING, title = item.title, artist = item.artist, control = null,
        variantId = VariantId(item.variantId), entryId = entry,
    )
}
