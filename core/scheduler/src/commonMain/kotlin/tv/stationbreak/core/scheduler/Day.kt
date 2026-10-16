// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings

/** What is on at one moment: the block, how far into the media, and the blocks that follow it in the same day. */
data class OnAir(
    val block: ScheduleBlock,
    val offsetMs: Long?,
    val next: List<ScheduleBlock>,
)

/** One built day (epoch [n]): contiguous half-open blocks that cover exactly `[startMs, endMs)`. */
class Day internal constructor(
    val n: Long,
    val startMs: Long,
    val endMs: Long,
    val blocks: List<ScheduleBlock>,
    internal val startRunIndex: Int,
    internal val regularRunCount: Int,
    internal val cycleRunCount: Int,
    private val onCompare: (() -> Unit)? = null,
) {
    fun whatsOn(
        t: Long,
        k: Int,
    ): OnAir = TODO("task 3.6")
}

fun buildDay(
    settings: SchedulerSettings,
    items: List<ScheduleItem>,
    n: Long,
): Day = buildDayCounting(settings, items, n, null)

internal fun buildDayCounting(
    settings: SchedulerSettings,
    items: List<ScheduleItem>,
    n: Long,
    onCompare: (() -> Unit)?,
): Day {
    val start = Epochs.epochStart(settings, n)
    val end = start + settings.epochMs
    val pool = EpochPool.build(settings, items, n)
    val cycle = Timeline.cycle(settings, pool, Epochs.seedN(settings.seed, n))
    if (cycle.isEmpty()) {
        return Day(n, start, end, listOf(ScheduleBlock(start, end, ScheduleContent.Slate)), 0, 0, 0, onCompare)
    }
    val startRun = Timeline.startRun(settings, cycle, start)
    val layout = Timeline.layout(settings, cycle, startRun, start, end)
    return Day(n, start, end, layout.blocks, startRun, layout.runsStarted, cycle.size, onCompare)
}

/** The day's layout, step by step (spec 08, Unit 3). Pure functions of their inputs. */
internal object Timeline {
    class Layout(
        val blocks: List<ScheduleBlock>,
        val runsStarted: Int,
    )

    /** A run's slot: its runtime, rounded up to a whole number of grid slots when aligned. */
    fun slotMs(
        settings: SchedulerSettings,
        run: Run,
    ): Long =
        if (settings.alignToGrid) {
            (run.runtimeMs + settings.gridMs - 1).floorDiv(settings.gridMs) * settings.gridMs
        } else {
            run.runtimeMs
        }

    /** One cycle of the pool's runs, without the runs whose slot is longer than the epoch. */
    fun cycle(
        settings: SchedulerSettings,
        pool: List<ScheduleItem>,
        seedN: ULong,
    ): List<Run> = Runs.cycle(settings, pool, seedN).filter { slotMs(settings, it) <= settings.epochMs }

    /**
     * The run a day starts at. `shuffle` starts at run 0, because each day has its own seed. The other modes start at
     * the run of the channel's continuous playout (the cycle repeated forever, each run taking its slot, from the
     * origin `-offsetMs`) that contains [dayStart]: with the prefix sums `Q[0] = 0 < ... < Q[B] = C` of the slots and
     * `pos = floorMod(dayStart + offsetMs, C)`, the largest `j` with `Q[j] <= pos`, found by binary search.
     */
    fun startRun(
        settings: SchedulerSettings,
        cycle: List<Run>,
        dayStart: Long,
    ): Int {
        if (settings.orderingMode == OrderingMode.Shuffle) return 0
        val prefix = LongArray(cycle.size + 1)
        for (i in cycle.indices) prefix[i + 1] = prefix[i] + slotMs(settings, cycle[i])
        val pos = (dayStart + settings.offsetMs).mod(prefix[cycle.size])
        // The largest j in [0, B - 1] with prefix[j] <= pos. prefix[0] = 0 <= pos always holds.
        var low = 0
        var high = cycle.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (prefix[mid] <= pos) low = mid else high = mid - 1
        }
        return low
    }

    /** The first grid mark at or after [t] when aligned, else [t]. */
    fun startAtOrAfter(
        settings: SchedulerSettings,
        t: Long,
    ): Long = if (settings.alignToGrid) t + (-(t + settings.offsetMs)).mod(settings.gridMs) else t

    fun layout(
        settings: SchedulerSettings,
        cycle: List<Run>,
        startRun: Int,
        dayStart: Long,
        dayEnd: Long,
    ): Layout {
        val blocks = ArrayList<ScheduleBlock>()
        var cursor = dayStart
        var index = startRun
        var started = 0
        while (cursor < dayEnd) {
            val run = cycle[index]
            val runStart = startAtOrAfter(settings, cursor)
            if (runStart + run.runtimeMs > dayEnd) break
            if (runStart > cursor) blocks.add(ScheduleBlock(cursor, runStart, ScheduleContent.Slate))
            cursor = runStart
            for (item in run.items) {
                blocks.add(ScheduleBlock(cursor, cursor + item.runtimeMs, ScheduleContent.Program(item.id)))
                cursor += item.runtimeMs
            }
            started++
            index = (index + 1) % cycle.size
        }
        if (cursor < dayEnd) blocks.add(ScheduleBlock(cursor, dayEnd, ScheduleContent.Slate))
        return Layout(blocks, started)
    }
}
