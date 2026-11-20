// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The hand-over from setup's cheer to live TV: the cheer's still covers live TV only after the cheer, and only until
 * live TV stops standing by, a key, or [SETUP_HAND_OVER_WAIT]; after the wait live TV's own stand-by shows, as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupHandOverTest {
    private val wait = 5.seconds

    private fun TestScope.handedOver(): SetupHandOver {
        val handOver = SetupHandOver(wait)
        handOver.begin()
        backgroundScope.launch { handOver.hold() }
        runCurrent()
        return handOver
    }

    @Test
    fun liveTvNotEnteredFromTheCheerIsNeverCovered() {
        val handOver = SetupHandOver(wait)
        assertFalse(handOver.covering)

        // Live TV stops standing by on every tune: with no cheer before it, that changes nothing.
        handOver.end()
        assertFalse(handOver.covering)
    }

    @Test
    fun fromTheCheerTheStillCoversLiveTvWhileItStandsBy() =
        runTest {
            val handOver = handedOver()

            advanceTimeBy(wait - 1.milliseconds)
            runCurrent()

            assertTrue("no stand-by card shows while the cheer waits for the picture", handOver.covering)
        }

    @Test
    fun theStillLiftsTheMomentLiveTvStopsStandingBy() =
        runTest {
            val handOver = handedOver()
            advanceTimeBy(1.seconds)

            handOver.end()
            runCurrent()

            assertFalse(handOver.covering)
            // A later end (the next tune's picture) changes nothing.
            handOver.end()
            advanceTimeBy(wait)
            runCurrent()
            assertFalse(handOver.covering)
        }

    @Test
    fun afterTheWaitLiveTvShowsItsOwnStandByAsBefore() =
        runTest {
            val handOver = handedOver()

            advanceTimeBy(wait)
            runCurrent()

            assertFalse("past the wait the still lifts over whatever live TV shows", handOver.covering)
            // The picture arriving late changes nothing.
            handOver.end()
            assertFalse(handOver.covering)
        }

    @Test
    fun liftedBeforeTheHoldBeginsTheHoldReturnsAtOnce() =
        runTest {
            val handOver = SetupHandOver(wait)
            handOver.begin()
            handOver.end()

            val began = currentTime
            handOver.hold()

            assertEquals("no wait", began, currentTime)
            assertFalse(handOver.covering)
        }

    @Test
    fun theStillCoversFromTheMomentTheCheerEndsBeforeAnyWaitBegins() {
        val handOver = SetupHandOver(wait)

        handOver.begin()

        // Composed in the same frame as live TV's first, so that heavy frame shows the still.
        assertTrue(handOver.covering)
    }
}
