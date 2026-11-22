package com.audipp.ui.nowplaying

import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Drives the real window through the accessibility tree, as TalkBack would. */
internal class AccessibilityRobot {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun back() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

    fun click(text: String) {
        val node = reach("enabled $text action") { it.text?.toString() == text && clickable(it) != null }
        assertTrue("Could not click $text", clickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }

    fun await(text: String) = await(text) { it.text?.toString() == text }

    fun await(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitUntil("Waiting for $description") {
            result = find(predicate)
            result != null
        }
        return requireNotNull(result)
    }

    fun find(text: String) = find { it.text?.toString() == text }

    fun find(predicate: (AccessibilityNodeInfo) -> Boolean) =
        findNode(instrumentation.uiAutomation.rootInActiveWindow, predicate)

    fun gone(text: String) = waitUntil("$text disappears") { find(text) == null }

    /** Finds a node that may be off screen by scrolling every scrollable container to the end and back. */
    fun reach(text: String) = reach(text) { it.text?.toString() == text }

    /** Finds nodes that are on screen at the same time, so their bounds can be compared. */
    fun reachTogether(first: String, second: String): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
        var result: Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? = null
        var attempts = 0
        waitUntil("Reaching $first and $second together") {
            val a = find(first)
            val b = find(second)
            if (a != null && b != null) {
                result = a to b
            } else {
                val action = if (attempts++ % 20 < 10) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                scrollables().forEach { it.performAction(action) }
            }
            result != null
        }
        return requireNotNull(result)
    }

    fun reach(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        var attempts = 0
        waitUntil("Reaching $description") {
            result = find(predicate)
            if (result == null) {
                val action = if (attempts++ % 20 < 10) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                scrollables().forEach { it.performAction(action) }
            }
            result != null
        }
        return requireNotNull(result)
    }

    fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    fun waitUntil(description: String, condition: () -> Boolean) {
        val start = SystemClock.uptimeMillis()
        var early: List<String>? = null
        while (SystemClock.uptimeMillis() < start + 20_000) {
            if (condition()) return
            if (early == null && SystemClock.uptimeMillis() > start + 2_000) early = visibleTexts()
            SystemClock.sleep(100)
        }
        throw AssertionError("$description; after 2s: $early; at timeout: ${visibleTexts()}")
    }

    private fun visibleTexts(): List<String> {
        val texts = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?) {
            node ?: return
            if (node.isVisibleToUser) {
                (node.text ?: node.contentDescription)?.let {
                    texts += "$it${if (node.isEnabled) "" else "(disabled)"}${if (node.isFocused) "(focused)" else ""}"
                }
                if (node.isFocused && node.text == null) texts += "[focused ${node.className} clickable=${node.isClickable}]"
            }
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return texts
    }

    private fun scrollables(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            node ?: return
            if (node.isScrollable && node.isVisibleToUser) found += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return found
    }

    private fun findNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (index in 0 until node.childCount) findNode(node.getChild(index), predicate)?.let { return it }
        return null
    }
}
