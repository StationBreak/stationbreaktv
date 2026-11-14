// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.math.abs

private const val MS_PER_SECOND = 1_000L

/**
 * One candidate fill laid out as breaks: the break [points] in order (the last is `c`), the units placed in each break
 * in the order they were placed, each break's card length, the ordered modes' [fill] after the last break's card, and
 * the runtime [placed].
 */
internal class BreakPlan(
    val points: List<Long>,
    val units: List<List<List<ScheduleItem>>>,
    val cards: LongArray,
    val fill: List<ScheduleItem>,
    val placed: Long,
)

/**
 * Off the grid: each candidate fill is laid out as breaks ([plan]) and the one that places the most runtime is kept,
 * ties to the earliest candidate. The ordered modes try the grouped best fit, then first fit; `shuffle` and `curated`
 * the best fit of the items that have not aired, the best fit of all, then first fit.
 */
internal fun SpreadEnd.spread(regular: List<ScheduleBlock>): List<ScheduleBlock> {
    val leftover = end - c
    val all = visit.indices.toList()
    val runtime = { item: ScheduleItem -> item.runtimeMs }
    val candidates =
        if (wraps) {
            val airedIds = aired.mapTo(HashSet()) { it.item.id }
            val unaired = all.filter { visit[it].id !in airedIds }
            listOf(
                indicesOf(bestFit(itemsAt(unaired), leftover, runtime), unaired),
                indicesOf(bestFit(visit, leftover, runtime), all),
                firstFit(),
            )
        } else {
            listOf(indicesOf(bestGroupedFit(visit, { groupKey(it) }, leftover, runtime), all), firstFit())
        }
    val earliest = earliestPoints()
    var best: BreakPlan? = null
    for (fill in candidates) {
        val plan = plan(fill, earliest)
        if (best == null || plan.placed > best.placed) best = plan
    }
    return emit(regular, checkNotNull(best))
}

// S1 to S4 for the candidate [fill] (visit indices).
private fun SpreadEnd.plan(
    fill: List<Int>,
    earliest: Map<CanonicalId, Long>,
): BreakPlan {
    val leftover = end - c
    val sum = fill.sumOf { visit[it].runtimeMs }
    val r0 = leftover - sum
    val n0 = if (wraps) fill.size.toLong() else 0L
    val k = maxOf(1L, minOf((end - start) / BREAK_SPACING_MS, n0 + r0 / MIN_CARD_MS)).toInt()
    val points = points(k)
    val units = List(points.size) { ArrayList<List<ScheduleItem>>() }
    val placed = if (wraps) placeUnits(fill, points, units, earliest) else sum
    val ordered = if (wraps) emptyList() else itemsAt(fill)
    return BreakPlan(points, units, cards(leftover - placed, points.size), ordered, placed)
}

/**
 * S2: the break points. `c` is always one; then for each target `t_i`, the run end inside the regular layout nearest
 * to it (ties to the earlier) that has an unchosen run end between it and every chosen point; a target with none is
 * skipped.
 */
private fun SpreadEnd.points(k: Int): List<Long> {
    if (runEnds.isEmpty()) return listOf(c)
    val chosen = arrayListOf(runEnds.lastIndex)
    for (i in 0 until k - 1) {
        val target = start + (i + 1) * (c - start) / k
        var pick = -1
        // Every run end but the last lies strictly inside (start, c).
        for (j in 0 until runEnds.lastIndex) {
            val closer = pick < 0 || abs(runEnds[j] - target) < abs(runEnds[pick] - target)
            if (closer && j !in chosen && separated(j, chosen)) pick = j
        }
        if (pick >= 0) chosen += pick
    }
    return chosen.sorted().map { runEnds[it] }
}

// True when an unchosen run end lies strictly between run end [j] and every chosen one.
private fun separated(
    j: Int,
    chosen: List<Int>,
): Boolean =
    chosen.all { q ->
        val low = minOf(j, q)
        val high = maxOf(j, q)
        val chosenBetween = chosen.count { it in (low + 1) until high }
        high - low - 1 - chosenBetween >= 1
    }

/**
 * S3 for `shuffle` and `curated`: the fill as units (each item in `shuffle`, each group in `curated`, ordered by their
 * first place in the fill, the items of a unit in cycle order), unit `j` placed at the first break from `j mod k'` on
 * whose point its group may take, or dropped. Returns the runtime placed.
 */
private fun SpreadEnd.placeUnits(
    fill: List<Int>,
    points: List<Long>,
    units: List<MutableList<List<ScheduleItem>>>,
    earliest: Map<CanonicalId, Long>,
): Long {
    val byGroup = LinkedHashMap<Any, MutableList<Int>>()
    for ((index, v) in fill.withIndex()) {
        val key: Any = if (settings.orderingMode == OrderingMode.Curated) groupKey(visit[v]) else index
        byGroup.getOrPut(key) { ArrayList() }.add(v)
    }
    var placed = 0L
    for ((j, members) in byGroup.values.withIndex()) {
        val unit = members.sortedBy { cycleIndex(it) }.map { visit[it] }
        val from = earliest[groupKey(unit.first())] ?: Long.MIN_VALUE
        val at = (j % points.size until points.size).firstOrNull { points[it] >= from } ?: continue
        units[at] += unit
        placed += unit.sumOf { it.runtimeMs }
    }
    return placed
}

/**
 * S4: [remainder] as cards: `m` of them, on breaks `(j + 1) * k' / m - 1`, each `R / m` cut to whole seconds, and the
 * last also taking what that cut leaves.
 */
private fun cards(
    remainder: Long,
    breaks: Int,
): LongArray {
    val cards = LongArray(breaks)
    if (remainder == 0L) return cards
    val m = maxOf(1L, minOf(breaks.toLong(), remainder / MIN_CARD_MS)).toInt()
    val each = remainder / m / MS_PER_SECOND * MS_PER_SECOND
    for (j in 0 until m) cards[(j + 1) * breaks / m - 1] += each
    cards[breaks - 1] += remainder - m * each
    return cards
}

// The regular blocks with [plan]'s breaks after the runs that end at its points, every later block moved by them.
private fun SpreadEnd.emit(
    regular: List<ScheduleBlock>,
    plan: BreakPlan,
): List<ScheduleBlock> {
    val out = ArrayList<ScheduleBlock>()
    var shift = 0L
    var next = 0
    if (plan.points.first() == start) shift += emitBreak(out, plan, next++, start)
    for (block in regular) {
        out += ScheduleBlock(block.startMs + shift, block.endMs + shift, block.content)
        if (next < plan.points.size && plan.points[next] == block.endMs) {
            shift += emitBreak(out, plan, next++, block.endMs + shift)
        }
    }
    return out
}

// Break [index] at [at]: its units, then its card; the last break of an ordered mode is its card, then the fill.
// Returns its length.
private fun SpreadEnd.emitBreak(
    out: MutableList<ScheduleBlock>,
    plan: BreakPlan,
    index: Int,
    at: Long,
): Long {
    var cursor = at
    val programmes = plan.units[index].flatten() + if (index == plan.points.lastIndex) plan.fill else emptyList()
    val card = plan.cards[index]
    val cardFirst = !wraps
    if (cardFirst && card > 0) {
        out += ScheduleBlock(cursor, cursor + card, ScheduleContent.Break)
        cursor += card
    }
    for (item in programmes) {
        out += ScheduleBlock(cursor, cursor + item.runtimeMs, ScheduleContent.Program(item.id))
        cursor += item.runtimeMs
    }
    if (!cardFirst && card > 0) {
        out += ScheduleBlock(cursor, cursor + card, ScheduleContent.Break)
        cursor += card
    }
    return cursor - at
}
