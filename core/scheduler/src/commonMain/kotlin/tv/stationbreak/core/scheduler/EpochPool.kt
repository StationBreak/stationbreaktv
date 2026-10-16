// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings

/**
 * Which items count for epoch `n` (spec 08, Unit 1).
 *
 * With `S = epochStart(n)` and `G = graceHours * 3,600,000`, an item is in the pool when:
 * - `createdAt < S - G` (an item added inside the grace window waits for the next epoch);
 * - `deletedAt` is absent, or `deletedAt >= S - G` (an item deleted shortly before `S` still airs in epoch `n`, so
 *   the lineup does not shift; the player skips it);
 * - `0 < runtime <= epochMs` (a longer item can never play without running over, so it is left out).
 *
 * `deletedAt` is a client's first observation that the item is gone. Jellyfin gives no deletion time, so two
 * devices can disagree on epoch `n` about items deleted in `[S - 2G, S - G)`.
 */
object EpochPool {
    /**
     * The pool of epoch [n] from [items], in canonical ID order (ascending, as 32 ASCII bytes). Throws
     * [IllegalArgumentException] naming the ID when two inputs share one, whether or not either would be kept.
     */
    fun build(
        settings: SchedulerSettings,
        items: List<ScheduleItem>,
        n: Long,
    ): List<ScheduleItem> {
        requireUniqueIds(items)
        val cutoff = Epochs.epochStart(settings, n) - settings.graceMs
        return items
            .filter { isInPool(it, cutoff, settings.epochMs) }
            .sortedBy { it.id.value }
    }

    private fun isInPool(
        item: ScheduleItem,
        cutoff: Long,
        epochMs: Long,
    ): Boolean {
        val deleted = item.deletedAtMs
        return item.createdAtMs < cutoff &&
            (deleted == null || deleted >= cutoff) &&
            item.runtimeMs > 0L &&
            item.runtimeMs <= epochMs
    }

    private fun requireUniqueIds(items: List<ScheduleItem>) {
        val seen = HashSet<String>(items.size * 2)
        for (item in items) {
            require(seen.add(item.id.value)) { "Duplicate item ID ${item.id.value}: an item may be given only once" }
        }
    }
}
