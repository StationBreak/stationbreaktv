// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

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
    return Day(n, start, end, listOf(ScheduleBlock(start, end, ScheduleContent.Slate)), 0, 0, 0, onCompare)
}
