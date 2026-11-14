// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItem

/** At most this many candidates are looked at; the rest of the pool is ignored. */
internal const val MAX_FIT_CANDIDATES = 64

private const val MS_PER_SECOND = 1_000L
private const val UNSET = -1
private const val START = -2

/**
 * The items, in candidate order, whose weights add up to the most seconds that fit in [budgetMs] (a whole-second
 * subset-sum). Weights round up and the budget rounds down, so the real sum never exceeds the budget. Ties go to the
 * set reached first when candidates are taken in order. Only the first [MAX_FIT_CANDIDATES] candidates that fit alone
 * count. A weight is at least one second ([IllegalArgumentException] otherwise), and a budget over one day counts as
 * one day.
 */
internal fun bestFit(
    candidates: List<ScheduleItem>,
    budgetMs: Long,
    weightMs: (ScheduleItem) -> Long,
): List<ScheduleItem> {
    val budget = budgetSeconds(budgetMs)
    if (budget <= 0) return emptyList()
    val pool = candidates.filter { seconds(weightMs(it)) <= budget }.take(MAX_FIT_CANDIDATES)
    // reach[s]: the index in pool of the item that first made s reachable; the sum before it is s - w(item).
    val reach = IntArray(budget + 1) { UNSET }
    reach[0] = START
    for ((index, item) in pool.withIndex()) {
        val w = seconds(weightMs(item))
        for (s in budget downTo w) {
            if (reach[s] == UNSET && reach[s - w] != UNSET) reach[s] = index
        }
    }
    val chosen = BooleanArray(pool.size)
    var s = reach.lastSet()
    while (s > 0) {
        val index = reach[s]
        chosen[index] = true
        s -= seconds(weightMs(pool[index]))
    }
    return pool.filterIndexed { i, _ -> chosen[i] }
}

/**
 * Like [bestFit] for the ordered modes, where within a group only a prefix (in candidate order) may be taken. A group's
 * first candidate that does not fit alone passes the group over: it and the group's later candidates are dropped, as
 * in the end-of-day fill. At most one prefix is taken per group.
 */
internal fun bestGroupedFit(
    candidates: List<ScheduleItem>,
    groupOf: (ScheduleItem) -> CanonicalId,
    budgetMs: Long,
    weightMs: (ScheduleItem) -> Long,
): List<ScheduleItem> {
    val budget = budgetSeconds(budgetMs)
    if (budget <= 0) return emptyList()
    val pool = groupedPool(candidates, groupOf, budget, weightMs)
    // Pool indexes per group, in order of the group's first kept candidate.
    val groups = LinkedHashMap<CanonicalId, MutableList<Int>>()
    pool.forEachIndexed { i, item -> groups.getOrPut(groupOf(item)) { ArrayList() }.add(i) }
    // prefixSum[i]: the weight of the group's prefix that ends with pool item i.
    val prefixSum = IntArray(pool.size)
    for (members in groups.values) {
        var running = 0
        for (index in members) {
            running += seconds(weightMs(pool[index]))
            prefixSum[index] = running
        }
    }
    // reach[s]: the pool index of the last item of the prefix that first made s reachable.
    val reach = IntArray(budget + 1) { UNSET }
    reach[0] = START
    val snapshot = IntArray(budget + 1)
    for (members in groups.values) {
        reach.copyInto(snapshot)
        for (s in budget downTo 1) {
            for (index in members) {
                val w = prefixSum[index]
                if (reach[s] == UNSET && s >= w && snapshot[s - w] != UNSET) reach[s] = index
            }
        }
    }
    val chosen = BooleanArray(pool.size)
    var s = reach.lastSet()
    while (s > 0) {
        val last = reach[s]
        // The prefix is the group's items up to and including `last`.
        for (index in groups.getValue(groupOf(pool[last])).takeWhile { it <= last }) chosen[index] = true
        s -= prefixSum[last]
    }
    return pool.filterIndexed { i, _ -> chosen[i] }
}

/** The cut and the cap: a passed-over group loses its tail, then the first [MAX_FIT_CANDIDATES] remain. */
private fun groupedPool(
    candidates: List<ScheduleItem>,
    groupOf: (ScheduleItem) -> CanonicalId,
    budget: Int,
    weightMs: (ScheduleItem) -> Long,
): List<ScheduleItem> {
    val passedOver = HashSet<CanonicalId>()
    val pool = ArrayList<ScheduleItem>()
    for (candidate in candidates) {
        val group = groupOf(candidate)
        val fits = seconds(weightMs(candidate)) <= budget
        if (!fits) passedOver.add(group)
        if (fits && group !in passedOver) pool.add(candidate)
    }
    return pool.take(MAX_FIT_CANDIDATES)
}

/**
 * The largest budget looked at, in seconds: one day, the longest epoch. A longer budget is cut to it, so a weight (at
 * most this plus one once clamped) and the sum of [MAX_FIT_CANDIDATES] of them stay far inside an Int.
 */
private const val MAX_BUDGET_SECONDS = 86_400L

private fun budgetSeconds(budgetMs: Long): Int = (budgetMs / MS_PER_SECOND).coerceAtMost(MAX_BUDGET_SECONDS).toInt()

/** [ms] in whole seconds, rounded up, without overflow; past the largest budget it is clamped to one more. */
private fun seconds(ms: Long): Int {
    require(ms >= MS_PER_SECOND) { "A weight is at least one second, got $ms ms" }
    val up = ms / MS_PER_SECOND + if (ms % MS_PER_SECOND > 0) 1 else 0
    return up.coerceAtMost(MAX_BUDGET_SECONDS + 1).toInt()
}

private fun IntArray.lastSet(): Int {
    var s = size - 1
    while (this[s] == UNSET) s--
    return s
}
