// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings

/**
 * A slow, plain second statement of spec 08, Unit 3, for the tests to compare `buildDay` with. It shares only the pool,
 * the orderings and run formation with the code under test (they have their own vectors). Everything from the left-out
 * runs on is written again here in the most direct way: linear scans, no prefix array, no binary search, and the end of
 * the day as "first fit, repeat" over the visit list instead of a single pass.
 */
internal object DayOracle {
    fun blocks(
        settings: SchedulerSettings,
        items: List<ScheduleItem>,
        n: Long,
    ): List<ScheduleBlock> {
        val dayStart = Epochs.epochStart(settings, n)
        val dayEnd = dayStart + settings.epochMs
        val pool = EpochPool.build(settings, items, n)
        val cycle = cycle(settings, pool, Epochs.seedN(settings.seed, n))
        if (cycle.isEmpty()) return listOf(ScheduleBlock(dayStart, dayEnd, ScheduleContent.Slate))

        val shuffle = settings.orderingMode == OrderingMode.Shuffle
        val first = if (shuffle) 0 else continuousRun(settings, cycle, dayStart)
        val regular = regularRuns(settings, cycle, first, dayStart, dayEnd)
        val out = regular.blocks.toMutableList()
        var cursor = out.lastOrNull()?.endMs ?: dayStart
        // The end of the day: first fit, repeat, over the visit list, starting at the first item of the unstarted run.
        val flat = cycle.flatMap { it.items }
        val firstItem = cycle.take(regular.nextRun).sumOf { it.items.size }
        var candidates =
            if (shuffle) List(flat.size) { flat[(firstItem + it) % flat.size] } else flat.drop(firstItem)
        // Only the modes with the group rule remember the groups they passed over.
        val blocked = if (shuffle) null else mutableSetOf<CanonicalId>()
        while (cursor < dayEnd) {
            val at = firstFit(settings, candidates, blocked, cursor, dayEnd)
            if (at < 0) break
            val item = candidates[at]
            val start = markAtOrAfter(settings, cursor)
            if (start > cursor) out.add(ScheduleBlock(cursor, start, ScheduleContent.Slate))
            out.add(ScheduleBlock(start, start + item.runtimeMs, ScheduleContent.Program(item.id)))
            cursor = start + item.runtimeMs
            candidates = candidates.drop(at + 1)
        }
        if (cursor < dayEnd) out.add(ScheduleBlock(cursor, dayEnd, ScheduleContent.Slate))
        return out
    }

    /** One cycle of runs without the runs whose slot is longer than the epoch, judged by the oracle's own slots. */
    fun cycle(
        settings: SchedulerSettings,
        pool: List<ScheduleItem>,
        seedN: ULong,
    ): List<Run> = Runs.cycle(settings, pool, seedN).filter { slot(settings, it) <= settings.epochMs }

    /** Blocks of the runs that fit, and the index of the first run that did not (in the cycle, after wrapping). */
    private class Regular(
        val blocks: List<ScheduleBlock>,
        val nextRun: Int,
    )

    private fun regularRuns(
        settings: SchedulerSettings,
        cycle: List<Run>,
        first: Int,
        dayStart: Long,
        dayEnd: Long,
    ): Regular {
        val out = mutableListOf<ScheduleBlock>()
        var cursor = dayStart
        var runIndex = first
        while (true) {
            val run = cycle[runIndex]
            val start = markAtOrAfter(settings, cursor)
            if (start + run.runtimeMs > dayEnd) return Regular(out, runIndex)
            if (start > cursor) out.add(ScheduleBlock(cursor, start, ScheduleContent.Slate))
            cursor = start
            for (item in run.items) {
                out.add(ScheduleBlock(cursor, cursor + item.runtimeMs, ScheduleContent.Program(item.id)))
                cursor += item.runtimeMs
            }
            runIndex = (runIndex + 1) % cycle.size
        }
    }

    /**
     * The index of the first candidate that is eligible and fits now, or -1. Every candidate before it was passed over,
     * and that blocks its group for the later ones (the group rule; [blocked] is null for shuffle), including later
     * ones in this same scan.
     */
    private fun firstFit(
        settings: SchedulerSettings,
        candidates: List<ScheduleItem>,
        blocked: MutableSet<CanonicalId>?,
        cursor: Long,
        dayEnd: Long,
    ): Int {
        val start = markAtOrAfter(settings, cursor)
        for ((i, candidate) in candidates.withIndex()) {
            val eligible = blocked == null || candidate.groupId !in blocked
            if (eligible && start + candidate.runtimeMs <= dayEnd) return i
            blocked?.add(candidate.groupId)
        }
        return -1
    }

    fun slot(
        settings: SchedulerSettings,
        run: Run,
    ): Long {
        if (!settings.alignToGrid) return run.runtimeMs
        var slots = 0L
        while (slots * settings.gridMs < run.runtimeMs) slots++
        return slots * settings.gridMs
    }

    /** The run of the continuous playout that contains [dayStart]: the last run whose slot begins at or before pos. */
    fun continuousRun(
        settings: SchedulerSettings,
        cycle: List<Run>,
        dayStart: Long,
    ): Int {
        val total = cycle.sumOf { slot(settings, it) }
        val pos = ((dayStart + settings.offsetMs) % total + total) % total
        var begin = 0L
        var found = 0
        for (i in cycle.indices) {
            if (begin <= pos) found = i
            begin += slot(settings, cycle[i])
        }
        return found
    }

    fun markAtOrAfter(
        settings: SchedulerSettings,
        t: Long,
    ): Long {
        if (!settings.alignToGrid) return t
        val remainder = (((t + settings.offsetMs) % settings.gridMs) + settings.gridMs) % settings.gridMs
        return if (remainder == 0L) t else t + settings.gridMs - remainder
    }
}
