// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed

/**
 * What the scheduler needs from a channel's rules (docs/rules-format.md, "Timing"), in numbers.
 *
 * **There are no defaults here, on purpose.** The defaults of the optional rules fields live only in `core:rules`,
 * so the two cannot drift apart. Whoever builds this type (F1.2's engine) spells out every field.
 *
 * It validates itself when built:
 * - [gridMinutes] is 15, 30 or 60, and [startMinuteOfDay] is a multiple of it (so the day starts on a grid mark);
 * - [epochHours] is one of 1, 2, 3, 4, 6, 8, 12 or 24;
 * - [utcOffsetMinutes] is -720 to 840 and a multiple of 15;
 * - [graceHours] is 0 to 48;
 * - [blockLength] is 1 to 100.
 *
 * @property startMinuteOfDay when the broadcast day starts: minutes after midnight on the channel's clock, 0..1439.
 */
data class SchedulerSettings(
    val orderingMode: OrderingMode,
    val seed: Seed,
    val blockLength: Int,
    val alignToGrid: Boolean,
    val gridMinutes: Int,
    val epochHours: Int,
    val utcOffsetMinutes: Int,
    val startMinuteOfDay: Int,
    val graceHours: Int,
) {
    init {
        require(blockLength in MIN_BLOCK_LENGTH..MAX_BLOCK_LENGTH) { "A run is 1 to 100 items" }
        require(gridMinutes in GRID_MINUTES) { "The grid is 15, 30 or 60 minutes" }
        require(epochHours in EPOCH_HOURS) { "An epoch length must divide 24" }
        require(utcOffsetMinutes in MIN_OFFSET..MAX_OFFSET) { "The offset is -720 to 840 minutes" }
        require(utcOffsetMinutes % OFFSET_STEP == 0) { "The offset is a multiple of 15 minutes" }
        require(startMinuteOfDay in 0 until MINUTES_PER_DAY) { "The start is inside the day" }
        require(startMinuteOfDay % gridMinutes == 0) { "The start must be on the grid" }
        require(graceHours in 0..MAX_GRACE_HOURS) { "Grace is 0 to 48 hours" }
    }

    /** The grid spacing in milliseconds. */
    val gridMs: Long get() = gridMinutes * MS_PER_MINUTE

    /** The epoch length in milliseconds. */
    val epochMs: Long get() = epochHours * MS_PER_HOUR

    /** The fixed UTC offset in milliseconds, east positive. */
    val offsetMs: Long get() = utcOffsetMinutes * MS_PER_MINUTE

    /** The start of the broadcast day, in milliseconds after midnight on the channel's own clock. */
    val startMs: Long get() = startMinuteOfDay * MS_PER_MINUTE

    /** The grace window G in milliseconds. */
    val graceMs: Long get() = graceHours * MS_PER_HOUR

    companion object {
        private const val MS_PER_MINUTE = 60_000L
        private const val MS_PER_HOUR = 3_600_000L
        private const val MINUTES_PER_DAY = 1440
        private const val MIN_BLOCK_LENGTH = 1
        private const val MAX_BLOCK_LENGTH = 100
        private const val MIN_OFFSET = -720
        private const val MAX_OFFSET = 840
        private const val OFFSET_STEP = 15
        private const val MAX_GRACE_HOURS = 48
        private val GRID_MINUTES = setOf(15, 30, 60)
        private val EPOCH_HOURS = setOf(1, 2, 3, 4, 6, 8, 12, 24)
    }
}
