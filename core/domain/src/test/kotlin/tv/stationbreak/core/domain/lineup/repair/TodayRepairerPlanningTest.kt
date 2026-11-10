// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LineupStore
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupSeams
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.domain.lineup.TestCatalog
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.selectionOf
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

// The thread's name without the coroutine debugger's " @coroutine#n" suffix.
private fun here(): String = Thread.currentThread().name.substringBefore(" @")

/**
 * The planning dispatcher (#684, PR 2): a selection change's plan runs on [TodayRepairer]'s `planning` dispatcher,
 * and nothing else leaves the turn's `dispatcher`: the record's write, the caller's save and the settle keep the order
 * they had (the day-saves thread, under the turn's lock). The recorded bytes are pinned by the equality oracle
 * ([SelectionRepairEqualityTest], and [SelectionRepairPlanningThreadsTest] for other thread counts).
 */
internal class TodayRepairerPlanningTest : RepairTestBase() {
    private val turnThread = Executors.newSingleThreadExecutor { Thread(it, "test-turn") }
    private val planThread = Executors.newSingleThreadExecutor { Thread(it, "test-plan") }

    @AfterTest
    fun close() {
        turnThread.shutdownNow()
        planThread.shutdownNow()
    }

    // Counts the tasks sent through it, with the names of the threads that ran them.
    private class Counting(
        private val inner: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        val threads: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = inner.dispatch(
            context,
            Runnable {
                threads += here()
                block.run()
            },
        )
    }

    @Test
    fun thePlanRunsOnPlanning_andTheRecordTheSaveAndTheTurnStayOnTheTurnsDispatcher() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB + crimesB, setOf(libA, libB))
        val turn = Counting(turnThread.asCoroutineDispatcher())
        val planning = Counting(planThread.asCoroutineDispatcher())
        val writes = Collections.synchronizedList(mutableListOf<String>())
        val store =
            object : LineupStore by rig.store {
                override suspend fun save(
                    account: LibraryAccount,
                    bytes: ByteArray,
                ): Boolean {
                    writes += here()
                    return rig.store.save(account, bytes)
                }
            }
        val clock =
            object : Clock {
                override fun now(): Instant = NOON
            }
        val service = LineupService(store, rig.library, clock, { 0 }, {}, LineupSeams(TestCatalog))
        val source = BuiltInScheduleSource(service, rig.library) { selectionOf(rig.chosen, rig.applied) }
        val repairer = TodayRepairer(service, source, rig.library, Successors.None, { rig.applied }, turn, planning)
        val saved = Collections.synchronizedList(mutableListOf<String>())
        runBlocking { repairer.warmDays(ACCOUNT, checkNotNull(source.savedSelection()), atMs) }

        val outcome =
            runBlocking {
                repairer.selectionChanged(ACCOUNT, setOf(libA), atMs, 0L) { seq ->
                    saved += here()
                    rig.chosen = setOf(libA)
                    rig.applied = seq
                    true
                }
            }

        assertIs<RepairOutcome.Saved<SelectionRepaired>>(outcome)
        assertTrue(planning.threads.isNotEmpty(), "the plan went through the planning dispatcher")
        assertEquals(setOf("test-plan"), planning.threads.toSet(), "and ran on its thread only")
        assertTrue(writes.isNotEmpty(), "the record was written")
        assertEquals(setOf("test-turn"), writes.toSet(), "the record's write ran on the turn's dispatcher")
        assertEquals(listOf("test-turn"), saved, "the save ran on the turn's dispatcher")
        assertEquals(setOf("test-turn"), turn.threads.toSet(), "the turn's dispatcher ran on its own thread")
    }

    @Test
    fun aCallThatPlansNothing_neverGoesToPlanning() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB + crimesB, setOf(libA))
        val planning = Counting(planThread.asCoroutineDispatcher())
        val repairer =
            TodayRepairer(
                rig.service,
                rig.source,
                rig.library,
                Successors.None,
                { rig.applied },
                turnThread.asCoroutineDispatcher(),
                planning,
            )

        val outcome =
            runBlocking {
                repairer.saveWithoutRepair(ACCOUNT, 0L) { seq ->
                    rig.applied = seq
                    true
                }
            }

        assertIs<RepairOutcome.Saved<Long>>(outcome)
        assertTrue(planning.threads.isEmpty(), "no plan, no hop: ${planning.threads}")
    }

    @Test
    fun planThreads_defaultsToTwo_andIsHeldToOneToFour() {
        assertEquals(2, RepairConfig().planThreads)
        assertEquals(1, RepairConfig(planThreads = 1).planThreads)
        assertEquals(4, RepairConfig(planThreads = 4).planThreads)
        assertFailsWith<IllegalArgumentException> { RepairConfig(planThreads = 0) }
        assertFailsWith<IllegalArgumentException> { RepairConfig(planThreads = 5) }
    }
}
