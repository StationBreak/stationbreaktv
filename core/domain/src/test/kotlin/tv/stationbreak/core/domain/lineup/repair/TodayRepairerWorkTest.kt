// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.model.ItemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The work a selection change does in the repairer's turn (spec 26: the slow untick on older hardware), not what it
 * records (the other repair tests pin that): today's days, warmed before the turn ([TodayRepairer.warmDays]), are not
 * built in it.
 */
internal class TodayRepairerWorkTest : RepairTestBase() {
    @Test
    fun anUntickRightAfterAnAdd_warmedFirst_buildsNoDayInTheTurn() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB + crimesB, setOf(libA))
        rig.days()
        // The add's save moves the saved selection, so today's days are no longer the ones built.
        val added =
            runBlocking {
                rig.repairer.saveWithoutRepair(ACCOUNT, 0L) { seq ->
                    rig.chosen = setOf(libA, libB)
                    rig.applied = seq
                    true
                }
            }
        assertIs<RepairOutcome.Saved<Long>>(added)
        var inTurn = -1
        // As the selection's allocation does: today's days warmed first, with the selection it read.
        runBlocking { rig.repairer.warmDays(ACCOUNT, checkNotNull(rig.source.savedSelection()), atMs) }

        val outcome = rig.untick(setOf(libA), entered = { inTurn = rig.builds.size })

        assertIs<RepairOutcome.Saved<SelectionRepaired>>(outcome)
        assertEquals(inTurn, rig.builds.size, "no day was built once the turn was held")
    }

    // The change from the rig's selection to it without [removed], saved; [clock] and [entered] as the repairer takes
    // them.
    private fun Rig.untick(
        removed: Set<ItemId>,
        clock: RepairClock? = null,
        entered: () -> Unit = {},
    ): RepairOutcome<SelectionRepaired> {
        val after = chosen - removed
        return runBlocking {
            repairer.selectionChanged(ACCOUNT, after, atMs, 0L, entered, clock) { seq ->
                chosen = after
                applied = seq
                true
            }
        }
    }
}
