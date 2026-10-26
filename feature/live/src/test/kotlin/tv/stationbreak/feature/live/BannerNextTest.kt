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
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.standIn
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

    /** Program 5, then four slates (more than the blocks read with the tune), then program 7. */
    private fun Rig.slatesThenSeven() {
        schedule.channel(
            CH5,
            5,
            listOf(
                program(0, 30, 5),
                slate(30, 60),
                slate(60, 90),
                slate(90, 120),
                slate(120, 150),
                program(150, 180, 7),
            ),
        )
    }

    @Test
    fun nextFallsBackToAWiderScanWhenTheReadBlocksAreAllSlates() =
        runTest {
            val f = flipping { slatesThenSeven() }
            val banner = checkNotNull(f.watching().banner)
            assertEquals(NextProgram("Test Movie 7", T0 + 150 * MIN), banner.next)
        }

    @Test
    fun widerNextScanDoesNotDelayTheTune() =
        runTest {
            val usual = mutableListOf<Int>()
            flipping { schedule.onAirObserver = { usual += it } }
            val events = mutableListOf<String>()
            val asked = mutableListOf<Int>()
            val f =
                flipping {
                    slatesThenSeven()
                    schedule.onAirObserver = {
                        asked += it
                        if (it == WIDE) events += "wide"
                    }
                    tune.script(itemOf(5), {
                        events += "tune"
                        FakeTune.plays()
                    })
                }
            // The tune comes first, and the picture shows its banner without the line, which fills in after.
            assertEquals(listOf("tune", "wide"), events)
            val shown =
                f.r.seen
                    .filterIsInstance<LiveState.Watching>()
                    .mapNotNull { it.banner }
            assertNull(shown.first().next)
            assertEquals("Test Movie 7", shown.last().next?.title)
            // The reads on the tune path are the usual ones; the wide scan is one more, after the picture.
            assertEquals(usual.filter { it != WIDE }, asked.filter { it != WIDE })
            assertEquals(1, asked.count { it == WIDE })
        }

    @Test
    fun anUntitledProgramDoesNotHideTheTitledOneAfterIt() =
        runTest {
            val f =
                flipping {
                    schedule.channel(
                        CH5,
                        5,
                        listOf(program(0, 30, 5), program(30, 60, 6, info = null), program(60, 90, 7)),
                    )
                }
            runCurrent()
            assertEquals(NextProgram("Test Movie 7", T0 + 60 * MIN), checkNotNull(f.watching().banner).next)
        }

    /** Program 1 on channel 7 is dead, so stand-ins fill its block; [after] follows it. */
    private suspend fun TestScope.standInOnSeven(after: List<AiringBlock>): Rig {
        val r = Rig(this, startMin = 10)
        r.schedule.channel(CH7, 7, listOf(program(0, 60, 1)) + after)
        r.schedule.standInScript = { _, _ -> listOf(standIn(0, 60, 8, 0)) }
        r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
        r.start()
        // Past the slate's hold and the snow: the stand-in is on screen.
        r.at(10_000)
        return r
    }

    /** The stand-in's banner as last shown over its picture (it has timed out by now). */
    private fun Rig.standInBanner(): Banner =
        seen
            .filterIsInstance<LiveState.Watching>()
            .mapNotNull { it.banner }
            .last { it.label == BannerLabel.FillingIn }

    @Test
    fun aStandInsBannerSaysWhatComesNext() =
        runTest {
            val r = standInOnSeven(listOf(program(60, 120, 2)))
            assertEquals(NextProgram("Test Movie 2", T0 + 60 * MIN), r.standInBanner().next)
        }

    @Test
    fun aStandInsBannerLooksWiderWhenTheReadBlocksAreAllSlates() =
        runTest {
            val slates = listOf(slate(60, 90), slate(90, 120), slate(120, 150), slate(150, 180))
            val r = standInOnSeven(slates + program(180, 240, 3))
            runCurrent()
            assertEquals(NextProgram("Test Movie 3", T0 + 180 * MIN), r.standInBanner().next)
        }

    private companion object {
        const val WIDE = 48
    }
}
