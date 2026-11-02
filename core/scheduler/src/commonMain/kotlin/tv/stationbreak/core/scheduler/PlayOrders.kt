// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.ScheduleItem

/**
 * The capped shuffle of one cycle (docs/rules-format.md, "The capped shuffle"): the version 1 shuffle of [pool] with
 * [seedN], with items moved later only as far as [caps] need. Inside a group the order is the version 1 shuffle's.
 * While one group is *tight* (it needs every remaining gap) it plays whenever its cap allows, so the other groups are
 * spread one per `cap + 1` places; otherwise the next item is the first, in shuffle order, whose group may go on. A
 * group runs past its cap only when nothing else is left.
 *
 * A total function of (pool, seed, caps, film-to-collection map) in `O(n log n)`. The per-item loop has no lambdas
 * (ART 7.1 crash, #264).
 */
internal fun cappedShuffle(
    pool: List<ScheduleItem>,
    seedN: ULong,
    caps: Caps,
    groups: GroupRules,
): List<ScheduleItem> = PacedWalk(Orderings.shuffle(pool, seedN), caps, groups).walk()

/**
 * The walk of [cappedShuffle] over [order], the version 1 shuffle. Groups are numbered in group ID order, so the
 * counts order's tie-break (group ID bytes ascending) is the number. `heads` and `counts` are heaps with lazy removal:
 * an entry that no longer matches its group's head or count is dropped when it reaches the top.
 */
private class PacedWalk(
    private val order: List<ScheduleItem>,
    caps: Caps,
    rules: GroupRules,
) {
    private val size = order.size

    /** The group number of each place in [order]. */
    private val groupOf = IntArray(size)
    private val cap: IntArray
    private val count: IntArray

    /** Each group's places in [order], group after group: group g's are `members[start[g] until start[g + 1]]`. */
    private val members = IntArray(size)
    private val start: IntArray

    /** The index in [members] of each group's head. */
    private val head: IntArray

    /** Keys: the place of a group's head in [order]. The first is the group whose head comes first. */
    private val heads = LongHeap(size)

    /** Keys: `(size - count) shl 32 or group`. The first is the group with most items left, the smaller ID on a tie. */
    private val counts: LongHeap

    private var last = -1
    private var run = 0

    init {
        val ids = ArrayList<CanonicalId>()
        val seen = HashSet<CanonicalId>()
        for (item in order) {
            val id = rules.groupId(item)
            if (seen.add(id)) ids += id
        }
        ids.sortWith(byIdBytes)
        val number = HashMap<CanonicalId, Int>(ids.size * 2)
        for ((index, id) in ids.withIndex()) number[id] = index

        cap = IntArray(ids.size)
        count = IntArray(ids.size)
        for ((place, item) in order.withIndex()) {
            val group = number.getValue(rules.groupId(item))
            groupOf[place] = group
            cap[group] = rules.cap(item, caps)
            count[group]++
        }
        start = IntArray(ids.size + 1)
        for (group in ids.indices) start[group + 1] = start[group] + count[group]
        head = start.copyOf(ids.size)
        val filled = start.copyOf(ids.size)
        for (place in 0 until size) members[filled[groupOf[place]]++] = place

        counts = LongHeap(size + ids.size)
        for (group in ids.indices) {
            heads.push(members[start[group]].toLong())
            counts.push(countKey(group))
        }
    }

    fun walk(): List<ScheduleItem> {
        val out = ArrayList<ScheduleItem>(size)
        for (step in 0 until size) {
            val tight = tightGroup(size - step)
            val pick = if (tight >= 0 && legal(tight)) tight else firstLegalHead()
            out += order[members[head[pick]]]
            take(pick)
        }
        return out
    }

    private fun legal(group: Int): Boolean = !(group == last && run >= cap[group])

    /** The group that holds more than `cap / (cap + 1)` of the [left] items, else -1. Only the first in counts can. */
    private fun tightGroup(left: Int): Int {
        while (counts.size > 0 && !isCurrentCount(counts.peek())) counts.pop()
        if (counts.size == 0) return -1
        val group = (counts.peek() and GROUP_MASK).toInt()
        val needs = count[group].toLong() * (1 + cap[group]) > cap[group].toLong() * left
        return if (needs) group else -1
    }

    /** The first group in heads that may go on; at most two are looked at. Else the first, which gives way. */
    private fun firstLegalHead(): Int {
        dropStaleHeads()
        val firstKey = heads.pop()
        val first = groupOf[firstKey.toInt()]
        var pick = first
        if (!legal(first)) {
            dropStaleHeads()
            if (heads.size > 0) pick = groupOf[heads.peek().toInt()]
        }
        heads.push(firstKey)
        return pick
    }

    private fun dropStaleHeads() {
        while (heads.size > 0 && !isCurrentHead(heads.peek())) heads.pop()
    }

    /** Emits [group]'s head: moves its head on and counts it, pushing the new entries. */
    private fun take(group: Int) {
        run = if (group == last) run + 1 else 1
        last = group
        head[group]++
        count[group]--
        if (head[group] < start[group + 1]) heads.push(members[head[group]].toLong())
        if (count[group] > 0) counts.push(countKey(group))
    }

    private fun isCurrentHead(key: Long): Boolean {
        val place = key.toInt()
        val group = groupOf[place]
        return head[group] < start[group + 1] && members[head[group]] == place
    }

    private fun isCurrentCount(key: Long): Boolean {
        val group = (key and GROUP_MASK).toInt()
        return count[group] > 0 && size - (key ushr GROUP_BITS).toInt() == count[group]
    }

    private fun countKey(group: Int): Long = ((size - count[group]).toLong() shl GROUP_BITS) or group.toLong()

    private companion object {
        const val GROUP_BITS = 32
        const val GROUP_MASK = 0xFFFF_FFFFL

        /** Canonical IDs are lower-case ASCII hex, so comparing them as strings compares their bytes. */
        val byIdBytes: Comparator<CanonicalId> = compareBy { it.value }
    }
}

/** A binary min-heap of [Long] keys with room for [capacity] keys at once. */
private class LongHeap(
    capacity: Int,
) {
    private val keys = LongArray(capacity)
    var size = 0
        private set

    fun peek(): Long = keys[0]

    fun push(key: Long) {
        var at = size++
        keys[at] = key
        while (at > 0) {
            val parent = (at - 1) / 2
            if (keys[parent] <= keys[at]) break
            swap(at, parent)
            at = parent
        }
    }

    fun pop(): Long {
        val top = keys[0]
        keys[0] = keys[--size]
        var at = 0
        var child = 1
        while (child < size) {
            if (child + 1 < size && keys[child + 1] < keys[child]) child++
            if (keys[at] <= keys[child]) break
            swap(at, child)
            at = child
            child = 2 * at + 1
        }
        return top
    }

    private fun swap(
        a: Int,
        b: Int,
    ) {
        val key = keys[a]
        keys[a] = keys[b]
        keys[b] = key
    }
}
