// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.SchedulerSettings

internal const val MS_MINUTE = 60_000L
internal const val MS_HOUR = 3_600_000L
internal const val MS_DAY = 86_400_000L

/** Test helper. The settings type has no defaults, so a test that only cares about some fields uses this. */
@Suppress("LongParameterList")
internal fun testSettings(
    orderingMode: OrderingMode = OrderingMode.Shuffle,
    seed: Seed = Seed(1uL),
    blockLength: Int = 1,
    alignToGrid: Boolean = true,
    gridMinutes: Int = 30,
    epochHours: Int = 24,
    utcOffsetMinutes: Int = 0,
    startMinuteOfDay: Int = 360,
    graceHours: Int = 6,
) = SchedulerSettings(
    orderingMode = orderingMode,
    seed = seed,
    blockLength = blockLength,
    alignToGrid = alignToGrid,
    gridMinutes = gridMinutes,
    epochHours = epochHours,
    utcOffsetMinutes = utcOffsetMinutes,
    startMinuteOfDay = startMinuteOfDay,
    graceHours = graceHours,
)
