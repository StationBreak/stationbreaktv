// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.model.schedule.TitleWeights

/**
 * Lays out the rest of day [n] from [fromMs]: blocks that cover exactly `[fromMs, epochEnd(n))`, made by the same
 * `Timeline` the whole day uses, over the cycle of [pool] (already the day's pool: no trim window is applied here).
 * A run is left out only when every one of its items is [aired], unless that would empty the cycle; so a partly aired
 * run replays its aired items. In chronological ordering a run that is mid-air at [fromMs] starts again from its
 * beginning, not mid-run. An empty cycle gives one slate. [fromMs] must be in `[epochStart(n), epochEnd(n)]`; at
 * `epochEnd(n)` it returns an empty list, so the caller's kept blocks must already cover the day.
 */
@Suppress("LongParameterList") // The day's inputs plus the moment and what has aired.
fun layoutTail(
    settings: SchedulerSettings,
    pool: List<ScheduleItem>,
    n: Long,
    films: FilmCollections,
    weights: TitleWeights,
    fromMs: Long,
    aired: Set<CanonicalId>,
): List<ScheduleBlock> {
    val start = Epochs.epochStart(settings, n)
    val end = start + settings.epochMs
    require(fromMs in start..end) { "Time $fromMs is outside day $n [$start, $end]" }
    val groups = GroupRules(films, weights)
    val full = Timeline.cycle(settings, pool, Epochs.seedN(settings.seed, n), groups)
    val cycle = full.filterNot { run -> run.items.all { it.id in aired } }.ifEmpty { full }
    return when {
        fromMs == end -> {
            emptyList()
        }

        cycle.isEmpty() -> {
            listOf(ScheduleBlock(fromMs, end, ScheduleContent.Slate))
        }

        else -> {
            val startRun = Timeline.startRun(settings, cycle, fromMs)
            Timeline.layout(settings, cycle, startRun, fromMs, end, groups).blocks
        }
    }
}

/**
 * A day of epoch [n] made from [blocks], which must be contiguous half-open blocks covering exactly `[startMs, endMs)`;
 * otherwise [IllegalArgumentException]. There is no empty list and no zero-length block, so callers omit an empty kept
 * window and a zero-length past slate. [pool] is the pool the blocks were laid from.
 */
fun composedDay(
    n: Long,
    startMs: Long,
    endMs: Long,
    blocks: List<ScheduleBlock>,
    pool: List<ScheduleItem>,
): Day {
    require(blocks.isNotEmpty()) { "A day has at least one block" }
    require(blocks.first().startMs == startMs) { "The first block must start at $startMs" }
    require(blocks.last().endMs == endMs) { "The last block must end at $endMs" }
    for (i in blocks.indices) {
        require(blocks[i].startMs < blocks[i].endMs) { "Block $i is empty" }
        if (i > 0) require(blocks[i - 1].endMs == blocks[i].startMs) { "Blocks ${i - 1} and $i are not contiguous" }
    }
    return Day(n, startMs, endMs, blocks.toList(), pool, RunPlan(0, 0, 0))
}
