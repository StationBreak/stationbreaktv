// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The banner's "Next" line and the actions banner the viewer asks for (spec 17, Task 5). */
class BannerNextTest {
    private fun Flipping.watching(): LiveState.Watching = assertIs(r.state)

    private suspend fun TestScope.onChannel5WithAfter() =
        flipping {
            schedule.channel(CH5, 5, listOf(program(0, 30, 5), slate(30, 60), program(60, 90, 6)))
        }

    @Test
    fun nextSkipsSlates() =
        runTest {
            val f = onChannel5WithAfter()
            val banner = checkNotNull(f.watching().banner)
            assertEquals(NextProgram("Test Movie 6", T0 + 60 * MIN), banner.next)
        }

    @Test
    fun noNextWhenNothingFollows() =
        runTest {
            val f = flipping()
            assertNull(checkNotNull(f.watching().banner).next)
        }

    @Test
    fun selectShowsBannerWithActions() =
        runTest {
            val f = onChannel5WithAfter()
            // The tune banner has gone by now; Select brings the viewer's own.
            advanceTimeBy(5.seconds)
            assertNull(f.watching().banner)
            f.send(LiveCommand.ShowBanner)
            val banner = checkNotNull(f.watching().banner)
            assertTrue(banner.actions)
            assertEquals(BannerLabel.Now, banner.label)
            assertEquals("Test Movie 6", banner.next?.title)
            assertFalse("no channel to go back to yet", banner.hasLast)
        }

    @Test
    fun theActionsBannerOffersLastOnceAChannelWasLeft() =
        runTest {
            val f = flipping()
            f.up()
            advanceTimeBy(5.seconds)
            f.send(LiveCommand.ShowBanner)
            assertTrue(checkNotNull(f.watching().banner).hasLast)
        }

    @Test
    fun actionsBannerStaysWhileKeysArrive() =
        runTest {
            val f = onChannel5WithAfter()
            advanceTimeBy(5.seconds)
            f.send(LiveCommand.ShowBanner)
            advanceTimeBy(3.seconds)
            f.send(LiveCommand.ShowBanner)
            advanceTimeBy(3.seconds)
            f.send(LiveCommand.ShowBanner)
            advanceTimeBy(3.seconds)
            runCurrent()
            assertTrue("3 s after the last key", checkNotNull(f.watching().banner).actions)
            advanceTimeBy(1100.milliseconds)
            runCurrent()
            assertNull("4.1 s after the last key", f.watching().banner)
        }

    @Test
    fun backWithOnlyATuneBannerChangesNothing() =
        runTest {
            val f = onChannel5WithAfter()
            val before = f.r.state
            f.send(LiveCommand.DismissOverlay)
            assertEquals(before, f.r.state)
            assertNull(f.r.live.info.value)
        }
}
