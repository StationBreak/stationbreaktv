// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler.benchmark

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.scheduler.Day
import tv.stationbreak.core.scheduler.buildDay
import tv.stationbreak.core.scheduler.runtimeMsOrZero
import tv.stationbreak.core.scheduler.shortRuntimeMs
import tv.stationbreak.core.scheduler.syntheticScheduleItems
import tv.stationbreak.core.scheduler.testSettings
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How long `Day.whatsOn` takes on a built day from a 10,000-item library (spec 08, Goal 4: under 5 ms). A plain timed
 * loop on a warm JVM, with no library. It is excluded from `jvmTest` and `check` (wall-clock time on a shared runner is
 * noisy) and runs only through `./gradlew :core:scheduler:benchmark`.
 */
class WhatsOnBenchmark {
    private val library = SyntheticLibrary(size = 10_000, seed = 7L)

    private fun report(
        label: String,
        items: List<ScheduleItem>,
    ): Double {
        val settings =
            testSettings(
                orderingMode = OrderingMode.Shuffle,
                alignToGrid = false,
                epochHours = 24,
                startMinuteOfDay = 360,
            )
        val dayNumber = 30_000L
        // buildDay is timed too, for information: the caller builds a day once and then asks it many times.
        val builds = LongArray(BUILDS)
        var day: Day = buildDay(settings, items, dayNumber)
        for (i in 0 until BUILDS) {
            val began = System.nanoTime()
            day = buildDay(settings, items, dayNumber)
            builds[i] = System.nanoTime() - began
        }
        val span = day.endMs - day.startMs

        fun timeAt(i: Int): Long = day.startMs + (i.toLong() * STRIDE) % span

        var sink = 0L
        for (i in 0 until WARM_UP) sink += day.whatsOn(timeAt(i), NEXT).block.startMs
        val samples = LongArray(CALLS)
        for (i in 0 until CALLS) {
            val t = timeAt(i)
            val began = System.nanoTime()
            val answer = day.whatsOn(t, NEXT)
            samples[i] = System.nanoTime() - began
            sink += answer.block.startMs
        }
        samples.sort()
        builds.sort()
        val median = samples[CALLS / 2]
        val p95 = samples[CALLS * 95 / 100]
        println(
            "WhatsOnBenchmark $label: ${items.size} items, ${day.blocks.size} blocks; " +
                "$CALLS calls after $WARM_UP warm-up; " +
                "whatsOn median $median ns, p95 $p95 ns, max ${samples.last()} ns; " +
                "buildDay median ${builds[BUILDS / 2] / NS_PER_MS} ms (checksum $sink)",
        )
        return median.toDouble() / NS_PER_MS
    }

    @Test
    fun medianAndP95OnATenThousandItemDay() {
        val own = syntheticScheduleItems(library) { it.runtimeMsOrZero() }
        val short = syntheticScheduleItems(library) { it.shortRuntimeMs() }
        val medianOwnMs = report("library runtimes", own)
        val medianShortMs = report("short programs", short)
        assertTrue(medianOwnMs < LIMIT_MS && medianShortMs < LIMIT_MS, "whatsOn median is not under $LIMIT_MS ms")
    }

    private companion object {
        const val BUILDS = 11
        const val WARM_UP = 100_000
        const val CALLS = 200_000
        const val NEXT = 5
        const val STRIDE = 7_919L
        const val NS_PER_MS = 1_000_000L
        const val LIMIT_MS = 5.0
    }
}
