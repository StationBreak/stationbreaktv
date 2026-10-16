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
        val all = Runs.cycle(settings, pool, Epochs.seedN(settings.seed, n))
        val cycle = all.filter { slot(settings, it) <= settings.epochMs }
        if (cycle.isEmpty()) return listOf(ScheduleBlock(dayStart, dayEnd, ScheduleContent.Slate))

        val out = mutableListOf<ScheduleBlock>()
        var cursor = dayStart
        var runIndex = if (settings.orderingMode == OrderingMode.Shuffle) 0 else continuousRun(settings, cycle, dayStart)
        while (true) {
            val run = cycle[runIndex]
            val start = markAtOrAfter(settings, cursor)
            if (start + run.runtimeMs > dayEnd) break
            if (start > cursor) out.add(ScheduleBlock(cursor, start, ScheduleContent.Slate))
            cursor = start
            for (item in run.items) {
                out.add(ScheduleBlock(cursor, cursor + item.runtimeMs, ScheduleContent.Program(item.id)))
                cursor += item.runtimeMs
            }
            runIndex = (runIndex + 1) % cycle.size
            if (cursor == dayEnd) return out
        }

        // The end of the day: first fit, repeat, over the visit list.
        val flat = cycle.flatMap { it.items }
        val firstItem = cycle.take(runIndex).sumOf { it.items.size }
        val shuffle = settings.orderingMode == OrderingMode.Shuffle
        var candidates =
            if (shuffle) List(flat.size) { flat[(firstItem + it) % flat.size] } else flat.drop(firstItem)
        val blocked = mutableSetOf<CanonicalId>()
        while (true) {
            val at =
                candidates.indexOfFirst { item ->
                    (shuffle || item.groupId !in blocked) &&
                        markAtOrAfter(settings, cursor) + item.runtimeMs <= dayEnd
                }
            if (at < 0) break
            if (!shuffle) candidates.take(at).forEach { blocked.add(it.groupId) }
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

    fun slot(
        settings: SchedulerSettings,
        run: Run,
    ): Long {
        if (!settings.alignToGrid) return run.runtimeMs
        var slots = 0L
        while (slots * settings.gridMs < run.runtimeMs) slots++
        return slots * settings.gridMs
    }

    /** The run of the continuous playout that contains [dayStart]: the last run whose slot begins at or before `pos`. */
    private fun continuousRun(
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
