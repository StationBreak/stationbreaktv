// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag

/*
 * A key from `sendKeyDownUpSync` goes to whichever window the system has given input focus, not to the test's
 * content. Compose can be idle, and a node can already report Focused, while its window has not been shown yet: on the
 * slow API 25 TV emulator "Displayed" came up to a second after the activity resumed, the launcher kept input focus
 * until then, and a key sent in that gap went to the launcher and was lost (#473). These waits hold a key back until
 * a window of the test has input focus.
 *
 * The same file is in feature/onboarding and app androidTest, as TypeWhenReady is: there is no shared Android test
 * module (core:testing is common-only and core:domain test fixtures are JVM-only). Keep the two copies in step.
 */

private const val WINDOW_FOCUS_TIMEOUT_MS = 10_000L

/** Waits until a window showing this test's content has input focus, so the next key event reaches the test. */
internal fun ComposeTestRule.awaitWindowFocus(timeoutMs: Long = WINDOW_FOCUS_TIMEOUT_MS) {
    waitUntil("a window of the test has input focus", timeoutMs) {
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false)
        roots.any { windowHasFocus(it) }
    }
}

/**
 * Waits until the node tagged [tag] is focused and its own window has input focus: the state in which the next key
 * event reaches that node. A dialog is a window of its own, so this also waits for a dialog's window, not just the
 * activity's.
 *
 * It needs the node's own window to have input focus, so waiting on a node in the activity's window while a dialog
 * is open times out: wait on the dialog's focused node then, and on the screen's only once the dialog has closed.
 */
internal fun ComposeTestRule.awaitFocusedForKeys(
    tag: String,
    timeoutMs: Long,
) {
    val node = onNodeWithTag(tag)
    waitUntil("$tag is focused and its window has input focus", timeoutMs) {
        runCatching { node.assertIsFocused() }.isSuccess &&
            runCatching { windowHasFocus(node.fetchSemanticsNode()) }.getOrDefault(false)
    }
}

private fun ComposeTestRule.windowHasFocus(node: SemanticsNode): Boolean {
    val view = (node.root as? ViewRootForTest)?.view ?: return false
    return runOnUiThread { view.hasWindowFocus() }
}
