// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The picker's "YOU'RE IN!" clock starts at the first frame drawn once the screen is shown (its navigation entry
 * resumed, after the fade-in), not when the list is first composed: on a slow TV that composition, Stan's first
 * paint and the fade used up the whole cheer before anyone saw it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheerStartTest {
    @Test
    fun theClockWaitsForTheScreenToBeShownAndThenForAFrame() =
        runTest {
            val frames = BroadcastFrameClock()
            val shown = CompletableDeferred<Unit>()
            var now = 1_000L
            val start = async(frames) { cheerStart(shown = { shown.await() }, now = { now }) }
            runCurrent()

            // Composed, but the fade-in is still running: a frame drawn now does not start it.
            now = 1_700L
            frames.sendFrame(1L)
            runCurrent()
            assertFalse(start.isCompleted)

            // Shown, but nothing drawn since: still waiting.
            now = 1_900L
            shown.complete(Unit)
            runCurrent()
            assertFalse(start.isCompleted)

            // The first frame after it is shown starts the clock.
            now = 2_000L
            frames.sendFrame(2L)
            runCurrent()
            assertEquals(2_000L, start.await())
        }
}
