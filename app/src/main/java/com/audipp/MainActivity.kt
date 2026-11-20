package com.audipp

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audipp.data.preferences.PreferenceOperation
import com.audipp.data.preferences.preferenceStore
import com.audipp.domain.model.ThemeMode
import com.audipp.ui.AudippApp
import com.audipp.ui.formatAndroidVersion
import com.audipp.ui.navigation.NavigationViewModel
import com.audipp.ui.settings.ThemeSettingsController
import com.audipp.ui.theme.AudippDarkColors
import com.audipp.ui.theme.AudippTheme
import com.audipp.ui.theme.isDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val navigation by viewModels<NavigationViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val sources by navigation.sources.collectAsStateWithLifecycle()
            val playback by navigation.playback.collectAsStateWithLifecycle()
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
                    onTheme = { scope.launch { controller.select(it) } },
                    onRetryTheme = {
                        if (state.failure == PreferenceOperation.READ) readAttempt++
                        else state.failedTheme?.let { scope.launch { controller.select(it) } }
                    },
                    onRetrySources = navigation::retrySources,
                    onRetryPlayback = navigation::retryPlayback,
                    onTogglePlayback = navigation::togglePlayback,
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
