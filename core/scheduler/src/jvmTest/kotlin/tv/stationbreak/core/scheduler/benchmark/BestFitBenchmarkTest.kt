// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler.benchmark

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.scheduler.MS_MINUTE
import tv.stationbreak.core.scheduler.bestFit
import tv.stationbreak.core.scheduler.bestGroupedFit
import tv.stationbreak.core.scheduler.testItem
import kotlin.test.Test
import kotlin.test.assertTrue

/** A timing guard for the worst case: a 24-hour budget and 64 candidates. JVM only (commonTest runs natively too). */
class BestFitBenchmarkTest {
    private val candidates =
        (1..64).map { testItem(idAt(it), runtimeMs = (7 + it % 53) * MS_MINUTE + it * 1_337L) }

    private fun idAt(index: Int) = CanonicalId.parse(index.toString(16).padStart(32, '0'))

    private val budgetMs = 24 * 60 * MS_MINUTE
    private val groupIds = (0 until 8).map { idAt(it) }

    private fun medianMs(run: () -> Int): Double {
        var sink = 0
        repeat(WARM_UP) { sink += run() }
        val samples =
            DoubleArray(RUNS) {
                val began = System.nanoTime()
                sink += run()
                (System.nanoTime() - began) / NS_PER_MS
            }
        samples.sort()
        println("BestFitBenchmarkTest median ${samples[RUNS / 2]} ms ($sink)")
        return samples[RUNS / 2]
    }

    @Test
    fun a24HourBudgetWith64CandidatesRunsUnder50msOnJvm() {
        val plain = medianMs { bestFit(candidates, budgetMs) { it.runtimeMs }.size }
        val index = candidates.withIndex().associate { it.value.id to groupIds[it.index % groupIds.size] }
        val grouped = medianMs { bestGroupedFit(candidates, { index.getValue(it.id) }, budgetMs) { it.runtimeMs }.size }
        assertTrue(plain < LIMIT_MS, "bestFit median $plain ms")
        assertTrue(grouped < LIMIT_MS, "bestGroupedFit median $grouped ms")
    }

    private companion object {
        const val WARM_UP = 20
        const val RUNS = 21
        const val NS_PER_MS = 1_000_000.0
        const val LIMIT_MS = 50.0
    }
}
