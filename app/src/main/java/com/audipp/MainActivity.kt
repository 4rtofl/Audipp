package com.audipp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audipp.data.db.LibraryField
import com.audipp.data.db.LibraryGroup
import com.audipp.data.db.LibraryItem
import com.audipp.data.local.SafLibrary
import com.audipp.data.preferences.PreferenceOperation
import com.audipp.data.preferences.preferenceStore
import com.audipp.domain.model.ThemeMode
import com.audipp.playback.PlaybackConnection
import com.audipp.ui.AudippApp
import com.audipp.ui.formatAndroidVersion
import com.audipp.ui.library.LibraryActions
import com.audipp.ui.library.LibraryUi
import com.audipp.ui.library.LibraryView
import com.audipp.ui.navigation.NavigationViewModel
import com.audipp.ui.settings.ThemeSettingsController
import com.audipp.ui.theme.AudippDarkColors
import com.audipp.ui.theme.AudippTheme
import com.audipp.ui.theme.isDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val navigation by viewModels<NavigationViewModel>()

    // The notification's content intent asks for Now Playing; recreation must not replay an already handled request.
    private var openNowPlaying by mutableStateOf(false)

    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        navigation.onFolderResult(it.resultCode == Activity.RESULT_OK, it.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openNowPlaying = intent.action == PlaybackConnection.ACTION_OPEN_NOW_PLAYING
    }

    override fun onResume() {
        super.onResume()
        navigation.refreshGrants()
    }

    private val libraryActions = object : LibraryActions {
        override fun show(view: LibraryView) = navigation.library.show(view)
        override fun open(group: LibraryGroup) = navigation.library.open(group)
        override fun close() = navigation.library.close()
        override fun search(keyword: String) = navigation.library.search(keyword)
        override fun sort(field: LibraryField, descending: Boolean) = navigation.library.sort(field, descending)
        override fun showFrom(firstVisible: Int) = navigation.library.showFrom(firstVisible)
        override fun retry() = navigation.library.retry()
        override fun select(item: LibraryItem) = navigation.select(item)
        override fun retrySelection() = navigation.retrySelection()
    }

    private fun chooseFolder() {
        if (!navigation.openPicker()) return
        try {
            folderPicker.launch(SafLibrary.openTreeIntent())
        } catch (_: ActivityNotFoundException) {
            navigation.pickerUnavailable()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openNowPlaying = savedInstanceState == null && intent.action == PlaybackConnection.ACTION_OPEN_NOW_PLAYING
        enableEdgeToEdge()
        setContent {
            val sources by navigation.sources.collectAsStateWithLifecycle()
            val recentPlays by navigation.recentPlays.collectAsStateWithLifecycle()
            val registration by navigation.registration.collectAsStateWithLifecycle()
            val playback by navigation.playback.collectAsStateWithLifecycle()
            val browse by navigation.library.browse.collectAsStateWithLifecycle()
            val songs by navigation.library.songs.collectAsStateWithLifecycle()
            val groups by navigation.library.groups.collectAsStateWithLifecycle()
            val selection by navigation.selection.collectAsStateWithLifecycle()
            val lastScan by navigation.lastScan.collectAsStateWithLifecycle()
            val acceptedEntry by navigation.acceptedEntry.collectAsStateWithLifecycle()
            val controller = remember { ThemeSettingsController(applicationContext.preferenceStore) }
            val state by controller.state.collectAsState()
            val scope = rememberCoroutineScope()
            var readAttempt by remember { mutableIntStateOf(0) }
            LaunchedEffect(controller, readAttempt) { controller.observe() }
            val mode = state.theme ?: ThemeMode.SYSTEM
            val dark = mode.isDark(isSystemInDarkTheme())
            SideEffect {
                updateSystemBars(dark)
            }
            AudippTheme(mode = mode) {
                AudippApp(
                    androidVersion = formatAndroidVersion(Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
                    themeState = state,
                    sources = sources,
                    playback = playback,
                    recentPlays = recentPlays,
                    registration = registration,
                    onAddMusic = ::chooseFolder,
                    onRetryScan = navigation::retryScan,
                    onCancelScan = navigation::cancelScan,
                    onRetryHistory = navigation::retryHistory,
                    onTheme = { scope.launch { controller.select(it) } },
                    onRetryTheme = {
                        if (state.failure == PreferenceOperation.READ) readAttempt++
                        else state.failedTheme?.let { scope.launch { controller.select(it) } }
                    },
                    onRetrySources = navigation::retrySources,
                    onRetryPlayback = navigation::retryPlayback,
                    onTogglePlayback = navigation::togglePlayback,
                    onConfirmOutput = navigation::confirmOutput,
                    onSeek = navigation::seek,
                    loadArtwork = { variantId, edgePx ->
                        (application as AudippApplication).artworkProvider.load(variantId.value, edgePx)
                    },
                    openNowPlaying = openNowPlaying,
                    onOpenNowPlayingHandled = { openNowPlaying = false },
                    library = LibraryUi(browse, songs, groups, selection, lastScan, acceptedEntry),
                    libraryActions = libraryActions,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars(dark: Boolean) {
        // Repeating enableEdgeToEdge adds protection views on API35+ in Activity 1.12.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            window.navigationBarColor = AudippDarkColors.background.toArgb()
        }
    }
}
