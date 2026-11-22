package com.audipp.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.audipp.R
import com.audipp.domain.model.ThemeMode
import com.audipp.ui.audio.UpnpScreen
import com.audipp.ui.home.HomeScreen
import com.audipp.ui.home.LocalMusic
import com.audipp.ui.navigation.FolderRegistration
import com.audipp.ui.navigation.HomeSources
import com.audipp.ui.navigation.NavigationPlaybackBar
import com.audipp.ui.navigation.NavigationPlaybackState
import com.audipp.ui.navigation.PlaybackControl
import com.audipp.ui.navigation.RecentPlays
import com.audipp.ui.settings.ThemeSettingsScreen
import com.audipp.ui.settings.ThemeSettingsState
import com.audipp.ui.theme.AudippSpacing

internal enum class NavigationTab(@param:StringRes val label: Int) {
    HOME(R.string.home), LIBRARY(R.string.navigation_library), SEARCH(R.string.navigation_search),
    VISUALIZER(R.string.navigation_visualizer),
}

internal enum class NavigationPage(@param:StringRes val label: Int) {
    SETTINGS(R.string.settings), APPEARANCE(R.string.appearance), UPNP(R.string.upnp_output),
    QUEUE(R.string.navigation_queue),
}

internal data class NavigationDestination(
    val tab: NavigationTab,
    val page: NavigationPage?,
) {
    fun select(tab: NavigationTab) = NavigationDestination(tab, null)

    fun back(): NavigationDestination? = when {
        page == NavigationPage.APPEARANCE || page == NavigationPage.UPNP -> copy(page = NavigationPage.SETTINGS)
        page != null -> copy(page = null)
        tab != NavigationTab.HOME -> select(NavigationTab.HOME)
        else -> null
    }
}

@Composable
internal fun AudippApp(
    androidVersion: String,
    themeState: ThemeSettingsState,
    sources: HomeSources,
    playback: NavigationPlaybackState,
    recentPlays: RecentPlays,
    registration: FolderRegistration,
    onAddMusic: () -> Unit,
    onRetryScan: (String) -> Unit,
    onCancelScan: () -> Unit,
    onRetryHistory: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onRetryTheme: () -> Unit,
    onRetrySources: () -> Unit,
    onRetryPlayback: () -> Unit,
    onTogglePlayback: (PlaybackControl) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(NavigationTab.HOME) }
    var page by rememberSaveable { mutableStateOf<NavigationPage?>(null) }
    val destination = NavigationDestination(tab, page)
    val screenState = rememberSaveableStateHolder()
    val previous = destination.back()
    fun navigate(next: NavigationDestination) {
        tab = next.tab
        page = next.page
    }
    val onTab: (NavigationTab) -> Unit = { navigate(destination.select(it)) }
    val currentPage = page
    BackHandler(enabled = previous != null) { previous?.let(::navigate) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.safeDrawingPadding()) {
            val useRail = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = AudippSpacing.small),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (currentPage != null && previous != null) {
                            TextButton(onClick = { navigate(previous) }) { Text(stringResource(R.string.back)) }
                        }
                        TextButton(onClick = { page = NavigationPage.QUEUE }) {
                            Text(stringResource(R.string.navigation_queue))
                        }
                        TextButton(onClick = { page = NavigationPage.SETTINGS }) {
                            Text(stringResource(R.string.settings))
                        }
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (useRail) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(
                                Modifier.width(112.dp).verticalScroll(rememberScrollState()).selectableGroup(),
                            ) {
                                NavigationTab.entries.forEach {
                                    NavigationItem(it, tab == it && currentPage == null, onTab, Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        screenState.SaveableStateProvider(currentPage?.name ?: tab.name) {
                            if (currentPage == NavigationPage.UPNP) {
                                UpnpScreen()
                            } else {
                                ScreenColumn {
                                    if (currentPage != NavigationPage.APPEARANCE) {
                                        Text(
                                            stringResource(currentPage?.label ?: tab.label),
                                            modifier = Modifier.semantics { heading() },
                                            style = MaterialTheme.typography.headlineLarge,
                                        )
                                    }
                                    when (currentPage) {
                                        NavigationPage.SETTINGS -> {
                                            TextButton(onClick = { page = NavigationPage.APPEARANCE }) {
                                                Text(stringResource(R.string.appearance))
                                            }
                                            TextButton(onClick = { page = NavigationPage.UPNP }) {
                                                Text(stringResource(R.string.upnp_output))
                                            }
                                            Text(androidVersion, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        NavigationPage.APPEARANCE -> ThemeSettingsScreen(themeState, onTheme, onRetryTheme)
                                        NavigationPage.QUEUE -> when (playback) {
                                            NavigationPlaybackState.Idle -> Text(stringResource(R.string.navigation_queue_empty))
                                            is NavigationPlaybackState.Current -> {
                                                Text(stringResource(R.string.navigation_queue_current), style = MaterialTheme.typography.titleLarge)
                                                Text(playback.title ?: stringResource(R.string.playback_unknown_title))
                                            }
                                            else -> Text(stringResource(R.string.navigation_queue_unavailable))
                                        }
                                        NavigationPage.UPNP -> Unit
                                        null -> when (tab) {
                                            NavigationTab.HOME, NavigationTab.LIBRARY -> {
                                                LocalMusic(sources, registration, onAddMusic, onRetrySources, onRetryScan, onCancelScan)
                                                if (tab == NavigationTab.HOME) {
                                                    HomeScreen(recentPlays, playback, onRetryHistory, onRetryPlayback)
                                                } else if (sources is HomeSources.Ready && sources.summary.registered > 0) {
                                                    Text(stringResource(R.string.navigation_library_unavailable))
                                                }
                                            }
                                            NavigationTab.SEARCH -> {
                                                var query by rememberSaveable { mutableStateOf("") }
                                                OutlinedTextField(
                                                    value = query,
                                                    onValueChange = { query = it },
                                                    label = { Text(stringResource(R.string.navigation_search_query)) },
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                                if (sources is HomeSources.Ready && sources.summary.registered == 0L) {
                                                    LocalMusic(sources, registration, onAddMusic, onRetrySources, onRetryScan, onCancelScan)
                                                }
                                                Text(stringResource(R.string.navigation_search_unavailable))
                                            }
                                            NavigationTab.VISUALIZER ->
                                                Text(stringResource(R.string.navigation_visualizer_unavailable))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (playback is NavigationPlaybackState.Current) {
                    NavigationPlaybackBar(playback, onTogglePlayback)
                }
                if (!useRail) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
                            NavigationTab.entries.forEach {
                                NavigationItem(it, tab == it && currentPage == null, onTab, Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationItem(tab: NavigationTab, selected: Boolean, onTab: (NavigationTab) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.selectable(selected, role = Role.Tab, onClick = { onTab(tab) }),
        color = if (selected) colors.primary else colors.surfaceContainer,
        contentColor = if (selected) colors.onPrimary else colors.onSurface,
    ) {
        Box(
            modifier = Modifier.heightIn(min = AudippSpacing.minimumTouchTarget).padding(AudippSpacing.extraSmall),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(tab.label),
                style = MaterialTheme.typography.labelSmall.copy(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(max = AudippSpacing.contentMaxWidth)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AudippSpacing.extraLarge, vertical = AudippSpacing.screenVertical),
        verticalArrangement = Arrangement.spacedBy(AudippSpacing.large),
        content = content,
    )
}
