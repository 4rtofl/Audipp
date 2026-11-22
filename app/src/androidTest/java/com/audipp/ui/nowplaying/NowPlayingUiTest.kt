package com.audipp.ui.nowplaying

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.MainActivity
import com.audipp.R
import com.audipp.audio.route.OutputConfirmation
import com.audipp.data.local.Artwork
import com.audipp.domain.model.ArtworkOrigin
import com.audipp.domain.model.AudioFormat
import com.audipp.domain.model.Availability
import com.audipp.domain.model.PlaceholderReason
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.ThemeMode
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.VariantId
import com.audipp.playback.OutputConfirmationRequest
import com.audipp.ui.AudippApp
import com.audipp.ui.navigation.FolderRegistration
import com.audipp.ui.navigation.HomeSources
import com.audipp.ui.navigation.NavigationPlaybackState
import com.audipp.ui.navigation.ObservedOutput
import com.audipp.ui.navigation.PlaybackControl
import com.audipp.ui.navigation.RecentPlays
import com.audipp.ui.settings.ThemeSettingsState
import com.audipp.ui.theme.AudippTheme
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Composes the shared app UI over fixed playback states, so every state is reachable on API 23 and 36. */
@RunWith(AndroidJUnit4::class)
class NowPlayingUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val robot = AccessibilityRobot()
    private val control = PlaybackControl(QueueEntryId("entry"), TransitionId("transition"), pause = false)
    private val source = SourceRef(SourceId("source"), SourceKind.LOCAL, availability = Availability.PLAYABLE)
    private val paused = NavigationPlaybackState.Current(
        PlaybackPhase.PAUSED, "Fixture title", "Fixture artist", control,
        variantId = VariantId("variant"), source = source,
        sourceFormat = AudioFormat("FLAC", 96_000, 24, 2),
        positionMs = 61_000, durationMs = 245_000, seekCommandAvailable = true,
        observedOutput = ObservedOutput.Single(android.media.AudioDeviceInfo.TYPE_USB_DEVICE),
    )
    private val confirmation = OutputConfirmationRequest("changed", OutputConfirmation.ROUTE_CHANGED, null, null, null)

    private class Calls {
        val toggles = CopyOnWriteArrayList<PlaybackControl>()
        val seeks = CopyOnWriteArrayList<Long>()
        val confirmations = CopyOnWriteArrayList<OutputConfirmationRequest>()
    }

    private class Display(
        val locale: Locale = Locale.JAPANESE,
        val fontScale: Float = 1f,
        val widthDp: Int? = null,
        val dark: Boolean = false,
    )

    private fun ActivityScenario<MainActivity>.show(
        state: androidx.compose.runtime.MutableState<NavigationPlaybackState>,
        calls: Calls,
        display: Display = Display(),
        loadArtwork: suspend (VariantId, Int) -> Artwork = { _, _ -> Artwork.Placeholder(PlaceholderReason.NO_ARTWORK) },
    ): android.content.Context {
        val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
            setLocale(display.locale)
            fontScale = display.fontScale
        }
        val localized = instrumentation.targetContext.createConfigurationContext(configuration)
        onActivity { activity ->
            activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalContext provides localized,
                    LocalDensity provides Density(density, fontScale = display.fontScale),
                ) {
                    AudippTheme(mode = if (display.dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                        Box(if (display.widthDp != null) Modifier.width(display.widthDp.dp) else Modifier) {
                            AudippApp(
                                androidVersion = "fixture", themeState = ThemeSettingsState(),
                                sources = HomeSources.Loading, playback = state.value,
                                recentPlays = RecentPlays.Loading, registration = FolderRegistration.Idle,
                                onAddMusic = {}, onRetryScan = {}, onCancelScan = {}, onRetryHistory = {},
                                onTheme = {}, onRetryTheme = {}, onRetrySources = {}, onRetryPlayback = {},
                                onTogglePlayback = { calls.toggles += it },
                                onConfirmOutput = { calls.confirmations += it },
                                onSeek = { calls.seeks += it },
                                loadArtwork = loadArtwork,
                                openNowPlaying = false, onOpenNowPlayingHandled = {},
                            )
                        }
                    }
                }
            }
        }
        return localized
    }

    private fun launched(block: (ActivityScenario<MainActivity>) -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).use(block)

    @Test
    fun expandingTheMiniPlayerOpensNowPlayingWithoutAnyPlaybackRequestAndBackFocusesTheMini() = launched { scenario ->
        val calls = Calls()
        val state = mutableStateOf<NavigationPlaybackState>(paused)
        val text = scenario.show(state, calls)
        val open = text.getString(R.string.now_playing_open)

        robot.click("Fixture title")
        robot.await(text.getString(R.string.now_playing_collapse))
        robot.await("Fixture title")
        assertNull("The mini player must not be duplicated on the full player",
            robot.find { it.text?.toString() == text.getString(R.string.playback_artist_and_state, "Fixture artist", text.getString(R.string.playback_paused)) })
        assertTrue(calls.toggles.isEmpty() && calls.seeks.isEmpty() && calls.confirmations.isEmpty())

        robot.back()
        robot.await(text.getString(R.string.home))
        robot.waitUntil("$open regains focus") {
            robot.find { it.text?.toString() == "Fixture title" }
                ?.let { mini -> generateSequence(mini) { it.parent }.any { it.isFocused } } == true
        }
        assertTrue(calls.toggles.isEmpty())
    }

    @Test
    fun queueFromNowPlayingReturnsToNowPlayingAndThenCollapsesToTheViewedScreen() = launched { scenario ->
        val calls = Calls()
        val text = scenario.show(mutableStateOf<NavigationPlaybackState>(paused), calls)
        robot.click(text.getString(R.string.navigation_library))
        robot.click("Fixture title")
        robot.await(text.getString(R.string.now_playing_collapse))
        robot.click(text.getString(R.string.navigation_queue))
        robot.await(text.getString(R.string.navigation_queue_current))
        robot.back()
        robot.await(text.getString(R.string.now_playing_collapse))
        robot.click(text.getString(R.string.now_playing_collapse))
        robot.gone(text.getString(R.string.now_playing_collapse))
        robot.await("Fixture title")
    }

    @Test
    fun unknownDurationAndNonSeekableSourcesDisableSeekWithAReasonAndKeepPlayPause() = launched { scenario ->
        val calls = Calls()
        val state = mutableStateOf<NavigationPlaybackState>(paused)
        val text = scenario.show(state, calls)
        robot.click("Fixture title")
        val label = text.getString(R.string.now_playing_seek)
        val seekBar = robot.await("seek bar") { it.rangeInfo != null }
        assertTrue(seekBar.isEnabled)
        assertNotNull(robot.find(text.getString(R.string.now_playing_seek_value, "1:01", "4:05")))

        instrumentation.runOnMainSync { state.value = paused.copy(durationMs = null, positionMs = null) }
        robot.await(text.getString(R.string.now_playing_seek_unknown_duration))
        assertFalse(robot.await("seek bar") { it.rangeInfo != null }.isEnabled)
        assertNotNull(robot.find(text.getString(R.string.now_playing_seek_value, "--:--", "--:--")))

        instrumentation.runOnMainSync { state.value = paused.copy(seekCommandAvailable = false) }
        robot.await(text.getString(R.string.now_playing_seek_not_seekable))
        assertFalse(robot.await("seek bar") { it.rangeInfo != null }.isEnabled)
        assertNotNull(robot.find { it.contentDescription?.toString() == label })

        robot.click(text.getString(R.string.playback_resume))
        assertEquals(listOf(control), calls.toggles)
        assertTrue(calls.seeks.isEmpty())
    }

    @Test
    fun skipAndDetailsAreDisabledWithAVisibleReason() = launched { scenario ->
        val text = scenario.show(mutableStateOf<NavigationPlaybackState>(paused), Calls())
        robot.click("Fixture title")
        for (label in listOf(R.string.now_playing_previous, R.string.now_playing_next, R.string.now_playing_detail)) {
            val node = robot.reach(text.getString(label))
            assertFalse(text.getString(label), generateSequence(node) { it.parent }.any { it.isClickable && it.isEnabled })
        }
        robot.reach(text.getString(R.string.now_playing_skip_unavailable))
    }

    @Test
    fun sourceFormatProviderAndObservedOutputAreShownAsReportedNotInferred() = launched { scenario ->
        val state = mutableStateOf<NavigationPlaybackState>(paused)
        val text = scenario.show(state, Calls())
        robot.click("Fixture title")
        robot.reach(text.getString(R.string.now_playing_format, "FLAC · 24 bit · 96 kHz · 2 ch"))
        robot.reach(text.getString(R.string.now_playing_provider, text.getString(R.string.now_playing_provider_local)))
        robot.reach(text.getString(R.string.now_playing_output_label, text.getString(R.string.now_playing_output_usb)))
        // A sample-rate match alone never produces a bit-perfect claim.
        assertNull(robot.find { it.text?.toString()?.contains("Bit-perfect", ignoreCase = true) == true &&
            !it.text.toString().contains(text.getString(R.string.now_playing_output_note).substringBefore(".")) })

        instrumentation.runOnMainSync {
            state.value = paused.copy(sourceFormat = null, observedOutput = ObservedOutput.Unobserved)
        }
        robot.reach(text.getString(R.string.now_playing_format, text.getString(R.string.now_playing_format_unknown)))
        robot.reach(text.getString(R.string.now_playing_output_label, text.getString(R.string.now_playing_output_unobserved)))
    }

    @Test
    fun artworkPlaceholderShowsItsReasonAndARealImageShowsNone() = launched { scenario ->
        val state = mutableStateOf<NavigationPlaybackState>(paused)
        val artwork = mutableStateOf<Artwork>(Artwork.Placeholder(PlaceholderReason.EXPIRED))
        val text = scenario.show(state, Calls(), loadArtwork = { _, _ -> artwork.value })
        robot.click("Fixture title")
        robot.reach(text.getString(R.string.artwork_expired))

        // A new variant triggers a new load; the loaded image has no placeholder reason.
        artwork.value = Artwork.Image(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), ArtworkOrigin.EMBEDDED)
        instrumentation.runOnMainSync { state.value = paused.copy(variantId = VariantId("next")) }
        robot.gone(text.getString(R.string.artwork_expired))
        robot.await(text.getString(R.string.now_playing_artwork)) { it.contentDescription?.toString() == text.getString(R.string.now_playing_artwork) }
    }

    @Test
    fun japaneseOutputConfirmationInNowPlaying320dp200Percent() = verifyConfirmation(Locale.JAPANESE)

    @Test
    fun englishOutputConfirmationInNowPlaying320dp200Percent() = verifyConfirmation(Locale.ENGLISH)

    private fun verifyConfirmation(locale: Locale) = launched { scenario ->
        val calls = Calls()
        val text = scenario.show(
            mutableStateOf<NavigationPlaybackState>(paused.copy(outputConfirmation = confirmation, control = null)),
            calls, Display(locale, fontScale = 2f, widthDp = 320),
        )
        robot.click("Fixture title")
        val (message, action) = robot.reachTogether(
            text.getString(R.string.playback_output_changed), text.getString(R.string.playback_output_play),
        )
        assertTrue("Confirmation text ${robot.bounds(message)} and action ${robot.bounds(action)} overlap", robot.bounds(message).bottom <= robot.bounds(action).top)
        robot.reach(text.getString(R.string.now_playing_status_silent))
        robot.reach(text.getString(R.string.now_playing_seek_confirmation))
        assertNull("Ordinary playback stays unavailable during confirmation", robot.find(text.getString(R.string.playback_resume)))
        robot.click(text.getString(R.string.playback_output_play))
        assertEquals(listOf(confirmation), calls.confirmations)
        assertTrue(calls.toggles.isEmpty())
    }

    @Test
    fun landscapeAt200PercentKeepsEveryControlReachable() = launched { scenario ->
        val calls = Calls()
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        robot.waitUntil("Landscape") {
            var landscape = false
            scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            landscape
        }
        // Recreation replaces fixture content, so compose it only after the rotation has settled.
        val text = scenario.show(mutableStateOf<NavigationPlaybackState>(paused), calls, Display(fontScale = 2f))
        robot.click("Fixture title")
        for (label in listOf(R.string.now_playing_collapse, R.string.navigation_queue, R.string.now_playing_output)) {
            robot.reach(text.getString(label))
        }
        val resume = robot.reach(text.getString(R.string.playback_resume))
        assertTrue(resume.isEnabled)
        robot.click(text.getString(R.string.playback_resume))
        assertEquals(listOf(control), calls.toggles)
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test
    fun failedPlaybackInNowPlayingExplainsAndCollapses() = launched { scenario ->
        val state = mutableStateOf<NavigationPlaybackState>(paused)
        val text = scenario.show(state, Calls())
        robot.click("Fixture title")
        instrumentation.runOnMainSync { state.value = NavigationPlaybackState.Idle }
        robot.await(text.getString(R.string.now_playing_empty))
        robot.click(text.getString(R.string.now_playing_collapse))
        robot.await(text.getString(R.string.home))
    }

    // A previous scenario's window can still show the same title while it closes, so require the mini layout.
    private fun awaitMini(text: android.content.Context) = robot.waitUntil("mini player on the previous screen's replacement") {
        robot.find("Fixture title") != null && robot.find(text.getString(R.string.now_playing_open)) == null &&
            robot.find("Collapse") == null && robot.find("縮小") == null
    }

    /** Writes the screenshots that docs/evidence/issue-85 cites. Runs only when `evidenceDir` is passed. */
    @Test
    fun captureEvidenceScreenshots() {
        val dir = InstrumentationRegistry.getArguments().getString("evidenceDir")
        Assume.assumeTrue("evidenceDir is not set", dir != null)
        val out = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), dir!!).apply { mkdirs() }
        fun capture(name: String) {
            android.os.SystemClock.sleep(1_000)
            java.io.FileOutputStream(java.io.File(out, "$name.png")).use {
                requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val gradient = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 64) for (y in 0 until 64) setPixel(x, y, android.graphics.Color.rgb(x * 4, y * 4, 160))
        }
        val image = Artwork.Image(gradient, ArtworkOrigin.EMBEDDED)
        val cases = listOf(
            Triple("ja-light", Display(Locale.JAPANESE), paused),
            Triple("en-dark", Display(Locale.ENGLISH, dark = true), paused),
            Triple("ja-dark-320dp-200pct", Display(Locale.JAPANESE, 2f, 320, dark = true), paused),
            Triple("en-light-320dp-200pct", Display(Locale.ENGLISH, 2f, 320), paused),
        )
        for ((name, display, state) in cases) {
            launched { scenario ->
                val text = scenario.show(mutableStateOf<NavigationPlaybackState>(state), Calls(), display, loadArtwork = { _, _ -> image })
                awaitMini(text)
                capture("mini-$name")
                robot.click("Fixture title")
                robot.await(text.getString(R.string.now_playing_collapse))
                capture("now-$name")
                if (display.widthDp == 320) {
                    robot.reach(text.getString(R.string.now_playing_skip_unavailable))
                    capture("now-$name-scrolled")
                }
            }
        }
        val confirming = paused.copy(outputConfirmation = confirmation, control = null)
        val unavailable = mapOf(
            "ja-light-unknown-duration" to (paused.copy(durationMs = null, positionMs = null) to Display(Locale.JAPANESE)),
            "en-light-not-seekable" to (paused.copy(seekCommandAvailable = false) to Display(Locale.ENGLISH)),
            "ja-light-confirmation" to (confirming to Display(Locale.JAPANESE)),
            "ja-light-confirmation-320dp-200pct" to (confirming to Display(Locale.JAPANESE, 2f, 320)),
            "en-dark-confirmation-320dp-200pct" to (confirming to Display(Locale.ENGLISH, 2f, 320, dark = true)),
            "en-light-unobserved-output-placeholder" to
                (paused.copy(sourceFormat = null, observedOutput = ObservedOutput.Unobserved) to Display(Locale.ENGLISH)),
        )
        for ((name, stateAndDisplay) in unavailable) {
            launched { scenario ->
                val (state, display) = stateAndDisplay
                val text = scenario.show(mutableStateOf<NavigationPlaybackState>(state), Calls(), display,
                    loadArtwork = { _, _ -> Artwork.Placeholder(PlaceholderReason.EXPIRED) })
                awaitMini(text)
                robot.click("Fixture title")
                robot.await(text.getString(R.string.now_playing_collapse))
                capture("now-$name")
            }
        }
        for ((name, display) in listOf("en-light-landscape" to Display(Locale.ENGLISH),
            "ja-dark-landscape-200pct" to Display(Locale.JAPANESE, 2f, dark = true))) {
            launched { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                robot.waitUntil("Landscape") {
                    var landscape = false
                    scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    landscape
                }
                val text = scenario.show(mutableStateOf<NavigationPlaybackState>(paused), Calls(), display)
                awaitMini(text)
                capture("mini-$name")
                robot.click("Fixture title")
                robot.await(text.getString(R.string.now_playing_collapse))
                capture("now-$name")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
    }
}
