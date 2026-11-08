// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.lineupOf
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * The re-entry marker (spec 26, Part 4 §2): a call of the repairer from inside its own turn (a save callback, which
 * runs in the turn) fails loudly at once, where it would otherwise wait on the turn it is in for ever.
 */
internal class TodayRepairerInTurnTest : RepairTestBase() {
    // Every entry of the repairer, each called from where it must be refused.
    private suspend fun TodayRepairer.everyEntry(): List<suspend () -> Any?> =
        listOf(
            { selectionChanged(ACCOUNT, setOf(libA), atMs, atLeast = 0L) { true } },
            { repairLost(ACCOUNT, atMs) },
            { dropSelection(ACCOUNT, 99L) },
            { saveWithoutRepair(ACCOUNT, atLeast = 0L) { true } },
            { resetSelection(ACCOUNT, atLeast = 0L) { true } },
            { heldPins(ACCOUNT, atMs) },
        )

    // Each within a bound: an entry that is not refused waits on the turn it is in, and the bound turns that into a
    // failure rather than a test that never ends (a timeout is a cancellation, which is not the refusal).
    private suspend fun TodayRepairer.assertEveryEntryRefused() {
        for (entry in everyEntry()) {
            val refused = assertFailsWith<IllegalStateException> { withTimeout(BOUND_MS) { entry() } }
            assertFalse(refused is CancellationException, "refused at once, not left waiting: $refused")
        }
    }

    @Test
    fun insideAChangesSave_everyEntryIsRefused() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))

        val outcome =
            runBlocking {
                rig.repairer.selectionChanged(ACCOUNT, setOf(libA), atMs, atLeast = 0L) { seq ->
                    rig.repairer.assertEveryEntryRefused()
                    rig.chosen = setOf(libA)
                    rig.applied = seq
                    true
                }
            }

        assertIs<RepairOutcome.Saved<SelectionRepaired>>(outcome)
    }

    @Test
    fun insideASaveWithoutRepair_everyEntryIsRefused() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))

        val outcome =
            runBlocking {
                rig.repairer.saveWithoutRepair(ACCOUNT, atLeast = 0L) { seq ->
                    rig.repairer.assertEveryEntryRefused()
                    rig.applied = seq
                    true
                }
            }

        assertEquals(RepairOutcome.Saved(1L), outcome)
    }

    @Test
    fun insideASetupsSave_everyEntryIsRefused() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))

        val outcome =
            runBlocking {
                rig.repairer.resetSelection(ACCOUNT, atLeast = 0L) { seq ->
                    rig.repairer.assertEveryEntryRefused()
                    rig.applied = seq
                    true
                }
            }

        assertEquals(RepairOutcome.Saved(1L), outcome)
    }

    @Test
    fun aSecondCall_fromTheSameCoroutine_afterItsTurnEnded_goesThrough() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))

        runBlocking {
            val first = rig.repairer.saveWithoutRepair(ACCOUNT, atLeast = 0L) { seq -> true.also { rig.applied = seq } }
            assertEquals(RepairOutcome.Saved(1L), first)
            // The marker went with the turn: the same coroutine enters again.
            assertEquals(RepairOutcome.Saved(0), rig.repairer.repairLost(ACCOUNT, atMs))
            assertEquals(emptyMap(), rig.repairer.heldPins(ACCOUNT, atMs))
            val again = rig.repairer.resetSelection(ACCOUNT, atLeast = 0L) { seq -> true.also { rig.applied = seq } }
            assertEquals(RepairOutcome.Saved(2L), again)
        }
    }

    @Test
    fun outsideATurn_theCallsGoThrough() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))

        assertEquals(RepairOutcome.Saved(0), runBlocking { rig.repairer.repairLost(ACCOUNT, atMs) })
        assertEquals(emptyMap(), runBlocking { rig.repairer.heldPins(ACCOUNT, atMs) })
    }
}

private const val BOUND_MS = 2_000L
