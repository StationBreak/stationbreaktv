// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings

/** The jitter of a group's first pass: its key modulo 90 minutes of airtime, in milliseconds. */
private const val JITTER_MS = 5_400_000L

/** A pass counts airtime in sixths of a millisecond, so that a weight of 2 or 3 divides it without a remainder loss. */
private const val PASS_SCALE = 6L

/** Nothing has aired yet: the `last(g)` of a group that has not had a turn. */
private const val NEVER = Long.MIN_VALUE

/**
 * One group of the curated walk: its [id], its [key] (`splitmix64(seed_n + fnv1a64(id))`), its [queue] already in
 * the order it airs (rotated to start at `key mod size`), its [weight] (1 to 3) and whether its episodes [pairs].
 */
internal class WalkGroup(
    val id: CanonicalId,
    val key: ULong,
    val queue: List<ScheduleItem>,
    val weight: Int,
    val pairs: Boolean,
)

/**
 * One step of the walk as a test sees it (docs/rules-format/vectors/key-order-v3.json, `walk`): the [clock] before the
 * step, the groups [ready] then (empty when the step is [relaxed], taking the longest gap), the [chosen] group, its
 * [run], the [slotMs] the run takes on the clock and the group's pass before and after.
 */
@Suppress("LongParameterList") // One field per column of the vectors' walk trace.
internal class WalkStep(
    val clock: Long,
    val ready: List<CanonicalId>,
    val relaxed: Boolean,
    val chosen: CanonicalId,
    val run: List<CanonicalId>,
    val slotMs: Long,
    val passBefore: Long,
    val passAfter: Long,
)

/**
 * The runs of the `curated` order (docs/rules-format.md, "curated") for [pool] on the day with the seed [seedN]:
 * every group of the pool takes turns, the group with the least airtime first, no group again within
 * `curated.repeatGapMs` while another can go, episodes of at most `curated.pairUpToMs` in pairs. Whole numbers
 * only; the result is a function of the pool as a set, the seed, the group rules and the settings.
 */
internal fun curatedRuns(
    pool: List<ScheduleItem>,
    seedN: ULong,
    groups: GroupRules,
    settings: SchedulerSettings,
    onStep: ((WalkStep) -> Unit)? = null,
): List<Run> {
    val members = HashMap<CanonicalId, ArrayList<ScheduleItem>>()
    for (item in pool) members.getOrPut(groups.groupId(item)) { ArrayList() }.add(item)
    val walkGroups = ArrayList<WalkGroup>(members.size)
    for ((id, items) in members) walkGroups.add(walkGroupOf(id, items, seedN, groups))
    return walkGroups(walkGroups, settings, onStep)
}

private fun walkGroupOf(
    id: CanonicalId,
    items: ArrayList<ScheduleItem>,
    seedN: ULong,
    groups: GroupRules,
): WalkGroup {
    val kind = groups.kind(items.first())
    when (kind) {
        GroupKind.Series -> items.sortWith(Orderings.inGroupKey)
        GroupKind.Collection -> items.sortWith(Orderings.releaseKey)
        GroupKind.Single -> Unit
    }
    val key = Orderings.shuffleKey(seedN, id)
    val size = items.size
    val start = (key % size.toULong()).toInt()
    val queue = ArrayList<ScheduleItem>(size)
    for (offset in 0 until size) queue.add(items[(start + offset) % size])
    var weight = 1
    for (item in items) {
        val w = groups.weights[item.seriesId ?: item.id]
        if (w > weight) weight = w
    }
    return WalkGroup(id, key, queue, weight, kind == GroupKind.Series)
}

/**
 * The walk over groups that are already built. Two heaps keep a step to O(log g) (see [GroupHeap]). [onStep] is a test
 * seam: it is null in the app, and a step is only described when it is set.
 */
internal fun walkGroups(
    groups: List<WalkGroup>,
    settings: SchedulerSettings,
    onStep: ((WalkStep) -> Unit)? = null,
): List<Run> {
    val state = WalkState(groups)
    val ready = GroupHeap(state, byLast = false)
    val waiting = GroupHeap(state, byLast = true)
    var remaining = 0
    for (index in groups.indices) {
        remaining += groups[index].queue.size
        if (groups[index].queue.isNotEmpty()) ready.push(index)
    }
    val runs = ArrayList<Run>()
    val tuning = checkNotNull(settings.curated) { "The curated order needs its tuning" }
    var clock = 0L
    while (remaining > 0) {
        // A group that aired long enough ago is ready again; the one that aired longest ago is the first to be.
        while (waiting.size > 0 && clock - state.last[waiting.peek()] >= tuning.repeatGapMs) ready.push(waiting.pop())
        val readyNow = if (onStep != null) ready.members().map { groups[it].id } else emptyList()
        val relaxed = ready.size == 0
        val chosen = if (relaxed) waiting.pop() else ready.pop()
        val run = state.take(chosen, tuning.pairUpToMs)
        runs.add(run)
        remaining -= run.items.size
        state.last[chosen] = clock
        val slot = Timeline.slotMs(settings, run)
        val passBefore = state.pass[chosen]
        state.pass[chosen] += run.runtimeMs * PASS_SCALE / state.groups[chosen].weight
        if (onStep != null) {
            val ids = run.items.map { it.id }
            onStep(WalkStep(clock, readyNow, relaxed, groups[chosen].id, ids, slot, passBefore, state.pass[chosen]))
        }
        clock += slot
        if (state.next[chosen] < state.groups[chosen].queue.size) waiting.push(chosen)
    }
    return runs
}

private class WalkState(
    val groups: List<WalkGroup>,
) {
    val pass = LongArray(groups.size)
    val last = LongArray(groups.size)
    val next = IntArray(groups.size)

    init {
        for (index in groups.indices) {
            pass[index] = (groups[index].key % JITTER_MS.toULong()).toLong() * PASS_SCALE / groups[index].weight
            last[index] = NEVER
        }
    }

    /** The next run of group [index]: its next item, and the one after it when the two are short episodes. */
    fun take(
        index: Int,
        pairUpToMs: Long,
    ): Run {
        val group = groups[index]
        val first = group.queue[next[index]]
        val canPair = group.pairs && next[index] + 1 < group.queue.size
        if (canPair) {
            val second = group.queue[next[index] + 1]
            if (first.runtimeMs <= pairUpToMs && second.runtimeMs <= pairUpToMs) {
                next[index] += 2
                return Run(listOf(first, second))
            }
        }
        next[index]++
        return Run(listOf(first))
    }

    /** True when group [a] goes before group [b] on the shared ties: smaller pass, then key, then ID bytes. */
    fun beforeByPass(
        a: Int,
        b: Int,
    ): Boolean {
        val order =
            when {
                pass[a] != pass[b] -> pass[a].compareTo(pass[b])
                groups[a].key != groups[b].key -> groups[a].key.compareTo(groups[b].key)
                else -> groups[a].id.value.compareTo(groups[b].id.value)
            }
        return order < 0
    }
}

/**
 * A binary heap of group indices. Ready groups are ordered by (pass, key, ID bytes); waiting groups by (last, pass,
 * key, ID bytes), so the first of the waiting is both the next to become ready and, when none is ready, the longest
 * gap. A written-out heap, not a priority queue with a comparator, because it runs once per run of the day.
 */
private class GroupHeap(
    private val state: WalkState,
    private val byLast: Boolean,
) {
    private val items = IntArray(state.groups.size)
    var size = 0
        private set

    fun peek(): Int = items[0]

    /** The groups in the heap, in no order: only the test seam asks. */
    fun members(): List<Int> = items.take(size)

    fun push(group: Int) {
        var at = size++
        while (at > 0) {
            val parent = (at - 1) / 2
            if (!before(group, items[parent])) break
            items[at] = items[parent]
            at = parent
        }
        items[at] = group
    }

    fun pop(): Int {
        val top = items[0]
        val moved = items[--size]
        var at = 0
        var child = firstChild(at)
        while (child < size && before(items[child], moved)) {
            items[at] = items[child]
            at = child
            child = firstChild(at)
        }
        if (size > 0) items[at] = moved
        return top
    }

    // The child of [at] that goes first; at or past [size] when [at] is a leaf.
    private fun firstChild(at: Int): Int {
        val left = 2 * at + 1
        return if (left + 1 < size && before(items[left + 1], items[left])) left + 1 else left
    }

    private fun before(
        a: Int,
        b: Int,
    ): Boolean {
        if (byLast && state.last[a] != state.last[b]) return state.last[a] < state.last[b]
        return state.beforeByPass(a, b)
    }
}
