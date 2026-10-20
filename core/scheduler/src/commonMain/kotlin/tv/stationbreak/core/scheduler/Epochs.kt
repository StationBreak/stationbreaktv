// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ordering.splitmix64
import tv.stationbreak.core.model.schedule.SchedulerSettings

private const val GOLDEN_GAMMA = 0x9E3779B97F4A7C15uL

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
     * The pool cut-off of epoch [n]: `epochStart(n) - graceMs`. An item added before it is old enough to air in epoch
     * `n`; one deleted before it is gone. The one place this subtraction is written.
     */
    fun cutoff(
        settings: SchedulerSettings,
        n: Long,
    ): Long = epochStart(settings, n) - settings.graceMs

    /**
     * The seed of epoch [n]: `splitmix64(seed + n * 0x9E3779B97F4A7C15)`, wrapping modulo 2^64. [n] is a signed 64-bit
     * number reinterpreted as unsigned (two's complement) before the multiplication, so a negative `n` is well defined.
     */
    fun seedN(
        seed: Seed,
        n: Long,
    ): ULong = splitmix64(seed.value + n.toULong() * GOLDEN_GAMMA)

    /**
     * True when [t] is a grid mark of the channel's own clock: `floorMod(t + offsetMs, gridMs) == 0`. Every epoch start
     * is a grid mark, because the start is on the grid and the epoch length is a whole number of hours.
     */
    fun isGridMark(
        settings: SchedulerSettings,
        t: Long,
    ): Boolean = (t + settings.offsetMs).mod(settings.gridMs) == 0L
}
