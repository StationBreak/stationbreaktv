// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.LineupEvent
import tv.stationbreak.core.model.ItemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class LostRepairHookTest : RepairTestBase() {
    private val events = mutableListOf<LineupEvent>()
    private val asked = mutableListOf<Set<ItemId>?>()
    private var answers = ArrayDeque<RepairOutcome<Int>>()
    private var chosen: Set<ItemId>? = setOf(libA)

    private fun hook() =
        LostRepairHook(
            repair = { _: LibraryAccount, playable: Set<ItemId>?, _: Long ->
                asked += playable
                answers.removeFirst()
            },
            playable = { chosen },
            nowMs = { atMs },
            report = { events += it },
        )

    private fun run(vararg outcomes: RepairOutcome<Int>) {
        answers = ArrayDeque(outcomes.toList())
        runBlocking { hook()(ACCOUNT) }
    }

    @Test
    fun saved_isDone() {
        run(RepairOutcome.Saved(2))
        assertEquals(1, asked.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun stale_isRetriedOnce_andThenDone() {
        run(RepairOutcome.Stale, RepairOutcome.Saved(1))
        assertEquals(2, asked.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun stale_twice_isReported_andNotRetriedAgain() {
        run(RepairOutcome.Stale, RepairOutcome.Stale)
        assertEquals(2, asked.size)
        assertEquals(listOf(LineupEvent.LostRepairNotSaved), events)
    }

    @Test
    fun selectionUnknown_andNoLineup_doNothingFurther() {
        run(RepairOutcome.SelectionUnknown)
        run(RepairOutcome.NoLineup)
        assertEquals(2, asked.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun otherOutcomes_areReported_notRetried() {
        for (outcome in listOf(RepairOutcome.Refused, RepairOutcome.UndoFailed, RepairOutcome.Unsaved)) {
            events.clear()
            asked.clear()
            run(outcome)
            assertEquals(1, asked.size, "$outcome")
            assertEquals(listOf(LineupEvent.LostRepairNotSaved), events, "$outcome")
        }
    }

    @Test
    fun aGoneSelection_isPassedThroughAsNull() {
        chosen = null
        run(RepairOutcome.Saved(0))
        assertEquals(listOf<Set<ItemId>?>(null), asked)
    }

    @Test
    fun cancellation_reachesTheCaller() {
        val cancelling =
            LostRepairHook({ _, _, _ -> throw CancellationException("stop") }, { chosen }, { atMs }) {
                events += it
            }
        assertFailsWith<CancellationException> { runBlocking { cancelling(ACCOUNT) } }
    }
}
