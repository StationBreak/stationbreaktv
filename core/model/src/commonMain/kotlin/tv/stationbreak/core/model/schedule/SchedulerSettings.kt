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
 * - [blockLength] is 1 to 100;
 * - [minRuntimeMs] is 1,000 to 3,600,000 (one second to one hour);
 * - [orderingMode] `Rotation` needs [caps], and [caps] need a 24-hour epoch.
 *
 * @property minRuntimeMs the shortest runtime that counts as a program (`timing.minRuntimeSeconds` in ms): the pool
 *   leaves out anything shorter, so a day holds at most `epochMs / minRuntimeMs` programs.
 * @property caps the play-in-a-row caps, or null when none are in force (the mode ignores them)
 * @property runtimeFilter the `filters.runtime` window, or null: an item outside it does not air
 * @property curated the tunables of the `curated` order: set exactly when [orderingMode] is `Curated`, so no other
 *   mode carries numbers it never reads
 * @property startMinuteOfDay when the broadcast day starts: minutes after midnight on the channel's clock, 0..1439.
 * @property leftover where the time left at the end of a broadcast day goes ([LeftoverPolicy]): `Spread` for rules
 *   that say `formatVersion` 5 or more, `Gather` otherwise.
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
    val minRuntimeMs: Long,
    val caps: Caps?,
    val runtimeFilter: RuntimeFilter?,
    val curated: CuratedTuning?,
    val leftover: LeftoverPolicy,
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
        require(minRuntimeMs in MIN_RUNTIME_MS..MAX_RUNTIME_MS) { "The minimum runtime is 1 second to 1 hour" }
        require((orderingMode == OrderingMode.Curated) == (curated != null)) {
            "The curated tuning is given exactly with the curated order"
        }
        require(orderingMode != OrderingMode.Rotation || caps != null) { "Rotation needs caps" }
        require(caps == null || epochHours == MAX_EPOCH_HOURS) { "Caps need a 24-hour epoch" }
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

    /**
     * True when a program of [runtimeMs] and [kind] can air: `minRuntimeMs <= runtimeMs <= epochMs`, and inside
     * [runtimeFilter]
     * when there is one. A shorter item is not a program, and a longer one can never play without running over its
     * epoch. The one place this rule is written.
     */
    fun isAirable(
        runtimeMs: Long,
        kind: ScheduleItemKind,
    ): Boolean {
        val inFilter = runtimeFilter?.admits(runtimeMs, kind) != false
        return runtimeMs in minRuntimeMs..epochMs && inFilter
    }

    companion object {
        private const val MS_PER_MINUTE = 60_000L
        private const val MS_PER_HOUR = 3_600_000L
        private const val MAX_EPOCH_HOURS = 24
        private const val MINUTES_PER_DAY = 1440
        private const val MIN_BLOCK_LENGTH = 1
        private const val MAX_BLOCK_LENGTH = 100
        private const val MIN_OFFSET = -720
        private const val MAX_OFFSET = 840
        private const val OFFSET_STEP = 15
        private const val MAX_GRACE_HOURS = 48
        private const val MIN_RUNTIME_MS = 1_000L
        private const val MAX_RUNTIME_MS = 3_600_000L

        /** The longest epoch the format allows, in milliseconds (24 h). */
        const val MAX_EPOCH_MS: Long = MAX_EPOCH_HOURS * MS_PER_HOUR

        /** The largest grace window the format allows, in milliseconds (48 h). */
        const val MAX_GRACE_MS: Long = MAX_GRACE_HOURS * MS_PER_HOUR

        private val GRID_MINUTES = setOf(15, 30, 60)
        private val EPOCH_HOURS = setOf(1, 2, 3, 4, 6, 8, 12, 24)
    }
}

/**
 * Where the time a broadcast day cannot fill with programmes goes.
 * - [Gather]: all of it at the end of the day, before the next day starts (rules `formatVersion` 1 to 4).
 * - [Spread]: spread through the day as station breaks between programmes (rules `formatVersion` 5, the spread day
 *   end).
 */
enum class LeftoverPolicy {
    Gather,
    Spread,
}

/**
 * The two tunables of `curated` in milliseconds (`ordering.repeatGapMinutes` and `ordering.pairUpToMinutes`):
 * [repeatGapMs] is the shortest time before a title or franchise airs again (0 to 24 h), [pairUpToMs] the longest
 * episode paired with the next (1 minute to 24 h). No defaults here either: they live in `core:rules`.
 */
data class CuratedTuning(
    val repeatGapMs: Long,
    val pairUpToMs: Long,
) {
    init {
        require(repeatGapMs in 0..SchedulerSettings.MAX_EPOCH_MS) { "The repeat gap is 0 to 1440 minutes" }
        require(pairUpToMs in MS_PER_MINUTE..SchedulerSettings.MAX_EPOCH_MS) {
            "The pair-up length is 1 to 1440 minutes"
        }
    }

    private companion object {
        private const val MS_PER_MINUTE = 60_000L
    }
}
