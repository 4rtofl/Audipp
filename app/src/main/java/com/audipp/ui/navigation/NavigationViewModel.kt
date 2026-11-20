package com.audipp.ui.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.db.LocalSourceSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

internal sealed interface HomeSources {
    data object Loading : HomeSources
    data object Failed : HomeSources
    data class Ready(val summary: LocalSourceSummary) : HomeSources
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val database = lazy { LibraryDatabase.open(application) }
    private val playbackConnection = NavigationPlayback(application)
    private val sourceAttempt = MutableStateFlow(0)
    private val playbackAttempt = MutableStateFlow(0)
    // Keep the controller bound during a configuration change, including when playback is paused.
    private val whileVisible = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0)

    val sources = sourceAttempt.flatMapLatest {
        flow<HomeSources> {
            emit(HomeSources.Loading)
            database.value.libraryDao().observeLocalSourceSummary().collect {
                emit(HomeSources.Ready(it))
            }
        }.catch {
            if (it is CancellationException || it !is Exception) throw it
            emit(HomeSources.Failed)
        }
    }.stateIn(viewModelScope, whileVisible, HomeSources.Loading)

    val playback = playbackAttempt.flatMapLatest { playbackConnection.observe() }
        .stateIn(viewModelScope, whileVisible, NavigationPlaybackState.Connecting)

    fun retrySources() = sourceAttempt.update { it + 1 }

    fun retryPlayback() = playbackAttempt.update { it + 1 }

    fun togglePlayback(control: PlaybackControl) = playbackConnection.toggle(control)

    override fun onCleared() {
        if (database.isInitialized()) database.value.close()
    }
}
