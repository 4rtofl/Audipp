package com.audipp.ui.navigation

import com.audipp.ui.NavigationDestination
import com.audipp.ui.NavigationPage
import com.audipp.ui.NowPlayingLayer
import com.audipp.ui.NavigationTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationDestinationTest {
    @Test
    fun backAtATabRootReturnsToHomeWithoutRevisitingOtherTabs() {
        val home = NavigationDestination(NavigationTab.HOME, null)
        val search = home.select(NavigationTab.LIBRARY).select(NavigationTab.SEARCH)

        assertEquals(home, search.back())
        assertNull(home.back())
    }

    @Test
    fun backClosesTheCurrentPageAndReselectionReturnsToTheTabRoot() {
        val search = NavigationDestination(NavigationTab.SEARCH, null)
        val settings = search.copy(page = NavigationPage.SETTINGS)
        val appearance = search.copy(page = NavigationPage.APPEARANCE)
        val queue = search.copy(page = NavigationPage.QUEUE)

        assertEquals(settings, appearance.back())
        assertEquals(search, settings.back())
        assertEquals(search, queue.back())
        assertEquals(search, appearance.select(NavigationTab.SEARCH))
    }

    @Test
    fun sourceManagementReturnsToTheLibrary() {
        val library = NavigationDestination(NavigationTab.LIBRARY, null)
        val source = library.copy(page = NavigationPage.SOURCE)

        assertEquals(library, source.back())
        assertEquals(library, source.select(NavigationTab.LIBRARY))
        assertEquals(source, source.openNowPlaying().back())
    }

    @Test
    fun expandingTheMiniPlayerLayersNowPlayingOverTheCurrentScreenAndBackReturnsToIt() {
        val queue = NavigationDestination(NavigationTab.SEARCH, NavigationPage.QUEUE)
        val tabRoot = NavigationDestination(NavigationTab.LIBRARY, null)

        for (viewing in listOf(queue, tabRoot, NavigationDestination(NavigationTab.HOME, NavigationPage.SETTINGS))) {
            val now = viewing.openNowPlaying()
            assertEquals(NowPlayingLayer.PLAYER, now.nowPlaying)
            assertEquals(viewing, now.back())
        }
    }

    @Test
    fun queueOpenedFromNowPlayingReturnsToNowPlayingThenToTheViewedScreen() {
        val search = NavigationDestination(NavigationTab.SEARCH, null)
        val queueOverNow = search.openNowPlaying().copy(nowPlaying = NowPlayingLayer.QUEUE)

        assertEquals(search.openNowPlaying(), queueOverNow.back())
        assertEquals(search, queueOverNow.back()?.back())
    }
}
