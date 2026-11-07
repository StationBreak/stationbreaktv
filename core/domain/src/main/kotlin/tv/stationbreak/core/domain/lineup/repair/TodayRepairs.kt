// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.sha256Hex
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId

/**
 * One block of a repaired day: [startMs] to [endMs] airs [itemId] from [mediaStartMs] into it. A null [itemId] is a
 * slate (nothing airs). Blocks are in order and have length: a block that starts before the one before it ends, a block
 * of no length or a negative [mediaStartMs] cannot be stored, and the save of a record holding one is refused.
 */
internal data class KeptBlock(
    val startMs: Long,
    val endMs: Long,
    val itemId: CanonicalId?,
    val mediaStartMs: Long,
)

/**
 * A repaired channel day (spec 26): the blocks of one channel's broadcast day [n] as they stand after a change of the
 * selection, so the rest of the day is never worked out again. At most one per (channel, [n]): the latest.
 *
 * @property seq the selection seq this repair wrote (Task 4.1); stored and compared only here.
 * @property libraries the selection the blocks follow.
 * @property blocks contiguous from [keptFromMs] to [dayEndMs]: the kept window (ending at [pinEndMs]), then the tail.
 * @property tailHidden the tail's own empty reason, stored so no snapshot is read for it.
 */
internal data class ChannelRepair(
    val channel: ChannelId,
    val n: Long,
    val dayEndMs: Long,
    val atMs: Long,
    val seq: Long,
    val libraries: Set<ItemId>,
    val keptFromMs: Long,
    val pinEndMs: Long,
    val pinLibrary: ItemId?,
    val blocks: List<KeptBlock>,
    val tailHidden: HiddenReason?,
)

/**
 * The fix of one block whose item has left the library ([gone]): the [pieces] that air in its place, recorded so a
 * later lookup reads the record only. A match is one piece with the block's own `mediaStartMs`; fills start at 0.
 */
internal data class LostFix(
    val channel: ChannelId,
    val n: Long,
    val dayEndMs: Long,
    val blockStartMs: Long,
    val blockEndMs: Long,
    val gone: CanonicalId,
    val pieces: List<KeptBlock>,
) {
    /** True when the block is on air at [nowMs]. */
    fun covers(nowMs: Long): Boolean = nowMs >= blockStartMs && nowMs < blockEndMs
}

/**
 * The per-TV repair record, stored in the lineup file from version 4 (spec 26): each repaired channel day's blocks and
 * the fixes of lost blocks. Never more than today's and the coming days'; [rolled] takes off what is over.
 *
 * @property lostCheckedGeneration the library generation the lost-block check last ran for, or null.
 */
internal data class TodayRepairs(
    val selection: List<ChannelRepair> = emptyList(),
    val fixes: List<LostFix> = emptyList(),
    val lostCheckedGeneration: Long? = null,
) {
    /** True when there is nothing to store: no repair, no fix and no checked generation. */
    val isEmpty: Boolean get() = selection.isEmpty() && fixes.isEmpty() && lostCheckedGeneration == null

    /** Without the entries whose day has ended by [nowMs]; this very instance when nothing is dropped. */
    fun rolled(nowMs: Long): TodayRepairs {
        val keepSelection = selection.filter { it.dayEndMs > nowMs }
        val keepFixes = fixes.filter { it.dayEndMs > nowMs }
        return if (keepSelection.size == selection.size && keepFixes.size == fixes.size) {
            this
        } else {
            copy(selection = keepSelection, fixes = keepFixes)
        }
    }

    /**
     * A digest of what is recorded for [channel]'s day [n] (its repair and its fixes), for the day's input key: equal
     * records give equal digests in every process. The empty string when nothing is recorded for the day. Worked out
     * once per record (this instance) for every recorded day together, on first use: a lookup hashes nothing again.
     */
    fun digestFor(
        channel: ChannelId,
        n: Long,
    ): String = digests[channel to n].orEmpty()

    // Not part of the value (a data class compares its constructor's fields only): each instance works its own out.
    private val digests: Map<Pair<ChannelId, Long>, String> by lazy {
        val days = LinkedHashSet<Pair<ChannelId, Long>>()
        selection.mapTo(days) { it.channel to it.n }
        fixes.mapTo(days) { it.channel to it.n }
        days.associateWith { (channel, n) ->
            hashed++
            val repair = selection.firstOrNull { it.channel == channel && it.n == n }
            val mine = fixes.filter { it.channel == channel && it.n == n }.sortedBy { it.blockStartMs }
            val text = StringBuilder()
            if (repair != null) text.append(canonical(repair))
            for (fix in mine) text.append(canonical(fix))
            sha256Hex(text.toString().encodeToByteArray())
        }
    }

    /** For tests: how many channel days this instance has hashed (each recorded day once, on first use). */
    @Volatile
    internal var hashed: Int = 0
        private set

    /** The highest seq of any repair held, or null when none. */
    fun latestSeq(): Long? = selection.maxOfOrNull { it.seq }

    companion object {
        val NONE = TodayRepairs()
    }
}

// Every field, in a fixed order, with separators no value can hold.
private fun canonical(repair: ChannelRepair): String =
    buildString {
        append("R|")
            .append(repair.channel.value)
            .append('|')
            .append(repair.n)
            .append('|')
            .append(repair.dayEndMs)
        append('|').append(repair.atMs).append('|').append(repair.seq)
        append("|L").append(
            repair.libraries
                .map { it.value }
                .sorted()
                .joinToString(","),
        )
        append('|').append(repair.keptFromMs).append('|').append(repair.pinEndMs)
        append('|').append(repair.pinLibrary?.value ?: "-").append('|').append(repair.tailHidden?.name ?: "-")
        append("|B").append(canonical(repair.blocks)).append(';')
    }

private fun canonical(fix: LostFix): String =
    buildString {
        append("F|")
            .append(fix.channel.value)
            .append('|')
            .append(fix.n)
            .append('|')
            .append(fix.dayEndMs)
        append('|')
            .append(fix.blockStartMs)
            .append('|')
            .append(fix.blockEndMs)
            .append('|')
            .append(fix.gone.value)
        append("|P").append(canonical(fix.pieces)).append(';')
    }

private fun canonical(blocks: List<KeptBlock>): String =
    blocks.joinToString(",") { "${it.startMs}:${it.endMs}:${it.itemId?.value ?: "-"}:${it.mediaStartMs}" }

/** What [trimmedToFit] did: the [record] that fits and the counts dropped to get there (numbers only). */
internal data class Trimmed(
    val record: TodayRepairs,
    val fixes: Int,
    val coveringFixes: Int,
    val selection: Int,
) {
    val dropped: Int get() = fixes + coveringFixes + selection
}

/**
 * This record made to fit a lineup save that must not fail because of it ([sizeOf] is the whole file's size with a
 * record in it, [limit] the most it may be). What goes, in order, until it fits: the oldest fixes whose block is not
 * on air at [nowMs]; then fixes whose block is; then selection repairs, the oldest [ChannelRepair.atMs] first. The
 * lost-block generation always stays. The same instance when it already fits.
 */
internal fun TodayRepairs.trimmedToFit(
    nowMs: Long,
    limit: Int,
    sizeOf: (TodayRepairs) -> Int,
): Trimmed {
    val order = compareBy<LostFix>({ it.dayEndMs }, { it.blockStartMs }, { it.channel.value }, { it.n })
    val (covering, others) = fixes.partition { it.covers(nowMs) }
    val fixOrder = others.sortedWith(order) + covering.sortedWith(order)
    val selectionOrder = selection.sortedWith(compareBy<ChannelRepair>({ it.atMs }, { it.channel.value }, { it.n }))

    fun without(count: Int): TodayRepairs {
        val fixCount = minOf(count, fixOrder.size)
        return copy(
            fixes = fixes - fixOrder.take(fixCount).toSet(),
            selection = selection - selectionOrder.take(count - fixCount).toSet(),
        )
    }
    val all = fixOrder.size + selectionOrder.size
    val count =
        if (isEmpty || sizeOf(this) <= limit) 0 else leastDropsThatFit(0, all) { sizeOf(without(it)) <= limit }
    val dropFixes = minOf(count, fixOrder.size)
    val noncovering = minOf(dropFixes, others.size)
    return Trimmed(
        if (count == 0) this else without(count),
        noncovering,
        dropFixes - noncovering,
        count - dropFixes,
    )
}

/**
 * This record made to fit: [sizeOf] is the whole file's size with a record in it and [limit] the most it may be.
 * Fixes go first when it does not fit: the oldest day, then the earliest block, never one whose block is on air at
 * [nowMs]; and never more than [RepairConfig.maxFixes] stay. Null when it still does not fit with every fix that may
 * go gone (a selection repair too big, or fixes covering now too many): the caller changes nothing.
 */
internal fun TodayRepairs.fitted(
    nowMs: Long,
    config: RepairConfig,
    limit: Int,
    sizeOf: (TodayRepairs) -> Int,
): TodayRepairs? {
    val droppable =
        fixes
            .filter { !it.covers(nowMs) }
            .sortedWith(compareBy<LostFix>({ it.dayEndMs }, { it.blockStartMs }, { it.channel.value }, { it.n }))

    fun without(count: Int): TodayRepairs = copy(fixes = fixes - droppable.take(count).toSet())
    val mustDrop = (fixes.size - config.maxFixes).coerceAtLeast(0)
    return when {
        mustDrop > droppable.size -> null
        isEmpty || sizeOf(without(mustDrop)) <= limit -> without(mustDrop)
        sizeOf(without(droppable.size)) > limit -> null
        else -> without(leastDropsThatFit(mustDrop, droppable.size) { sizeOf(without(it)) <= limit })
    }
}

// Dropping more only ever makes the file smaller, so the least count that fits is found by bisection: [low] does not
// fit and [high] does.
private fun leastDropsThatFit(
    low: Int,
    high: Int,
    fits: (Int) -> Boolean,
): Int {
    var below = low
    var above = high
    while (above - below > 1) {
        val middle = (below + above) / 2
        if (fits(middle)) above = middle else below = middle
    }
    return above
}
