package com.audipp.ui.navigation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.StringRes
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audipp.MainActivity
import com.audipp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationOnboardingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun safCancellationAndMissingReadGrantLeaveHomeAvailable() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val addMusic = context.getString(R.string.music_add)
            pickerResult(Activity.RESULT_CANCELED, null) { click(addMusic) }
            awaitNode(addMusic)
            assertTrue(find(context.getString(R.string.music_permission_denied)) == null)

            val tree = DocumentsContract.buildTreeDocumentUri("com.audipp.fixture", "music")
            val denied = Intent().setData(tree).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            pickerResult(Activity.RESULT_OK, denied) { click(addMusic) }
            awaitNode(R.string.music_permission_denied)
            awaitNode(addMusic)
            click(R.string.navigation_library)
            awaitHeading(R.string.navigation_library)
            back()
            awaitNode(R.string.recent_plays)
        }
    }

    @Test
    fun rotationAndBackRetainTheSearchTabAndQuery() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitNode(R.string.local_sources)
            click(R.string.navigation_search)
            val query = "Audipp fixture"
            val edit = awaitNode { it.isEditable }
            assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }))
            awaitNode(query)

            var orientation = Configuration.ORIENTATION_UNDEFINED
            scenario.onActivity {
                orientation = it.resources.configuration.orientation
                it.requestedOrientation = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
            waitUntil("Activity rotates") {
                var rotated = false
                scenario.onActivity { rotated = it.resources.configuration.orientation != orientation }
                rotated
            }
            awaitNode(query)
            click(R.string.navigation_queue)
            awaitNode(R.string.navigation_queue_empty)
            back()
            awaitNode(query)
            click(R.string.settings)
            click(R.string.appearance)
            awaitHeading(R.string.appearance)
            click(R.string.navigation_search)
            awaitNode(query)
            back()
            awaitHeading(R.string.home)
            back()
            waitUntil("Home back leaves the activity") {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName != context.packageName
            }
        }
    }

    private fun pickerResult(result: Int, data: Intent?, open: () -> Unit) {
        val monitor = instrumentation.addMonitor(
            IntentFilter(Intent.ACTION_OPEN_DOCUMENT_TREE), Instrumentation.ActivityResult(result, data), true,
        )
        try {
            open()
            waitUntil("SAF folder picker launches") { monitor.hits == 1 }
            assertEquals(1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun back() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

    private fun click(@StringRes label: Int) = click(context.getString(label))

    private fun click(text: String) {
        val node = awaitNode("enabled $text action") { it.text?.toString() == text && clickable(it) != null }
        assertTrue("Could not click $text", clickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }

    private fun awaitNode(@StringRes label: Int) = awaitNode(context.getString(label))

    private fun awaitNode(text: String) =
        awaitNode(text) { it.text?.toString() == text }

    private fun awaitHeading(@StringRes label: Int) {
        val text = context.getString(label)
        awaitNode("$text screen") { it.text?.toString() == text && clickable(it) == null }
    }

    private fun awaitNode(
        description: String = "matching accessibility node",
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitUntil("Waiting for $description") {
            result = findNode(instrumentation.uiAutomation.rootInActiveWindow, predicate)
            result != null
        }
        return requireNotNull(result)
    }

    private fun find(text: String) =
        findNode(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text }

    private fun findNode(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (index in 0 until node.childCount) {
            findNode(node.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError(description)
    }
}
