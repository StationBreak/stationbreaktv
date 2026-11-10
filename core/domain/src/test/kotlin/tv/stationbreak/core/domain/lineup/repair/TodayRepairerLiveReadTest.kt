// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.leftovers
import tv.stationbreak.core.domain.lineup.lineupOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The current-row reads of a selection change (spec 26: the slow untick on older hardware): which tail pool items have
 * a current row is asked once for every channel, not once per channel. What it records is pinned by the other repair
 * tests, unchanged.
 */
internal class TodayRepairerLiveReadTest : RepairTestBase() {
    private val comediesC = (0 until 3).map { film(340 + it, "Comedy", library = libC) }
    private val crimesC = (0 until 3).map { film(360 + it, "Crime", library = libC) }
    private val items = comedies + crimes + comediesB + crimesB + comediesC + crimesC
    private val lineup = lineupOf(laughs, whodunit, ownB, leftovers("odds-and-ends", 199, null))

    @Test
    fun aChange_asksWhichTailItemsAreLive_onceForEveryChannel() {
        val rig = Rig(lineup, items, setOf(libA, libB, libC))
        val calls = rig.library.currentIdsCalls
        val after = setOf(libA, libB)

        val outcome =
            runBlocking {
                rig.repairer.selectionChanged(ACCOUNT, after, atMs, 0L) { seq ->
                    rig.chosen = after
                    rig.applied = seq
                    true
                }
            }

        assertIs<RepairOutcome.Saved<SelectionRepaired>>(outcome)
        // One read for the programmes in the kept windows, and the early read of the new selection's items, which
        // answers every channel's tail pool (#684).
        assertEquals(2, rig.library.currentIdsCalls - calls)
    }
}
