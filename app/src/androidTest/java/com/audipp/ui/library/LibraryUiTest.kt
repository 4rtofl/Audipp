package com.audipp.ui.library

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.MediaController
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.AudippApplication
import com.audipp.MainActivity
import com.audipp.R
import com.audipp.data.db.AlbumEntity
import com.audipp.data.db.LibraryBatch
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.db.LibraryField
import com.audipp.data.db.LibraryGroup
import com.audipp.data.db.LibraryItem
import com.audipp.data.db.LocalScanRoot
import com.audipp.data.db.LocalSourceSummary
import com.audipp.data.db.SourceEntity
import com.audipp.data.db.TrackEntity
import com.audipp.data.db.VariantEntity
import com.audipp.data.db.VariantLocalMetadata
import com.audipp.data.local.Artwork
import com.audipp.data.local.LibraryScanIssue
import com.audipp.data.local.LibraryScanProgress
import com.audipp.data.local.LibraryScanResult
import com.audipp.data.local.LibraryScanStatus
import com.audipp.domain.model.ArtworkOrigin
import com.audipp.domain.model.Availability
import com.audipp.domain.model.ErrorDomain
import com.audipp.domain.model.PlaceholderReason
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.ThemeMode
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId
import com.audipp.playback.PlaybackConnection
import com.audipp.ui.AudippApp
import com.audipp.ui.navigation.FolderRegistration
import com.audipp.ui.navigation.HomeSources
import com.audipp.ui.navigation.NavigationPlaybackState
import com.audipp.ui.navigation.NavigationViewModel
import com.audipp.ui.navigation.PlayRequest
import com.audipp.ui.navigation.RecentPlays
import com.audipp.ui.navigation.SourceFolder
import com.audipp.ui.nowplaying.AccessibilityRobot
import com.audipp.ui.settings.ThemeSettingsState
import com.audipp.ui.theme.AudippTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val robot = AccessibilityRobot()

    private class Display(
        val locale: Locale = Locale.JAPANESE,
        val fontScale: Float = 1f,
        val widthDp: Int? = null,
        val dark: Boolean = false,
    )

    private val previous = NavigationPlaybackState.Current(
        PlaybackPhase.PLAYING, "Previous title", "Previous artist", null,
        variantId = VariantId("previous"), entryId = QueueEntryId("previous-entry"),
    )

    /** Composes the app over the given library state; the playback state is the previous song unless chosen. */
    private fun ActivityScenario<MainActivity>.show(
        library: @androidx.compose.runtime.Composable () -> LibraryUi,
        actions: LibraryActions,
        display: Display = Display(),
        sources: HomeSources = ready(),
        registration: FolderRegistration = FolderRegistration.Idle,
        playback: NavigationPlaybackState = previous,
        loadArtwork: suspend (VariantId, Int) -> Artwork = { _, _ -> Artwork.Placeholder(PlaceholderReason.NO_ARTWORK) },
    ): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(display.locale)
            fontScale = display.fontScale
        }
        val localized = context.createConfigurationContext(configuration)
        onActivity { activity ->
            activity.setContent {
                val density = LocalDensity.current.density
                val state = library()
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalContext provides localized,
                    LocalDensity provides Density(density, fontScale = display.fontScale),
                ) {
                    AudippTheme(mode = if (display.dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                        Box(if (display.widthDp != null) Modifier.width(display.widthDp.dp) else Modifier) {
                            AudippApp(
                                androidVersion = "fixture", themeState = ThemeSettingsState(),
                                sources = sources, playback = playback.withSelection(state.selection),
                                recentPlays = RecentPlays.Ready(emptyList()), registration = registration,
                                onAddMusic = { actions.addMusic() }, onRetryScan = {}, onCancelScan = {}, onRetryHistory = {},
                                onTheme = {}, onRetryTheme = {}, onRetrySources = {}, onRetryPlayback = {},
                                onTogglePlayback = {}, onConfirmOutput = {}, onSeek = {},
                                loadArtwork = loadArtwork,
                                openNowPlaying = false, onOpenNowPlayingHandled = {},
                                library = state, libraryActions = actions,
                            )
                        }
                    }
                }
            }
        }
        return localized
    }

    private fun ready(registered: Long = 3_000, accessLost: Boolean = false, folders: List<SourceFolder> = emptyList()) =
        HomeSources.Ready(LocalSourceSummary(registered, 0, 0), hasFolders = true, accessLost = accessLost,
            retryRootId = null, folders = folders)

    private fun launched(block: (ActivityScenario<MainActivity>) -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).use(block)

    private fun memoryLibrary(tracks: Int): LibraryDatabase =
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().also { db ->
            runBlocking {
                val ids = (0 until tracks).map { "%05d".format(Locale.ROOT, it) }
                db.libraryDao().save(LibraryBatch(
                    sources = ids.map { SourceEntity("s-$it", SourceKind.LOCAL, localUri = "content://fixture/$it",
                        availability = Availability.PLAYABLE, updatedAt = 0) },
                    tracks = ids.map { TrackEntity("t-$it") },
                    albums = ids.map { AlbumEntity("album-$it", "Album $it") },
                    variants = ids.map { VariantEntity("v-$it", "t-$it", "s-$it", albumId = "album-$it", codec = "FLAC",
                        localMetadata = VariantLocalMetadata(title = "Track $it", artist = "Artist ${it.take(3)}")) },
                ))
            }
        }

    @Test
    fun aLargeLibraryScrollsByWindowsAndAChosenSongGoesToThePlayEntryAndCanBeRetried() = launched { scenario ->
        val db = memoryLibrary(3_000)
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val sent = CopyOnWriteArrayList<TrackVariant>()
        var answer: PlayRequest = PlayRequest.Rejected(ErrorDomain.FORMAT_UNSUPPORTED)
        try {
            val browser = LibraryBrowser(db.libraryQueryDao(), scope, SharingStarted.Eagerly)
            val selection = LibrarySelection(scope, db.libraryDao()::trackVariant,
                send = { _, variant -> sent += variant; answer },
                playback = MutableStateFlow(previous), newEntryId = { QueueEntryId("entry-${sent.size}") })
            val state = combine(browser.browse, browser.songs, browser.groups, selection.state, selection.accepted) {
                    b, s, g, sel, accepted ->
                LibraryUi(b, s, g, sel, acceptedEntry = accepted)
            }.stateIn(scope, SharingStarted.Eagerly, LibraryUi())
            val actions = object : LibraryActions {
                override fun showFrom(firstVisible: Int) = browser.showFrom(firstVisible)
                override fun select(item: LibraryItem) = selection.select(item)
                override fun retrySelection() = selection.retry()
            }
            val text = scenario.show({ state.collectAsState().value }, actions)
            robot.click(text.getString(R.string.navigation_library))
            robot.await("Track 00000")
            robot.await("Previous title")

            // Scroll until a row beyond the first window of 100 is loaded and visible.
            val pastWindow: (AccessibilityNodeInfo) -> Boolean =
                { it.text?.toString()?.removePrefix("Track ")?.toIntOrNull()?.let { n -> n >= 150 } == true }
            robot.waitUntil("a row past the first window is loaded") {
                robot.find(pastWindow) != null ||
                    robot.find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD).let { false }
            }
            android.os.SystemClock.sleep(1_000)
            val title = requireNotNull(robot.find(pastWindow)).text.toString()
            robot.click(title)
            robot.await(text.getString(R.string.playback_format_unsupported))
            assertEquals(listOf("v-" + title.removePrefix("Track ")), sent.map { it.variantId.value })
            assertEquals(Availability.PLAYABLE, sent.single().source.availability)

            answer = PlayRequest.Accepted
            robot.click(text.getString(R.string.retry))
            robot.await(text.getString(R.string.now_playing_collapse))
            robot.await(title)
            robot.await(text.getString(R.string.playback_loading))
            assertNull("The previous song's title stays hidden while the chosen one prepares", robot.find("Previous title"))
            assertEquals(2, sent.size)
            assertEquals(sent[0], sent[1])
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun closingAnOpenedAlbumReturnsToTheSameRowAndKeyword() = launched { scenario ->
        val db = memoryLibrary(600)
        val scope = CoroutineScope(Dispatchers.Main + Job())
        try {
            val browser = LibraryBrowser(db.libraryQueryDao(), scope, SharingStarted.Eagerly)
            val state = combine(browser.browse, browser.songs, browser.groups) { b, s, g -> LibraryUi(b, s, g) }
                .stateIn(scope, SharingStarted.Eagerly, LibraryUi())
            val actions = object : LibraryActions {
                override fun show(view: LibraryView) = browser.show(view)
                override fun open(group: LibraryGroup) = browser.open(group)
                override fun close() = browser.close()
                override fun search(keyword: String) = browser.search(keyword)
                override fun showFrom(firstVisible: Int) = browser.showFrom(firstVisible)
            }
            val text = scenario.show({ state.collectAsState().value }, actions)
            robot.click(text.getString(R.string.navigation_library))
            robot.await("Track 00000")
            robot.click(text.getString(R.string.library_view_albums))
            robot.await("Album 00000")
            val search = robot.await("search field") { it.isEditable }
            assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Album")
            }))
            robot.await("Album 00000")

            val farAlbum: (AccessibilityNodeInfo) -> Boolean =
                { it.text?.toString()?.removePrefix("Album ")?.toIntOrNull()?.let { n -> n >= 150 } == true }
            robot.waitUntil("an album past the first window is visible") {
                robot.find(farAlbum) != null ||
                    robot.find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD).let { false }
            }
            // A tap while the list still flings only stops it, so let the scroll settle first. A new window can replace
            // the row nodes between finding and tapping, so find again until the tap lands.
            android.os.SystemClock.sleep(1_000)
            var title = ""
            robot.waitUntil("an album row is tapped") {
                val node = robot.find { farAlbum(it) && robot.bounds(it).top > 600 } ?: return@waitUntil false
                title = node.text.toString()
                robot.clickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            robot.await(text.getString(R.string.library_back_to_list))
            val track = "Track " + title.removePrefix("Album ")
            robot.waitUntil("$track comes into view") {
                findFresh(track) || robot.find { it.isScrollable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD).let { android.os.SystemClock.sleep(500); false }
            }

            robot.back()
            robot.gone(text.getString(R.string.library_back_to_list))
            // The rows around the opened one are in view again without scrolling, under the same keyword. The opened row
            // itself may sit partly under the top edge, where accessibility does not count it as visible.
            val opened = title.removePrefix("Album ").toInt()
            robot.await("rows around $title") {
                it.text?.toString()?.removePrefix("Album ")?.toIntOrNull()?.let { n -> n in opened - 2..opened + 2 } == true
            }
            assertEquals("Album", browser.browse.value.keyword)
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun theViewSurvivesRotationAndLostAccessOffersPickingTheFolderAgain() {
        val app = context.applicationContext as AudippApplication
        val root = LocalScanRoot("library-ui-test-root", "content://com.audipp.fixture/tree/lost")
        runBlocking { app.libraryDatabase.safScanDao().register(root) }
        try {
            launched { scenario ->
                robot.click(context.getString(R.string.navigation_library))
                robot.await(context.getString(R.string.music_access_lost))
                robot.click(context.getString(R.string.library_view_albums))
                robot.waitUntil("Albums is shown as chosen") { albumsChosen() }

                scenario.rotate()
                robot.await(context.getString(R.string.music_access_lost))
                assertTrue("The chosen view is kept across rotation", albumsChosen())
                assertEquals(LibraryView.ALBUMS, scenario.navigationModel().library.browse.value.view)

                // Picking again opens the system picker; cancelling it keeps the library and the way to retry.
                val monitor = Instrumentation.ActivityMonitor(
                    IntentFilter(Intent.ACTION_OPEN_DOCUMENT_TREE), Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true,
                )
                instrumentation.addMonitor(monitor)
                try {
                    robot.click(context.getString(R.string.music_choose_again))
                    robot.waitUntil("The folder picker is requested") { monitor.hits == 1 }
                } finally {
                    instrumentation.removeMonitor(monitor)
                }
                robot.await(context.getString(R.string.music_access_lost))
                robot.await(context.getString(R.string.music_choose_again))

                robot.click(context.getString(R.string.library_manage_sources))
                robot.reach(context.getString(R.string.source_folders))
                robot.reach("lost")
                robot.reach(context.getString(R.string.source_status_no_access))
                robot.back()
                robot.await(context.getString(R.string.music_access_lost))
                assertTrue("Returning from the source page keeps the view", albumsChosen())
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        } finally {
            app.libraryDatabase.openHelper.writableDatabase.execSQL("DELETE FROM local_scan_roots WHERE root_id = ?", arrayOf(root.id))
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun choosingALibrarySongPlaysItThroughTheServiceAndNowPlayingSurvivesRecreation() {
        val app = context.applicationContext as AudippApplication
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "Audipp library test.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ))
        val title = "Audipp library fixture"
        try {
            writeSilentWav(uri)
            runBlocking {
                app.libraryDatabase.libraryDao().save(LibraryBatch(
                    sources = listOf(SourceEntity("library-ui-source", SourceKind.LOCAL, localUri = uri.toString(),
                        availability = Availability.PLAYABLE, updatedAt = 0)),
                    tracks = listOf(TrackEntity("library-ui-track")),
                    variants = listOf(VariantEntity("library-ui-variant", "library-ui-track", "library-ui-source",
                        codec = "WAV", localMetadata = VariantLocalMetadata(container = "WAVE", title = title))),
                ))
            }
            launched { scenario ->
                val model = scenario.navigationModel()
                robot.click(context.getString(R.string.navigation_library))
                val search = robot.await("search field") { it.isEditable }
                assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "library fixture")
                }))
                robot.click(title)
                robot.await(context.getString(R.string.now_playing_collapse))
                val playing = runBlocking {
                    withTimeout(15_000) {
                        model.playback.filterIsInstance<NavigationPlaybackState.Current>()
                            .first { it.variantId == VariantId("library-ui-variant") && it.phase == PlaybackPhase.PLAYING }
                    }
                }
                robot.waitUntil("The selection clears once the service reports it") { model.selection.value == TrackSelection.None }

                scenario.recreate()
                robot.await(context.getString(R.string.now_playing_collapse))
                val again = runBlocking {
                    withTimeout(10_000) { model.playback.filterIsInstance<NavigationPlaybackState.Current>().first() }
                }
                assertEquals(playing.entryId, again.entryId)
                robot.back()
                robot.await(title)
                runBlocking {
                    val connection = withContext(Dispatchers.Main) { PlaybackConnection.connect(context, object : MediaController.Listener {}) }
                    val controller = connection.get(10, TimeUnit.SECONDS)
                    withContext(Dispatchers.Main) {
                        controller.stop()
                        MediaController.releaseFuture(connection)
                    }
                }
            }
        } finally {
            app.libraryDatabase.openHelper.writableDatabase.apply {
                execSQL("DELETE FROM track_variants WHERE variant_id = 'library-ui-variant'")
                execSQL("DELETE FROM tracks WHERE track_id = 'library-ui-track'")
                execSQL("DELETE FROM sources WHERE source_id = 'library-ui-source'")
            }
            context.contentResolver.delete(uri, null, null)
        }
    }

    /** Writes the screenshots that docs/evidence/issue-84 cites. Runs only when `evidenceDir` is passed. */
    @Test
    fun captureEvidenceScreenshots() {
        val dir = InstrumentationRegistry.getArguments().getString("evidenceDir")
        Assume.assumeTrue("evidenceDir is not set", dir != null)
        val out = java.io.File(context.getExternalFilesDir(null), dir!!).apply { mkdirs() }
        fun capture(name: String) {
            android.os.SystemClock.sleep(1_000)
            java.io.FileOutputStream(java.io.File(out, "$name.png")).use {
                requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val gradient = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 64) for (y in 0 until 64) setPixel(x, y, android.graphics.Color.rgb(x * 4, 90, y * 4))
        }
        val art: suspend (VariantId, Int) -> Artwork = { id, _ ->
            if (id.value == "art") Artwork.Image(gradient, ArtworkOrigin.EMBEDDED) else Artwork.Placeholder(PlaceholderReason.NO_ARTWORK)
        }
        val long = "とても長い曲名が続いて一行には収まらないときの表示を確かめるための曲名 Extended Version"
        val songs = LibraryList.Ready(3, 0, listOf(
            LibraryItem("art", "t1", "a1", "Fixture title", "Fixture album", "Fixture artist", null, 2024, "FLAC"),
            LibraryItem("long", "t2", null, long, null, "Long Artist Name With Several Collaborators feat. Others", null, null, "WAVE"),
            LibraryItem("unknown", "t3", null, null, null, null, null, null, null),
        ))
        val albums = LibraryList.Ready(3, 0, listOf(
            LibraryGroup("a1", null, "Fixture album", "Fixture artist", 12, "art"),
            LibraryGroup("a2", null, "Fixture album", null, 3, "none"),
            LibraryGroup(null, null, null, null, 4, "unknown"),
        ))
        val base = LibraryUi(songs = songs, groups = albums)
        val cases = listOf(
            "songs-ja-light" to Display(Locale.JAPANESE),
            "songs-en-dark" to Display(Locale.ENGLISH, dark = true),
            "songs-ja-dark-320dp-200pct" to Display(Locale.JAPANESE, 2f, 320, dark = true),
            "songs-en-light-320dp-200pct" to Display(Locale.ENGLISH, 2f, 320),
        )
        for ((name, display) in cases) launched { scenario ->
            val text = scenario.show({ base }, object : LibraryActions {}, display, loadArtwork = art)
            robot.click(text.getString(R.string.navigation_library))
            robot.await(text.getString(R.string.library_manage_sources))
            capture(name)
            if (display.widthDp != null) {
                robot.reach(long)
                capture("$name-rows")
            }
        }
        val albumsUi = base.copy(browse = LibraryBrowse(view = LibraryView.ALBUMS))
        for ((name, display) in listOf("albums-ja-light" to Display(Locale.JAPANESE), "albums-en-dark" to Display(Locale.ENGLISH, dark = true))) {
            launched { scenario ->
                val text = scenario.show({ albumsUi }, object : LibraryActions {}, display, loadArtwork = art)
                robot.click(text.getString(R.string.navigation_library))
                robot.reach(text.getString(R.string.library_unclassified))
                capture(name)
            }
        }
        val opened = base.copy(browse = LibraryBrowse(view = LibraryView.ALBUMS, opened = albums.rows[0], sort = LibraryField.TRACK_NUMBER))
        launched { scenario ->
            val text = scenario.show({ opened }, object : LibraryActions {}, Display(Locale.ENGLISH), loadArtwork = art)
            robot.click(text.getString(R.string.navigation_library))
            robot.await(text.getString(R.string.library_back_to_list))
            capture("album-open-en-light")
        }
        for ((name, display) in listOf("songs-en-light-landscape" to Display(Locale.ENGLISH),
            "songs-ja-dark-landscape-200pct" to Display(Locale.JAPANESE, 2f, dark = true))) {
            launched { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                robot.waitUntil("Landscape") {
                    var landscape = false
                    scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    landscape
                }
                val text = scenario.show({ base }, object : LibraryActions {}, display, loadArtwork = art)
                robot.click(text.getString(R.string.navigation_library))
                robot.await(text.getString(R.string.library_manage_sources))
                capture(name)
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
        // States: loading, failed choice, access lost, empty library.
        val failed = base.copy(selection = TrackSelection.Failed(songs.rows[0], SelectionFailure.FORMAT_UNSUPPORTED))
        val states = listOf(
            Triple("state-loading-ja-light", base.copy(songs = LibraryList.Loading), ready()),
            Triple("state-selection-failed-ja-light", failed, ready()),
            Triple("state-access-lost-ja-light", base, ready(accessLost = true)),
            Triple("state-empty-en-dark", base.copy(songs = LibraryList.Ready(0, 0, emptyList())), ready(registered = 0)),
        )
        for ((name, ui, sources) in states) launched { scenario ->
            val display = if (name.endsWith("en-dark")) Display(Locale.ENGLISH, dark = true) else Display()
            val text = scenario.show({ ui }, object : LibraryActions {}, display, sources = sources, playback = NavigationPlaybackState.Idle)
            robot.click(text.getString(R.string.navigation_library))
            robot.await(text.getString(R.string.library_manage_sources))
            capture(name)
        }
        // Registration progress page while scanning and after a partial scan.
        val folders = listOf(
            SourceFolder("r1", "primary:Music", LibraryScanStatus.PARTIAL, granted = true),
            SourceFolder("r2", "primary:Old music", LibraryScanStatus.COMPLETE, granted = false),
        )
        val partial = LibraryScanResult("scan", LibraryScanStatus.PARTIAL,
            LibraryScanProgress(visited = 1_204, directories = 37, indexed = 1_102, updated = 64, skipped = 31, failed = 7), 2,
            setOf(LibraryScanIssue.CORRUPT_AUDIO, LibraryScanIssue.TIMEOUT))
        val pages = listOf(
            Triple("source-scanning-ja-light", FolderRegistration.Scanning(LibraryScanProgress(visited = 420, directories = 12, indexed = 380, failed = 2)), Display()),
            Triple("source-partial-en-dark", FolderRegistration.ScanFailed("r1"), Display(Locale.ENGLISH, dark = true)),
            Triple("source-partial-ja-light-320dp-200pct", FolderRegistration.ScanFailed("r1"), Display(Locale.JAPANESE, 2f, 320)),
        )
        for ((name, registration, display) in pages) launched { scenario ->
            val text = scenario.show({ base.copy(lastScan = partial) }, object : LibraryActions {}, display,
                sources = ready(accessLost = true, folders = folders), registration = registration, playback = NavigationPlaybackState.Idle)
            robot.click(text.getString(R.string.navigation_library))
            robot.click(text.getString(R.string.library_manage_sources))
            robot.await(text.getString(R.string.source_manage))
            capture(name)
            robot.reach(text.getString(R.string.source_folders))
            capture("$name-folders")
        }
    }

    /** Walks the window refreshing every node, so a list swapped in place is not read from stale cached nodes. */
    private fun findFresh(text: String): Boolean {
        fun visit(node: AccessibilityNodeInfo?): Boolean {
            node ?: return false
            node.refresh()
            if (node.text?.toString() == text && node.isVisibleToUser) return true
            return (0 until node.childCount).any { visit(node.getChild(it)) }
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun albumsChosen(): Boolean {
        val chip = robot.reach(context.getString(R.string.library_view_albums))
        return generateSequence(chip) { it.parent }.take(3).any { it.isChecked || it.isSelected }
    }

    private fun ActivityScenario<MainActivity>.rotate() {
        var orientation = Configuration.ORIENTATION_UNDEFINED
        onActivity {
            orientation = it.resources.configuration.orientation
            it.requestedOrientation = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        robot.waitUntil("Activity rotates") {
            var rotated = false
            onActivity { rotated = it.resources.configuration.orientation != orientation }
            rotated
        }
    }

    private fun ActivityScenario<MainActivity>.navigationModel(): NavigationViewModel {
        lateinit var model: NavigationViewModel
        onActivity { model = ViewModelProvider(it)[NavigationViewModel::class.java] }
        return model
    }

    private fun writeSilentWav(uri: Uri) {
        val sampleRate = 8_000
        val dataBytes = 30 * sampleRate * 2
        val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataBytes)
        }
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(wav.array()) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
    }
}
