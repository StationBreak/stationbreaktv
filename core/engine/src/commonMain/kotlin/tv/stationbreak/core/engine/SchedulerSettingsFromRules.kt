// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.RuntimeFilter
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Timing

private const val MS_PER_SECOND = 1_000L
private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60

/**
 * The scheduler's numbers for a channel: its ordering, seed and timing with the rules-format defaults applied
 * (`effective…`), so the defaults are written once, in `core:rules`. The caps are the caps in force
 * ([ChannelRules.effectiveCaps]: `rotation`, or a `shuffle` that has them), and the runtime filter is in milliseconds.
 */
fun ChannelRules.toSchedulerSettings(): SchedulerSettings {
    // Format 3 is read and written here but not yet scheduled (spec 23, Task 7 removes this).
    require(orderingMode != OrderingMode.Curated) { "format 3 is scheduled from Task 7" }
    return settingsOf(
        orderingMode,
        seed,
        timing,
        caps = effectiveCaps?.let { Caps(it.effectiveSeries, it.effectiveFilm) },
        runtimeFilter = filters?.let { runtimeFilterOf(it) },
    )
}

private fun runtimeFilterOf(filters: Filters): RuntimeFilter? {
    // A window for one kind is applied from Task 6; until then it must not be ignored.
    check(!filters.hasKindRuntime) { "format 3 is scheduled from Task 7" }
    return generalRuntimeFilter(filters)
}

private fun generalRuntimeFilter(filters: Filters): RuntimeFilter? =
    if (filters.minRuntimeMinutes == null && filters.maxRuntimeMinutes == null) {
        null
    } else {
        RuntimeFilter(
            minMs = filters.minRuntimeMinutes?.let { it * MS_PER_MINUTE },
            maxMs = filters.maxRuntimeMinutes?.let { it * MS_PER_MINUTE },
        )
    }

internal fun settingsOf(
    orderingMode: OrderingMode,
    seed: Seed,
    timing: Timing,
    caps: Caps?,
    runtimeFilter: RuntimeFilter?,
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
        caps = caps,
        runtimeFilter = runtimeFilter,
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
