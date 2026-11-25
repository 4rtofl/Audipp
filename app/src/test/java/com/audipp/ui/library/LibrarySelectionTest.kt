package com.audipp.ui.library

import com.audipp.data.db.LibraryItem
import com.audipp.data.db.LocalSourceSummary
import com.audipp.data.local.LibraryScanProgress
import com.audipp.data.local.LibraryScanResult
import com.audipp.data.local.LibraryScanStatus
import com.audipp.domain.model.AudioFormat
import com.audipp.domain.model.Availability
import com.audipp.domain.model.ErrorDomain
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId
import com.audipp.ui.navigation.FolderRegistration
import com.audipp.ui.navigation.HomeSources
import com.audipp.ui.navigation.NavigationPlaybackState
import com.audipp.ui.navigation.ObservedOutput
import com.audipp.ui.navigation.PlayRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySelectionTest {
    private val item = LibraryItem("v-new", "t-new", null, "New title", null, "New artist", null, null, "FLAC")
    private val other = item.copy(variantId = "v-other", title = "Other")
    private fun variant(id: String) = TrackVariant(
        VariantId(id), TrackId("t-$id"), SourceRef(SourceId("s-$id"), SourceKind.LOCAL, availability = Availability.PLAYABLE),
        1_000, AudioFormat("FLAC", 44_100, 16, 2),
    )

    private val previous = NavigationPlaybackState.Current(
        PlaybackPhase.PLAYING, "Old title", "Old artist", null,
        variantId = VariantId("v-old"), sourceFormat = AudioFormat("DSD", 2_822_400, 1, 2),
        positionMs = 90_000, durationMs = 300_000, observedOutput = ObservedOutput.Single(1),
        entryId = QueueEntryId("old-entry"),
    )

    private class Harness(scope: CoroutineScope, variants: (VariantId) -> TrackVariant?) {
        val playback = MutableStateFlow<NavigationPlaybackState>(NavigationPlaybackState.Idle)
        val sent = mutableListOf<Pair<QueueEntryId, TrackVariant>>()
        var answer: suspend () -> PlayRequest = { PlayRequest.Accepted }
        private var next = 0
        val selection = LibrarySelection(
            scope,
            lookup = { variants(it) },
            send = { entry, variant -> sent += entry to variant; answer() },
            playback = playback,
            newEntryId = { QueueEntryId("entry-${next++}") },
        )
    }

    private fun withHarness(variants: (VariantId) -> TrackVariant?, block: suspend (Harness) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            withTimeout(10_000) { block(Harness(scope, variants)) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theChosenVariantGoesToThePlayEntryAndClearsOnceTheServiceReportsIt() = withHarness({ variant(it.value) }) { h ->
        h.playback.value = previous
        h.selection.select(item)
        val accepted = h.selection.state.first { it is TrackSelection.Accepted } as TrackSelection.Accepted
        assertEquals(listOf(QueueEntryId("entry-0") to variant("v-new")), h.sent)
        assertEquals(QueueEntryId("entry-0"), accepted.entryId)

        h.playback.value = previous.copy(variantId = VariantId("v-new"), entryId = QueueEntryId("entry-0"))
        h.selection.state.first { it == TrackSelection.None }
        assertEquals(QueueEntryId("entry-0"), h.selection.accepted.value)
    }

    @Test
    fun whilePreparingThePlayerShowsOnlyTheChosenSongNotThePreviousAttributes() {
        val starting = TrackSelection.Starting(item, QueueEntryId("entry-0"))
        val shown = previous.withSelection(starting) as NavigationPlaybackState.Current
        assertEquals(PlaybackPhase.RESOLVING, shown.phase)
        assertEquals("New title", shown.title)
        assertEquals("New artist", shown.artist)
        assertEquals(VariantId("v-new"), shown.variantId)
        assertNull(shown.sourceFormat)
        assertNull(shown.positionMs)
        assertNull(shown.durationMs)
        assertNull(shown.control)
        assertEquals(ObservedOutput.Unobserved, shown.observedOutput)
        assertEquals(NavigationPlaybackState.Idle.withSelection(starting), shown)

        val reported = previous.copy(entryId = QueueEntryId("entry-0"))
        assertEquals(reported, reported.withSelection(TrackSelection.Accepted(item, QueueEntryId("entry-0"))))
        assertEquals(previous, previous.withSelection(TrackSelection.Failed(item, SelectionFailure.FAILED)))
        assertEquals(previous, previous.withSelection(TrackSelection.None))
    }

    @Test
    fun failuresKeepTheChosenSongAndRetrySendsItAgain() = withHarness({ if (it.value == "v-gone") null else variant(it.value) }) { h ->
        h.selection.select(item.copy(variantId = "v-gone"))
        assertEquals(SelectionFailure.MISSING, (h.selection.state.first { it is TrackSelection.Failed } as TrackSelection.Failed).reason)
        assertEquals(emptyList<Any>(), h.sent)

        h.answer = { PlayRequest.Rejected(ErrorDomain.FORMAT_UNSUPPORTED) }
        h.selection.select(item)
        assertEquals(TrackSelection.Failed(item, SelectionFailure.FORMAT_UNSUPPORTED),
            h.selection.state.first { it is TrackSelection.Failed && it.item == item })

        h.answer = { PlayRequest.Disconnected }
        h.selection.retry()
        assertEquals(TrackSelection.Failed(item, SelectionFailure.DISCONNECTED),
            h.selection.state.first { it is TrackSelection.Failed && it.reason == SelectionFailure.DISCONNECTED })

        h.answer = { PlayRequest.Accepted }
        h.selection.retry()
        h.selection.state.first { it is TrackSelection.Accepted }
        h.playback.value = NavigationPlaybackState.Failed(ErrorDomain.PERMISSION_DENIED, h.sent.last().first)
        assertEquals(TrackSelection.Failed(item, SelectionFailure.PERMISSION_DENIED),
            h.selection.state.first { it is TrackSelection.Failed && it.reason == SelectionFailure.PERMISSION_DENIED })
        assertEquals(listOf("v-new", "v-new", "v-new"), h.sent.map { it.second.variantId.value })
    }

    @Test
    fun aFailureOfAnotherEntryDoesNotFailTheNewChoice() = withHarness({ variant(it.value) }) { h ->
        h.playback.value = NavigationPlaybackState.Failed(ErrorDomain.SOURCE_IO, QueueEntryId("old-entry"))
        h.selection.select(item)
        assertEquals(TrackSelection.Accepted(item, QueueEntryId("entry-0")), h.selection.state.first { it is TrackSelection.Accepted })
        h.playback.value = NavigationPlaybackState.Failed(ErrorDomain.SOURCE_IO, QueueEntryId("older"))
        assertEquals(TrackSelection.Accepted(item, QueueEntryId("entry-0")), h.selection.state.value)
    }

    @Test
    fun aNewerChoiceCancelsTheOlderRequestSoItsLateAnswerIsDropped() = withHarness({ variant(it.value) }) { h ->
        val slow = CompletableDeferred<PlayRequest>()
        h.answer = { slow.await() }
        h.selection.select(item)
        while (h.sent.isEmpty()) kotlinx.coroutines.yield()
        h.answer = { PlayRequest.Accepted }
        h.selection.select(other)
        assertEquals(TrackSelection.Accepted(other, QueueEntryId("entry-1")), h.selection.state.first { it is TrackSelection.Accepted })
        slow.complete(PlayRequest.Rejected(ErrorDomain.SOURCE_IO))
        kotlinx.coroutines.yield()
        assertEquals(TrackSelection.Accepted(other, QueueEntryId("entry-1")), h.selection.state.value)
    }
}

class LibraryNoticeTest {
    private val summary = LocalSourceSummary(registered = 3, unavailable = 0, unknown = 0)
    private fun ready(registered: Long = 3, accessLost: Boolean = false, retry: String? = null) =
        HomeSources.Ready(summary.copy(registered = registered), hasFolders = true, accessLost = accessLost, retryRootId = retry)
    private fun result(status: LibraryScanStatus) = LibraryScanResult("scan", status, LibraryScanProgress(), 0, emptySet())

    @Test
    fun mapsLoadingEmptyFailedPermissionAndCancelledStates() {
        assertNull(libraryNotice(HomeSources.Loading, FolderRegistration.Idle, null))
        assertNull(libraryNotice(ready(), FolderRegistration.Idle, null))
        assertEquals(LibraryNotice.SourcesFailed, libraryNotice(HomeSources.Failed, FolderRegistration.Idle, null))
        assertEquals(LibraryNotice.Empty, libraryNotice(ready(registered = 0), FolderRegistration.Idle, null))
        assertEquals(LibraryNotice.Scanning(0, cancellable = false), libraryNotice(ready(), FolderRegistration.Registering, null))
        assertEquals(LibraryNotice.Scanning(7),
            libraryNotice(ready(), FolderRegistration.Scanning(LibraryScanProgress(visited = 7)), null))
        assertEquals(LibraryNotice.Denied, libraryNotice(ready(registered = 0), FolderRegistration.Denied, null))
        assertEquals(LibraryNotice.AccessLost, libraryNotice(ready(), FolderRegistration.AccessLost, null))
        assertEquals(LibraryNotice.AccessLost, libraryNotice(ready(accessLost = true), FolderRegistration.Idle, null))
        assertEquals(LibraryNotice.Cancelled("root"),
            libraryNotice(ready(), FolderRegistration.ScanFailed("root"), result(LibraryScanStatus.CANCELLED)))
        assertEquals(LibraryNotice.Incomplete("root"),
            libraryNotice(ready(), FolderRegistration.ScanFailed("root"), result(LibraryScanStatus.PARTIAL)))
        assertEquals(LibraryNotice.Incomplete("other"), libraryNotice(ready(retry = "other"), FolderRegistration.Idle, null))
    }
}
