// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings

/** The target spacing of the breaks: one per 150 minutes of the laid-out window. */
internal const val BREAK_SPACING_MS = 9_000_000L

/** The shortest card share, 60 s, unless the whole remainder is shorter. */
internal const val MIN_CARD_MS = 60_000L

/**
 * The spread end of the day (rules format version 5): the time the regular layout leaves before [layoutEnd] is filled
 * with a best fit of the items the planned end may visit and spread as station breaks at run boundaries. Returns every
 * block of `[layoutStart, layoutEnd)`: the [regular] blocks (moved later by the breaks before them) with the breaks.
 *
 * - [runEnds]: the end of each run of the regular layout, in order; the last is `c`, the end of the regular layout.
 * - [visit]: the items the planned end visits, from the first item of the run that did not fit; [firstItem] is that
 *   item's index in the cycle's items, so a visit item's cycle index is `(firstItem + v) mod visit.size` in the modes
 *   that wrap.
 * - [aired]: what aired before [layoutStart] (`layoutTail`'s `pastAirings`) and every programme of [regular], each with
 *   its end before any shift. An airing that ends after [layoutStart] is one of the regular layout's.
 */
@Suppress("LongParameterList") // The layout's facts, one for one.
internal fun spread(
    settings: SchedulerSettings,
    regular: List<ScheduleBlock>,
    runEnds: List<Long>,
    visit: List<ScheduleItem>,
    firstItem: Int,
    aired: List<PastAiring>,
    groups: GroupRules,
    layoutStart: Long,
    layoutEnd: Long,
): List<ScheduleBlock> {
    val end = SpreadEnd(settings, runEnds, visit, firstItem, aired, groups, layoutStart, layoutEnd)
    return if (settings.alignToGrid) end.onGrid(regular) else end.spread(regular)
}

/** The inputs of [spread] that every step reads, and the steps both layouts share. */
@Suppress("LongParameterList") // The layout's facts, one for one.
internal class SpreadEnd(
    val settings: SchedulerSettings,
    val runEnds: List<Long>,
    val visit: List<ScheduleItem>,
    val firstItem: Int,
    val aired: List<PastAiring>,
    val groups: GroupRules,
    val start: Long,
    val end: Long,
) {
    private val mode = settings.orderingMode

    /** `shuffle` and `curated` wrap the visit and spread their fill; the other modes are ordered. */
    val wraps = mode == OrderingMode.Shuffle || mode == OrderingMode.Curated

    /** The end of the regular layout. */
    val c: Long = runEnds.lastOrNull() ?: start

    /** The group an item is judged by: the walk's group, the series or the item, or for `shuffle` the item. */
    fun groupKey(item: ScheduleItem): CanonicalId =
        when (mode) {
            OrderingMode.Curated, OrderingMode.Rotation -> groups.groupId(item)
            OrderingMode.Shuffle -> item.id
            OrderingMode.Chronological, OrderingMode.RandomInOrder, OrderingMode.InOrder -> item.groupId
        }

    /**
     * Today's planned end as a selection, in visit indices: from `c`, each item at the next grid mark when aligned,
     * taken when it ends by [end]; in the ordered modes a group is passed over at its first item that does not fit.
     */
    fun firstFit(): List<Int> {
        val passedOver = HashSet<CanonicalId>()
        val chosen = ArrayList<Int>()
        var cursor = c
        for (v in visit.indices) {
            val item = visit[v]
            val group = groupKey(item)
            if (!wraps && group in passedOver) continue
            val at = Timeline.startAtOrAfter(settings, cursor)
            if (at + item.runtimeMs <= end) {
                chosen += v
                cursor = at + item.runtimeMs
            } else if (!wraps) {
                passedOver += group
            }
        }
        return chosen
    }

    /** The visit indices of the items that have not aired (by canonical ID), in visit order. */
    fun unaired(): List<Int> {
        val airedIds = aired.mapTo(HashSet()) { it.item.id }
        return visit.indices.filter { visit[it].id !in airedIds }
    }

    /** [bestFit] over the visit items at [subset] (visit indices, ascending), as visit indices. */
    fun bestFitOf(
        subset: List<Int>,
        budgetMs: Long,
        weightMs: (ScheduleItem) -> Long,
    ): List<Int> = bestFit(itemsAt(subset), budgetMs, weightMs).map { subset[it] }

    /** The visit items at [indices]. */
    fun itemsAt(indices: List<Int>): List<ScheduleItem> = indices.map { visit[it] }

    /** The cycle index of visit item [v]: the walk's order. */
    fun cycleIndex(v: Int): Int = (firstItem + v) % visit.size

    /**
     * The earliest break point each group may take, from what aired: in `curated` its last airing's end plus
     * `repeatGapMs`; in `shuffle` just after the end of the run of its last airing (for an airing before [start],
     * its own end). Groups with no airing are absent.
     */
    fun earliestPoints(): Map<CanonicalId, Long> {
        val out = HashMap<CanonicalId, Long>()
        val gap = settings.curated?.repeatGapMs ?: 0L
        for (a in aired) {
            val earliest = if (mode == OrderingMode.Curated) a.endMs + gap else runEnd(a) + 1
            val key = groupKey(a.item)
            out[key] = maxOf(out[key] ?: Long.MIN_VALUE, earliest)
        }
        return out
    }

    // The end of the run [a] aired in: an entry of runEnds for the regular layout's airings, its own end otherwise.
    private fun runEnd(a: PastAiring): Long {
        if (a.endMs <= start) return a.endMs
        var low = 0
        var high = runEnds.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (runEnds[mid] >= a.endMs) high = mid else low = mid + 1
        }
        return runEnds[low]
    }
}

/**
 * Grid first: today's planned end with the best fit by slot length added (in `shuffle` and `curated` the best fit of
 * the unaired items unless all items fill more), and no spreading. The kept selection is laid
 * out as the planned end lays it (each item at the next grid mark, gaps as slates), then one break to [SpreadEnd.end].
 */
internal fun SpreadEnd.onGrid(regular: List<ScheduleBlock>): List<ScheduleBlock> {
    val budget = end - Timeline.startAtOrAfter(settings, c)
    val slot = { item: ScheduleItem -> slotOf(item) }
    val chosen =
        if (wraps) {
            // As off the grid, the items that have not aired come first: their best fit is kept unless the best fit of
            // all fills more slot time (ties to the unaired).
            val fresh = bestFitOf(unaired(), budget, slot)
            val any = bestFit(visit, budget, slot)
            if (any.sumOf { slotOf(visit[it]) } > fresh.sumOf { slotOf(visit[it]) }) any else fresh
        } else {
            bestGroupedFit(visit, { groupKey(it) }, budget, slot)
        }
    val withExtra = chosen + listOfNotNull(extraItem(chosen))
    val first = firstFit()
    val kept = if (endOnGrid(first) > endOnGrid(withExtra)) first else withExtra
    val out = ArrayList(regular)
    var cursor = c
    for (v in kept) {
        val item = visit[v]
        val at = Timeline.startAtOrAfter(settings, cursor)
        if (at > cursor) out += ScheduleBlock(cursor, at, ScheduleContent.Slate)
        out += ScheduleBlock(at, at + item.runtimeMs, ScheduleContent.Program(item.id))
        cursor = at + item.runtimeMs
    }
    if (cursor < end) out += ScheduleBlock(cursor, end, ScheduleContent.Break)
    return out
}

// An item's runtime rounded up to the grid.
private fun SpreadEnd.slotOf(item: ScheduleItem): Long {
    val grid = settings.gridMs
    return (item.runtimeMs + grid - 1).floorDiv(grid) * grid
}

// Where [selection] ends when laid out from c, each item at the next grid mark.
private fun SpreadEnd.endOnGrid(selection: List<Int>): Long {
    var cursor = c
    for (v in selection) cursor = Timeline.startAtOrAfter(settings, cursor) + visit[v].runtimeMs
    return cursor
}

/**
 * The extra pass: after [chosen], the first eligible visit item that still ends by the end from the next grid mark, or
 * null. In the ordered modes only the item right after its group's chosen prefix (or a group's first item when none of
 * it is chosen) is eligible; in `shuffle` and `curated` any item not chosen.
 */
private fun SpreadEnd.extraItem(chosen: List<Int>): Int? {
    val from = Timeline.startAtOrAfter(settings, endOnGrid(chosen))
    val chosenIds = chosen.mapTo(HashSet()) { visit[it].id }
    val prefix = HashMap<CanonicalId, Int>()
    for (v in chosen) prefix[groupKey(visit[v])] = (prefix[groupKey(visit[v])] ?: 0) + 1
    val seen = HashMap<CanonicalId, Int>()
    for (v in visit.indices) {
        val item = visit[v]
        val eligible =
            if (wraps) {
                item.id !in chosenIds
            } else {
                val group = groupKey(item)
                val position = seen[group] ?: 0
                seen[group] = position + 1
                position == (prefix[group] ?: 0)
            }
        if (eligible && from + item.runtimeMs <= end) return v
    }
    return null
}
