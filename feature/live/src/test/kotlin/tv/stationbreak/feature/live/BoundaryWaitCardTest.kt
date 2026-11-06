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

    @Test
    fun aSlowScheduleReadAtTheBoundaryShowsTheIdentForTheWholeWait() =
        runTest {
            val r = Rig(this)
            r.twoShows()
            r.start()
            r.schedule.onAirDelayMs = 17_500
            r.atMin(50)
            r.at(boundaryMs + 1_000)

            val ident = assertIs<LiveState.Ident>(r.state)
            assertEquals(ChannelTag(7, "Test Channel 7"), ident.channel)
            assertTrue(r.tune.calls.any { it is Call.Stop })

            r.at(boundaryMs + 17_000)
            assertIs<LiveState.Ident>(r.state)

            r.at(boundaryMs + 18_000)
            assertEquals(itemOf(2), r.tunes.last().item)
            assertFalse(r.state is LiveState.Ident)
            assertTrue(r.reports.any { it is TuneReport.Cut })
        }

    @Test
    fun aQuickScheduleReadAtTheBoundaryShowsNoIdent() =
        runTest {
            val r = Rig(this)
            r.twoShows()
            r.start()
            r.schedule.onAirDelayMs = 100
            r.atMin(50)
            r.at(boundaryMs + 5_000)

            val played =
                r.tune.calls.mapNotNull { (it as? Call.Tune)?.item ?: (it as? Call.TuneHandle)?.item }
            assertTrue(itemOf(2) in played)
            assertTrue(r.states.joinToString("\n"), r.seen.none { it is LiveState.Ident && it.channel != null })
        }
}
