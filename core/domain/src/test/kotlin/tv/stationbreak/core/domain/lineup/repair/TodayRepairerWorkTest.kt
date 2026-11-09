// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.model.ItemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The work a selection change does in the repairer's turn (spec 26: the slow untick on older hardware), not what it
 * records (the other repair tests pin that): what each channel admits of the new selection is built only when
 * something reads it, and today's days, warmed before the turn ([TodayRepairer.warmDays]), are not built in it.
 */
internal class TodayRepairerWorkTest : RepairTestBase() {
    @Test
    fun aFirstUntick_thatEmptiesNoChannel_neverBuildsTheNewSelectionsDays() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB + crimesB, setOf(libA, libB))
        val clock = RepairClock()

        val outcome = rig.untick(setOf(libA), clock)

        assertIs<RepairOutcome.Saved<SelectionRepaired>>(outcome)
        assertEquals(-1, clock.phases().admittedMs, "the new selection's build never ran")
    }

    @Test
    fun anUntick_thatEmptiesAChannel_buildsIt_forThatChannelsReason() {
        val rig = Rig(lineupOf(laughs, ownB), comedies + comediesB, setOf(libA, libB))
        val clock = RepairClock()

        rig.untick(setOf(libB), clock)

        assertTrue(clock.phases().admittedMs >= 0, "the build ran for the emptied channel's reason")
        assertEquals(HiddenReason.LibraryNotChosen, rig.repairOf(ownB.id).tailHidden)
    }

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
