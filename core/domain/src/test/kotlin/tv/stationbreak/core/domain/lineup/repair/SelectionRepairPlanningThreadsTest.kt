// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.repair.RepairMatrix.referenceOf
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The equality matrix of #684 ([RepairMatrix], against the frozen reference, which the golden digests of
 * [SelectionRepairEqualityTest] pin) run through the real [TodayRepairer], built with its internal constructor and a
 * planning pool of one thread and of four (`planThreads`' ends): every change goes through `selectionChanged`.
 *
 * The rig builds the repairer that way through its additive `planning` parameter; its scenario bodies run
 * unchanged.
 */
internal class SelectionRepairPlanningThreadsTest {
    private fun runWith(
        scenario: Scenario,
        threads: Int,
    ): List<Step> {
        val planning = planningDispatcher(RepairConfig(planThreads = threads))
        try {
            return runBlocking {
                val world = RepairWorld(scenario.libs, scenario.shape, firstSeenFilter = true, planning = planning)
                world.(scenario.body)()
                world.steps
            }
        } finally {
            (planning as ExecutorCoroutineDispatcher).close()
        }
    }

    private fun mismatches(threads: Int): List<String> =
        RepairMatrix.scenarios().mapNotNull { scenario ->
            val actual = runCatching { runWith(scenario, threads) }
            when {
                actual.isFailure -> "${scenario.name}: threw ${actual.exceptionOrNull()}"
                actual.getOrThrow() != referenceOf(scenario.name) -> "${scenario.name}: differs from the reference"
                else -> null
            }
        }

    @Test
    fun onOnePlanningThread_theRepairerRecordsWhatTheReferenceRecords() {
        val failures = mismatches(1)
        assertTrue(failures.isEmpty(), "one thread differs from the reference:\n${failures.joinToString("\n")}")
    }

    @Test
    fun onFourPlanningThreads_theRepairerRecordsWhatTheReferenceRecords() {
        val failures = mismatches(4)
        assertTrue(failures.isEmpty(), "four threads differ from the reference:\n${failures.joinToString("\n")}")
    }
}
