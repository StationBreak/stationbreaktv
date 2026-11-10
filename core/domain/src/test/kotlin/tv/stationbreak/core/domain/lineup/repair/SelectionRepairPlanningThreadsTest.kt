// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The equality matrix of #684 ([RepairMatrix], against the frozen reference) with the plan run, as `TodayRepairer`
 * runs it, through `withContext` on a normal-priority dispatcher of one thread and of four (`planThreads`' ends). The
 * frozen rig builds its repairer with the default of two (that run is [SelectionRepairEqualityTest]'s first test), so
 * these use the rig's planner seam: the plan goes through the same hop, in the rig's copy of the turn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SelectionRepairPlanningThreadsTest {
    @Suppress("InjectDispatcher") // The production dispatcher, as the repairer's public constructor makes it.
    private fun planningOn(threads: Int): Planner {
        val planning = Dispatchers.Default.limitedParallelism(threads)
        return { inputs -> withContext(planning) { Planners.production(inputs) } }
    }

    @Test
    fun onOneThread_theRepairRecordsWhatTheReferenceRecords() {
        val failures = RepairMatrix.mismatches(planningOn(1))
        assertTrue(failures.isEmpty(), "one thread differs from the reference:\n${failures.joinToString("\n")}")
    }

    @Test
    fun onFourThreads_theRepairRecordsWhatTheReferenceRecords() {
        val failures = RepairMatrix.mismatches(planningOn(4))
        assertTrue(failures.isEmpty(), "four threads differ from the reference:\n${failures.joinToString("\n")}")
    }
}
