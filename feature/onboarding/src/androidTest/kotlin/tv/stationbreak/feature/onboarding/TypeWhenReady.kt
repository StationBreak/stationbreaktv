// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput

/**
 * Types [text] into the field tagged [tag] and waits until the field holds it, trying again if it does not.
 *
 * On a cold emulator the first text input can reach the IME before its connection is active ("Failed to perform text
 * input", or "commitText on inactive InputConnection" in logcat) and is dropped, so a plain `performTextInput` is a
 * CI flake. This retries on the same field: a failed or dropped input is cleared and typed again, and only a field
 * that still lacks the text after [attempts] tries fails the test, with the usual assertion message. On a warm device
 * it behaves as `performTextInput` does.
 */
internal fun ComposeTestRule.typeWhenReady(
    tag: String,
    text: String,
    attempts: Int = 3,
    timeoutMs: Long = 2_000L,
) {
    val input = onNodeWithTag(tag)
    repeat(attempts) {
        val typed = runCatching { input.performTextInput(text) }.isSuccess
        if (typed) {
            val landed =
                runCatching {
                    waitUntil(timeoutMs) { runCatching { input.assert(hasText(text)) }.isSuccess }
                }.isSuccess
            if (landed) return
        }
        runCatching { input.performTextClearance() }
        waitForIdle()
    }
    input.performTextInput(text)
    input.assert(hasText(text))
}
