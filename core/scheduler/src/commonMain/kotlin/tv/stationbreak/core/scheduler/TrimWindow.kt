// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.TitleKeeps

/**
 * The trim window (docs/rules-format.md, "The pool of an epoch"): the last step of the pool of a day, in every mode.
 *
 * For a series with a cap in `keeps`, the episodes of the pool in [Orderings.inGroupKey] order are `e[0] .. e[s - 1]`;
 * `k` is the largest count whose first `k` runtimes sum to at most the cap, and at least one. When `k < s` the pool
 * keeps the `k` episodes `e[floorMod(start + j, s)]`, with `start = floorMod(floorMod(n, s) * k, s)`. Films, episodes
 * with no series and series with no cap are untouched. Integer arithmetic only.
 */
internal object TrimWindow {
    /**
     * [pool] with the window of day [n] applied, in canonical ID order. The same [pool] when [keeps] is empty. The
     * window is applied once, in the day build; a pool that was kept is restored with [TitleKeeps.NONE].
     */
    fun apply(
        pool: List<ScheduleItem>,
        n: Long,
        keeps: TitleKeeps,
    ): List<ScheduleItem> {
        val ordered = if (keeps.isEmpty || isAscending(pool)) pool else pool.sortedBy { it.id.value }
        val dropped = if (keeps.isEmpty) emptySet() else droppedBy(ordered, n, keeps)
        if (dropped.isEmpty()) return ordered
        val trimmed = ArrayList<ScheduleItem>(ordered.size)
        for (item in ordered) if (item.id !in dropped) trimmed.add(item)
        return trimmed
    }

    // The IDs the window of day [n] takes out of [pool], over every series that has a cap.
    private fun droppedBy(
        pool: List<ScheduleItem>,
        n: Long,
        keeps: TitleKeeps,
    ): Set<CanonicalId> {
        val episodes = HashMap<CanonicalId, ArrayList<ScheduleItem>>()
        for (item in pool) {
            val series = item.seriesId
            val capped = item.kind == ScheduleItemKind.Episode && series != null && keeps[series] != null
            if (capped) episodes.getOrPut(checkNotNull(series)) { ArrayList() }.add(item)
        }
        val dropped = HashSet<CanonicalId>()
        for ((series, members) in episodes) addDropped(dropped, members, checkNotNull(keeps[series]), n)
        return dropped
    }

    private fun addDropped(
        dropped: MutableSet<CanonicalId>,
        members: ArrayList<ScheduleItem>,
        cap: Long,
        n: Long,
    ) {
        members.sortWith(Orderings.inGroupKey)
        val size = members.size.toLong()
        val count = prefixCount(members, cap)
        if (count >= size) return
        val start = (n.mod(size) * count).mod(size)
        val kept = BooleanArray(members.size)
        for (j in 0 until count) kept[((start + j) % size).toInt()] = true
        for (index in members.indices) if (!kept[index]) dropped.add(members[index].id)
    }

    private fun isAscending(items: List<ScheduleItem>): Boolean {
        for (index in 1 until items.size) if (items[index - 1].id.value >= items[index].id.value) return false
        return true
    }

    // The largest j in 1..size whose first j runtimes sum to at most the cap; 1 when even the first is over.
    private fun prefixCount(
        members: List<ScheduleItem>,
        cap: Long,
    ): Int {
        var sum = 0L
        var count = 0
        for (member in members) {
            sum += member.runtimeMs
            if (sum > cap) break
            count++
        }
        return maxOf(count, 1)
    }
}
