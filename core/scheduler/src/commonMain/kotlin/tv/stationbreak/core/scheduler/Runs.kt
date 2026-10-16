// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings

/**
 * A run: the `blockLength` group of items played back to back as one or more blocks. The format calls it a run so that
 * the word "block" means one thing, a `ScheduleBlock`.
 */
class Run(
    val items: List<ScheduleItem>,
) {
    /** The sum of the items' runtimes: they play back to back. */
    val runtimeMs: Long = items.sumOf { it.runtimeMs }
}

/** Run formation (spec 08, Unit 2). */
object Runs {
    /**
     * Cuts [order], one cycle of a `shuffle` or `chronological` order, into runs of [blockLength] consecutive items,
     * counted from the cycle's first item. The last run may be shorter. Runs never span the cycle's wrap, because the
     * count starts again with each cycle.
     */
    fun form(
        order: List<ScheduleItem>,
        blockLength: Int,
    ): List<Run> {
        require(blockLength >= 1) { "A run is at least one item" }
        return order.chunked(blockLength).map { Run(it) }
    }

    /**
     * The runs of one cycle of [pool] in the settings' ordering mode. [seedN] is the epoch's seed, and only `shuffle`
     * uses it: `randomInOrder` keys its groups with the channel seed, so its walk is the same every day. For
     * `randomInOrder`, one turn is one run.
     */
    fun cycle(
        settings: SchedulerSettings,
        pool: List<ScheduleItem>,
        seedN: ULong,
    ): List<Run> =
        when (settings.orderingMode) {
            OrderingMode.Shuffle -> {
                form(Orderings.shuffle(pool, seedN), settings.blockLength)
            }

            OrderingMode.Chronological -> {
                form(Orderings.chronological(pool), settings.blockLength)
            }

            OrderingMode.RandomInOrder -> {
                Orderings.randomInOrderTurns(pool, settings.seed, settings.blockLength).map { Run(it) }
            }
        }
}
