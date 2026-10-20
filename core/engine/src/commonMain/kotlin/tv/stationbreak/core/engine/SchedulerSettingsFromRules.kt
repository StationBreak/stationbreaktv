// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Timing

private const val MS_PER_SECOND = 1_000L
private const val MINUTES_PER_HOUR = 60

/**
 * The scheduler's numbers for a channel: its ordering, seed and timing with the rules-format defaults applied
 * (`effective…`), so the defaults are written once, in `core:rules`.
 */
fun ChannelRules.toSchedulerSettings(): SchedulerSettings = settingsOf(orderingMode, seed, timing)

internal fun settingsOf(
    orderingMode: OrderingMode,
    seed: Seed,
    timing: Timing,
): SchedulerSettings =
    SchedulerSettings(
        orderingMode = orderingMode,
        seed = seed,
        blockLength = timing.effectiveBlockLength,
        alignToGrid = timing.effectiveAlignToGrid,
        gridMinutes = timing.effectiveGridMinutes,
        epochHours = timing.effectiveEpochHours,
        utcOffsetMinutes = timing.broadcastDay.utcOffsetMinutes,
        startMinuteOfDay = minuteOfDay(timing.broadcastDay.effectiveStart),
        graceHours = timing.effectiveGraceHours,
        minRuntimeMs = timing.effectiveMinRuntimeSeconds * MS_PER_SECOND,
    )

// The start is "HH:MM", already checked by BroadcastDay.
private fun minuteOfDay(start: String): Int {
    val (hours, minutes) = start.split(':')
    return hours.toInt() * MINUTES_PER_HOUR + minutes.toInt()
}

/** The total runtime of the [items] that can air under [settings] (`SchedulerSettings.isAirable`). */
fun airableRuntimeMs(
    items: List<ScheduleItem>,
    settings: SchedulerSettings,
): Long = items.sumOf { if (settings.isAirable(it.runtimeMs)) it.runtimeMs else 0L }
