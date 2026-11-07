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
 *   A day hidden at the repair keeps nothing: [pinEndMs] is [keptFromMs] (and when it stays hidden, both are the day's
 *   start and the one block is a slate).
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
 * Where the lost-block check last ran (spec 26): for the library's content [generation], over the channel days on the
 * air then, the earliest of which ends at [untilMs]. It holds only for those days: once one of them has ended a new
 * day is on the air that no check has seen, so the check is due again whatever the generation.
 */
internal data class LostCheck(
    val generation: Long,
    val untilMs: Long,
) {
    /** Whether a check for [generation] at [atMs] would find nothing this one did not. */
    fun covers(
        generation: Long,
        atMs: Long,
    ): Boolean = this.generation == generation && atMs < untilMs
}

/**
 * The per-TV repair record, stored in the lineup file from version 4 (spec 26): each repaired channel day's blocks and
 * the fixes of lost blocks. Never more than today's and the coming days'; [rolled] takes off what is over.
 *
 * @property lostCheck where the lost-block check last ran, or null. Kept in memory only, never in the file: a restart
 *   costs one more check, in the background.
 * @property lastSeq the highest selection seq recorded while a selection repair is held ([latestSeq]): kept through
 *   rolling and trimming as long as one is, and stored with it. With none held it is 0 and not stored; the next seq
 *   is [nextSeq], which the saved selection's seq keeps above every seq a shown repair has used.
 */
internal data class TodayRepairs(
    val selection: List<ChannelRepair> = emptyList(),
    val fixes: List<LostFix> = emptyList(),
    val lostCheck: LostCheck? = null,
    val lastSeq: Long = 0L,
) {
    /** True when there is nothing to store: no repair and no fix ([lostCheck] is never stored, nor [lastSeq] alone). */
    val isEmpty: Boolean get() = selection.isEmpty() && fixes.isEmpty()

    /** This record as the file holds it: no [lostCheck], and no [lastSeq] when no selection repair is held. */
    fun stored(): TodayRepairs {
        val seq = if (selection.isEmpty()) 0L else lastSeq
        return if (lostCheck == null && lastSeq == seq) this else copy(lostCheck = null, lastSeq = seq)
    }

    /**
     * The seq of the next selection change, for a TV whose saved selection has seq [appliedSeq] (Part 4 saves the seq
     * with the selection, in one write): above every seq this record holds and above the saved one. A seq whose
     * repairs are gone (rolled off, dropped after a failed save) may come round again: nothing of it is left to show.
     */
    fun nextSeq(appliedSeq: Long): Long = maxOf(latestSeq(), appliedSeq) + 1

    // The [lastSeq] a record holding [kept] keeps: the highest seq so far while a selection repair is held, else 0.
    internal fun seqKeptWith(kept: List<ChannelRepair>): Long = if (kept.isEmpty()) 0L else latestSeq()

    /**
     * Without the entries whose day has ended by [nowMs], and without the [lostCheck] once its earliest day has; this
     * very instance when nothing is dropped. [lastSeq] stays while a selection repair does.
     */
    fun rolled(nowMs: Long): TodayRepairs {
        val keepSelection = selection.filter { it.dayEndMs > nowMs }
        val keepFixes = fixes.filter { it.dayEndMs > nowMs }
        val keepCheck = lostCheck?.takeIf { it.untilMs > nowMs }
        return if (keepSelection.size == selection.size && keepFixes.size == fixes.size && keepCheck == lostCheck) {
            this
        } else {
            val seq = seqKeptWith(keepSelection)
            copy(selection = keepSelection, fixes = keepFixes, lostCheck = keepCheck, lastSeq = seq)
        }
    }

    /**
     * The record as a lookup follows it when the TV's saved selection has seq [appliedSeq]: without the selection
     * repairs of a later seq, whose selection is not saved yet (spec 26, ADR-0025 §7). The fixes all stay. This very
     * instance when none is later.
     */
    fun applied(appliedSeq: Long): TodayRepairs =
        if (selection.none { it.seq > appliedSeq }) {
            this
        } else {
            copy(selection = selection.filter { it.seq <= appliedSeq })
        }

    /**
     * Without the selection repairs of [seq] (a selection change whose save failed, Part 4): those days are built
     * again as the build makes them. [lastSeq] stays while another selection repair does; the [lostCheck] goes, as
     * the days it saw are no longer those on the air. This very instance when none has that seq.
     */
    fun withoutSelection(seq: Long): TodayRepairs =
        if (selection.none { it.seq == seq }) {
            this
        } else {
            val kept = selection.filter { it.seq != seq }
            copy(selection = kept, lostCheck = null, lastSeq = seqKeptWith(kept))
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
            DigestProbe.hashed?.invoke()
            val repair = selection.firstOrNull { it.channel == channel && it.n == n }
            val mine = fixes.filter { it.channel == channel && it.n == n }.sortedBy { it.blockStartMs }
            val text = StringBuilder()
            if (repair != null) text.append(canonical(repair))
            for (fix in mine) text.append(canonical(fix))
            sha256Hex(text.toString().encodeToByteArray())
        }
    }

    /** The highest selection seq held ([lastSeq], or a repair's if higher); 0 when none is. */
    fun latestSeq(): Long = maxOf(lastSeq, selection.maxOfOrNull { it.seq } ?: 0L)

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

/** A test seam: [hashed] is told each time a record works out the digest of one channel day. Never set in the app. */
internal object DigestProbe {
    @Volatile var hashed: (() -> Unit)? = null
}

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
 * on air at [nowMs]; then fixes whose block is; then selection repairs, the oldest [ChannelRepair.atMs] first. When a
 * fix goes the [TodayRepairs.lostCheck] goes too, so the next check makes it again. When dropping everything still
 * does not fit, the record is emptied ([TodayRepairs.NONE]). [TodayRepairs.lastSeq] stays while a selection repair
 * does. The
 * same instance when it already fits.
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
        val kept = selection - selectionOrder.take(count - fixCount).toSet()
        return copy(
            fixes = fixes - fixOrder.take(fixCount).toSet(),
            selection = kept,
            lostCheck = if (fixCount > 0) null else lostCheck,
            lastSeq = seqKeptWith(kept),
        )
    }
    val all = fixOrder.size + selectionOrder.size
    val fits = isEmpty || sizeOf(this) <= limit
    val count = if (fits) 0 else leastDropsThatFit(0, all) { sizeOf(without(it)) <= limit }
    val dropFixes = minOf(count, fixOrder.size)
    val noncovering = minOf(dropFixes, others.size)
    val record =
        when {
            count == 0 -> this
            sizeOf(without(all)) <= limit -> without(count)
            else -> TodayRepairs.NONE
        }
    return Trimmed(record, noncovering, dropFixes - noncovering, count - dropFixes)
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
