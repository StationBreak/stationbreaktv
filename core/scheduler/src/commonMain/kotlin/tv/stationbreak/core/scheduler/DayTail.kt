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
 * Runs whose items were all [aired] are left out, unless that would empty the cycle. An empty cycle gives one slate.
 * [fromMs] must be in `[epochStart(n), epochEnd(n)]`; at the end it gives no blocks.
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
 * otherwise [IllegalArgumentException]. [pool] is the pool the blocks were laid from.
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
