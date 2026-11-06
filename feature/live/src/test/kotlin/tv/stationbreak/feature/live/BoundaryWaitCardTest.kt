// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.playback.PlaybackEnd
import kotlin.time.Duration.Companion.minutes

/** #608: a block boundary that waits on the schedule shows the ident card, not a dead picture. */
class BoundaryWaitCardTest {
    /** The first block ends 50 minutes in: the rig starts 10 minutes into it. */
    private val boundaryMs = 50 * 60_000L

    private fun Rig.twoShows() {
        schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
        tune.script(itemOf(1), tune.playsThenEnds(60.minutes, PlaybackEnd.Ended))
    }

    /** Starts, lets the look-ahead resolve the next show at full speed, then makes every schedule read take [ms]. */
    private fun Rig.slowFromJustBeforeTheBoundary(ms: Long) {
        at(boundaryMs - 5_000)
        schedule.onAirDelayMs = ms
    }

    private fun Rig.stops() = tune.calls.count { it is Call.Stop }

    private fun Rig.channelIdents() = seen.filter { it is LiveState.Ident && it.channel != null }

    @Test
    fun aSlowScheduleReadAtTheBoundaryShowsTheIdentForTheWholeWait() =
        runTest {
            val r = Rig(this)
            r.twoShows()
            r.start()
            r.slowFromJustBeforeTheBoundary(17_500)
            r.at(boundaryMs + 1_000)

            val ident = assertIs<LiveState.Ident>(r.state)
            assertEquals(ChannelTag(7, "Test Channel 7"), ident.channel)
            assertEquals(1, r.stops())

            r.at(boundaryMs + 17_000)
            assertIs<LiveState.Ident>(r.state)

            r.at(boundaryMs + 18_000)
            assertFalse(r.state is LiveState.Ident)
            // Continues as today: the wait outlived the prepared handle (NextUp's cut slack), which is discarded,
            // and the next show is joined where the clock has it, with the cut's gap holding the whole wait.
            assertTrue(r.tune.calls.any { it is Call.Discard && it.item == itemOf(2) })
            assertEquals(itemOf(2), r.tunes.last().item)
            assertEquals(1, r.stops())
            val cut = r.reports.filterIsInstance<TuneReport.Cut>().single()
            assertTrue("gap ${cut.gapMs}", cut.gapMs >= 17_500)
        }

    @Test
    fun aQuickScheduleReadAtTheBoundaryShowsNoIdentAndStopsNothing() =
        runTest {
            val r = Rig(this)
            r.twoShows()
            r.start()
            r.slowFromJustBeforeTheBoundary(100)
            r.at(boundaryMs + 5_000)

            assertEquals(
                listOf(itemOf(2)),
                r.tune.calls
                    .filterIsInstance<Call.TuneHandle>()
                    .map { it.item },
            )
            assertEquals(0, r.stops())
            assertTrue(r.states.joinToString("\n"), r.channelIdents().isEmpty())
        }

    @Test
    fun aReadThatTakesExactlyTheGraceEndsOnTheNextShowNotOnTheCard() =
        runTest {
            val r = Rig(this)
            r.twoShows()
            r.start()
            r.slowFromJustBeforeTheBoundary(LiveTimings().boundaryCardAfter.inWholeMilliseconds)
            r.at(boundaryMs + 399)
            assertTrue(r.channelIdents().isEmpty())
            r.at(boundaryMs + 5_000)

            assertFalse(r.state is LiveState.Ident)
            assertEquals(
                listOf(itemOf(2)),
                r.tune.calls
                    .filterIsInstance<Call.TuneHandle>()
                    .map { it.item },
            )
        }

    @Test
    fun aSittingEndedBeforeTheGraceLeavesNoCardAndNoTimer() =
        runTest {
            val r = Rig(this)
            r.twoShows()
            val run = r.start()
            r.slowFromJustBeforeTheBoundary(17_500)
            r.at(boundaryMs + 100)
            run.cancel()
            r.at(boundaryMs + 20_000)

            assertTrue(r.states.joinToString("\n"), r.channelIdents().isEmpty())
        }

    @Test
    fun aCardAlreadyShowingIsLeftAloneByASlowRead() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), slate(60, 90), program(90, 150, 2)))
            r.tune.script(itemOf(1), r.tune.playsThenEnds(60.minutes, PlaybackEnd.Ended))
            r.start()
            r.at(boundaryMs + 1_000)
            val ident = assertIs<LiveState.Ident>(r.state)
            assertEquals("Test Movie 2", ident.upNext?.title)
            val stops = r.stops()
            // The slate ends 30 minutes after the boundary; the read then is slow.
            r.at(boundaryMs + 25 * 60_000L)
            r.schedule.onAirDelayMs = 17_500
            r.at(boundaryMs + 30 * 60_000L + 10_000)

            assertEquals(ident, r.state)
            assertEquals(stops, r.stops())
        }
}
