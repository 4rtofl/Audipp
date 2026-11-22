package com.audipp.ui.navigation

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.db.LocalSourceSummary
import com.audipp.data.db.RecentPlay
import com.audipp.data.db.libraryDatabase
import com.audipp.data.local.LibraryScan
import com.audipp.data.local.LibraryScanStatus
import com.audipp.data.local.LocalTreeRegistration
import com.audipp.data.local.SafLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface HomeSources {
    data object Loading : HomeSources
    data object Failed : HomeSources
    data class Ready(
        val summary: LocalSourceSummary,
        val hasFolders: Boolean,
        val accessLost: Boolean,
        val retryRootId: String?,
    ) : HomeSources
}

internal sealed interface RecentPlays {
    data object Loading : RecentPlays
    data object Failed : RecentPlays
    data class Ready(val plays: List<RecentPlay>) : RecentPlays
}

internal sealed interface FolderRegistration {
    data object Idle : FolderRegistration
    data object Picking : FolderRegistration
    data object PickerUnavailable : FolderRegistration
    data object Registering : FolderRegistration
    data object Denied : FolderRegistration
    data object RegistrationFailed : FolderRegistration
    data class Scanning(val visited: Int) : FolderRegistration
    data class ScanFailed(val rootId: String) : FolderRegistration
    data object AccessLost : FolderRegistration

    val busy: Boolean
        get() = this == Picking || this == Registering || this is Scanning
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class NavigationViewModel(
    application: Application,
    private val database: LibraryDatabase,
    private val safLibrary: SafLibrary,
) : AndroidViewModel(application) {
    constructor(application: Application) :
        this(application, application.libraryDatabase, SafLibrary(application, application.libraryDatabase))

    private val playbackConnection = NavigationPlayback(application)
    private val sourceAttempt = MutableStateFlow(0)
    private val historyAttempt = MutableStateFlow(0)
    private val grantCheck = MutableStateFlow(0)
    private val playbackAttempt = MutableStateFlow(0)
    private val folderState = MutableStateFlow<FolderRegistration>(FolderRegistration.Idle)
    val registration: StateFlow<FolderRegistration> = folderState
    private var activeScan: LibraryScan? = null
    // Keep the controller bound during a configuration change, including when playback is paused.
    private val whileVisible = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0)

    val sources = sourceAttempt.flatMapLatest {
        flow<HomeSources> {
            emit(HomeSources.Loading)
            combine(database.libraryDao().observeLocalSourceSummary(), database.safScanDao().observeRoots(), grantCheck) {
                    summary, roots, _ ->
                val grants = withContext(Dispatchers.IO) {
                    application.contentResolver.persistedUriPermissions
                        .filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
                }
                HomeSources.Ready(
                    summary,
                    hasFolders = roots.isNotEmpty(),
                    accessLost = roots.any { it.treeUri !in grants || it.lastScanStatus == LibraryScanStatus.ACCESS_LOST },
                    retryRootId = roots.firstOrNull {
                        it.treeUri in grants && (it.lastScanStatus == null ||
                            it.lastScanStatus == LibraryScanStatus.PARTIAL)
                    }?.id,
                )
            }.collect { emit(it) }
        }.catch {
            if (it !is Exception || it is CancellationException) throw it
            emit(HomeSources.Failed)
        }
    }.stateIn(viewModelScope, whileVisible, HomeSources.Loading)

    val recentPlays = historyAttempt.flatMapLatest {
        flow<RecentPlays> {
            emit(RecentPlays.Loading)
            database.playbackHistoryDao().observeRecentPlays().collect { emit(RecentPlays.Ready(it)) }
        }.catch {
            if (it !is Exception || it is CancellationException) throw it
            emit(RecentPlays.Failed)
        }
    }.stateIn(viewModelScope, whileVisible, RecentPlays.Loading)

    val playback = playbackAttempt.flatMapLatest { playbackConnection.observe() }
        .stateIn(viewModelScope, whileVisible, NavigationPlaybackState.Connecting)

    fun retrySources() = sourceAttempt.update { it + 1 }

    fun retryHistory() = historyAttempt.update { it + 1 }

    fun refreshGrants() = grantCheck.update { it + 1 }

    fun retryPlayback() = playbackAttempt.update { it + 1 }

    fun togglePlayback(control: PlaybackControl) = playbackConnection.toggle(control)

    fun openPicker(): Boolean {
        if (folderState.value.busy) return false
        folderState.value = FolderRegistration.Picking
        return true
    }

    fun pickerUnavailable() {
        folderState.value = FolderRegistration.PickerUnavailable
    }

    fun onFolderResult(resultOk: Boolean, selection: Intent?) {
        if (folderState.value == FolderRegistration.Registering || folderState.value is FolderRegistration.Scanning) return
        if (!resultOk) {
            folderState.value = FolderRegistration.Idle
            return
        }
        folderState.value = FolderRegistration.Registering
        viewModelScope.launch {
            when (val result = safLibrary.registerLocalTree(selection)) {
                is LocalTreeRegistration.Registered -> scan(result.rootId)
                LocalTreeRegistration.Rejected.INVALID_SELECTION,
                LocalTreeRegistration.Rejected.STORAGE_ERROR -> folderState.value = FolderRegistration.RegistrationFailed
                else -> folderState.value = FolderRegistration.Denied
            }
        }
    }

    fun retryScan(rootId: String) {
        if (folderState.value.busy) return
        folderState.value = FolderRegistration.Scanning(0)
        viewModelScope.launch { scan(rootId) }
    }

    fun cancelScan() {
        activeScan?.cancel()
    }

    private suspend fun scan(rootId: String) {
        folderState.value = FolderRegistration.Scanning(0)
        try {
            val status = coroutineScope {
                val scan = safLibrary.startScan(rootId, this)
                activeScan = scan
                val progress = launch {
                    scan.progress.collect { folderState.value = FolderRegistration.Scanning(it.visited) }
                }
                try {
                    scan.await().status
                } finally {
                    progress.cancel()
                }
            }
            folderState.value = when (status) {
                LibraryScanStatus.COMPLETE -> FolderRegistration.Idle
                LibraryScanStatus.CANCELLED, LibraryScanStatus.PARTIAL -> FolderRegistration.ScanFailed(rootId)
                LibraryScanStatus.ACCESS_LOST -> FolderRegistration.AccessLost
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            folderState.value = FolderRegistration.ScanFailed(rootId)
        } finally {
            activeScan = null
        }
    }
}
