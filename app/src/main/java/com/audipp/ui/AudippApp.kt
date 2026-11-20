package com.audipp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import com.audipp.R
import com.audipp.domain.model.ThemeMode
import com.audipp.ui.audio.UpnpScreen
import com.audipp.ui.home.HomeScreen
import com.audipp.ui.navigation.HomeSources
import com.audipp.ui.navigation.NavigationPlaybackBar
import com.audipp.ui.navigation.NavigationPlaybackState
import com.audipp.ui.navigation.PlaybackControl
import com.audipp.ui.settings.ThemeSettingsScreen
import com.audipp.ui.settings.ThemeSettingsState
import com.audipp.ui.theme.AudippSpacing

private enum class Screen { HOME, SETTINGS, APPEARANCE, UPNP }

@Composable
internal fun AudippApp(
    androidVersion: String,
    themeState: ThemeSettingsState,
    sources: HomeSources,
    playback: NavigationPlaybackState,
    onTheme: (ThemeMode) -> Unit,
    onRetryTheme: () -> Unit,
    onRetrySources: () -> Unit,
    onRetryPlayback: () -> Unit,
    onTogglePlayback: (PlaybackControl) -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val screenState = rememberSaveableStateHolder()
    val back = { screen = if (screen == Screen.APPEARANCE || screen == Screen.UPNP) Screen.SETTINGS else Screen.HOME }
    BackHandler(enabled = screen != Screen.HOME, onBack = back)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                screenState.SaveableStateProvider(screen) {
                    if (screen == Screen.UPNP) {
                        Column {
                            BackButton(back)
                            UpnpScreen()
                        }
                    } else ScreenColumn {
                        when (screen) {
                            Screen.HOME -> HomeScreen(
                                sources = sources,
                                playback = playback,
                                onSettings = { screen = Screen.SETTINGS },
                                onRetrySources = onRetrySources,
                                onRetryPlayback = onRetryPlayback,
                            )
                            Screen.SETTINGS -> {
                                BackButton(back)
                                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineLarge)
                                TextButton(onClick = { screen = Screen.APPEARANCE }) {
                                    Text(stringResource(R.string.appearance))
                                }
                                TextButton(onClick = { screen = Screen.UPNP }) {
                                    Text(stringResource(R.string.upnp_output))
                                }
                                Text(androidVersion, style = MaterialTheme.typography.bodyMedium)
                            }
                            Screen.APPEARANCE -> {
                                BackButton(back)
                                ThemeSettingsScreen(themeState, onTheme, onRetryTheme)
                            }
                            Screen.UPNP -> Unit
                        }
                    }
                }
            }
            if (playback is NavigationPlaybackState.Current) {
                NavigationPlaybackBar(playback, onTogglePlayback)
            }
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

@Composable
private fun BackButton(onBack: () -> Unit) {
    TextButton(
        modifier = Modifier.heightIn(min = AudippSpacing.minimumTouchTarget),
        onClick = onBack,
    ) {
        Text(stringResource(R.string.back))
    }
}
