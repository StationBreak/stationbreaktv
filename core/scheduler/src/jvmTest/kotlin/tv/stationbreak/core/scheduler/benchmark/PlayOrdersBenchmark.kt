// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler.benchmark

import tv.stationbreak.core.scheduler.SeededGen
import tv.stationbreak.core.scheduler.V2Fixture
import tv.stationbreak.core.scheduler.V2Order
import tv.stationbreak.core.scheduler.buildDay
import tv.stationbreak.core.scheduler.v2Fixture
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How long a whole-day build takes for each version 2 order on a library of about 10,000 items (pool, order and
 * layout). A plain timed loop on a warm JVM, run only through `./gradlew :core:scheduler:benchmark` like
 * `WhatsOnBenchmark`, because wall-clock time on a shared runner is noisy. Run with `-Xint` for the interpreter-only
 * figure; that run is reported, not asserted.
 */
class PlayOrdersBenchmark {
    private val fixture: V2Fixture =
        SeededGen(2026uL).v2Fixture(series = 200, episodes = 40, films = 2_000, collections = 300)

    private fun medianBuildMs(order: V2Order): Double {
        val settings = order.settings(0x1234uL, aligned = false)
        var sink = 0
        repeat(WARM_UP) { sink += buildDay(settings, fixture.items, DAY + it, fixture.films).blocks.size }
        val samples = DoubleArray(RUNS)
        for (i in 0 until RUNS) {
            val began = System.nanoTime()
            sink += buildDay(settings, fixture.items, DAY + i, fixture.films).blocks.size
            samples[i] = (System.nanoTime() - began) / NS_PER_MS
        }
        samples.sort()
        val median = samples[RUNS / 2].roundToLong()
        val p95 = samples[RUNS * 95 / 100].roundToLong()
        println("PlayOrdersBenchmark $order: ${fixture.items.size} items; median $median ms, p95 $p95 ms ($sink)")
        return samples[RUNS / 2]
    }

    @Test
    fun medianWholeDayBuildOnATenThousandItemLibrary() {
        for (order in V2Order.entries) {
            val median = medianBuildMs(order)
            assertTrue(median < LIMIT_MS, "$order: median whole-day build $median ms is not under $LIMIT_MS ms")
        }
    }

    private companion object {
        const val DAY = 1_234L
        const val WARM_UP = 15
        const val RUNS = 60
        const val NS_PER_MS = 1e6
        const val LIMIT_MS = 100.0
    }
}
