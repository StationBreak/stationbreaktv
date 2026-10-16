// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.schedule.SchedulerSettings

/**
 * Epoch arithmetic, exactly as docs/rules-format.md ("Timing") defines it. Every value is a 64-bit `Long` and every
 * division rounds toward negative infinity, so it is right for times before 1970 and for every offset and epoch length.
 */
object Epochs {
    /**
     * The epoch number `n` that contains [t] (milliseconds since the Unix epoch):
     * `floor((t + offsetMs - startMs) / epochMs)`.
     */
    fun epochNumber(
        settings: SchedulerSettings,
        t: Long,
    ): Long = (t + settings.offsetMs - settings.startMs).floorDiv(settings.epochMs)

    /** When epoch [n] starts: `n * epochMs + startMs - offsetMs`, in milliseconds since the Unix epoch. */
    fun epochStart(
        settings: SchedulerSettings,
        n: Long,
    ): Long = n * settings.epochMs + settings.startMs - settings.offsetMs

    /**
     * True when [t] is a grid mark of the channel's own clock: `floorMod(t + offsetMs, gridMs) == 0`. Every epoch start
     * is a grid mark, because the start is on the grid and the epoch length is a whole number of hours.
     */
    fun isGridMark(
        settings: SchedulerSettings,
        t: Long,
    ): Boolean = (t + settings.offsetMs).mod(settings.gridMs) == 0L
}
