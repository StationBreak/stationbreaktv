// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.schedule.ScheduleItem

/**
 * A programme that aired today, as the viewer saw it: [item] and the moment it ended ([endMs]). `layoutTail` is given
 * every programme that aired before the tail starts, so the spread end of the day (rules format version 5) can keep a
 * short that aired from playing again too soon.
 */
data class PastAiring(
    val item: ScheduleItem,
    val endMs: Long,
)
