// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent

/**
 * A channel day's blocks as composed from its repair record, and the starts of the blocks that are the unnamed rest of
 * a fix ([fixRemainders]): the schedule source never fills those a second time.
 */
internal class Composed(
    val blocks: List<ScheduleBlock>,
    val fixRemainders: Set<Long>,
)

/** Builds one channel's day from its repair record (spec 26). Pure: it reads only its arguments. */
internal object RepairedDay {
    /**
     * The day `[dayStartMs, dayEndMs)`: [original] when there is no [repair] (or when its blocks do not run
     * contiguously from `keptFromMs` to [dayEndMs], which a saved record never does); otherwise a slate over the past
     * `[dayStartMs, keptFromMs)` (left out when empty), then the record's blocks. Each of [fixes] whose block is in
     * the result and whose gone item still names it is replaced by its pieces; time the pieces leave stays an unnamed
     * `Program(gone)` and its start goes in [Composed.fixRemainders]. A fix that matches no block, or whose pieces do
     * not lie contiguously inside its block, is ignored.
     */
    fun compose(
        dayStartMs: Long,
        dayEndMs: Long,
        original: List<ScheduleBlock>,
        repair: ChannelRepair?,
        fixes: List<LostFix>,
    ): Composed {
        val base = repair?.let { recorded(dayStartMs, dayEndMs, it) } ?: original
        if (fixes.isEmpty()) return Composed(base, emptySet())
        val byBlock = fixes.associateBy { it.blockStartMs to it.blockEndMs }
        val remainders = LinkedHashSet<Long>()
        val out = ArrayList<ScheduleBlock>(base.size)
        for (block in base) {
            val content = block.content as? ScheduleContent.Program
            val fix = byBlock[block.startMs to block.endMs]
            if (fix == null || content == null || !replaces(fix, content)) {
                out += block
                continue
            }
            out += fix.pieces.map { it.asBlock() }
            val covered = fix.pieces.lastOrNull()?.endMs ?: block.startMs
            if (covered < block.endMs) {
                val rest = content.mediaStartOffsetMs + (covered - block.startMs)
                out += ScheduleBlock(covered, block.endMs, ScheduleContent.Program(fix.gone, rest))
                remainders += covered
            }
        }
        return Composed(out, remainders)
    }

    // The slate over the past, then the record's blocks; null when the record does not run contiguously to the end.
    private fun recorded(
        dayStartMs: Long,
        dayEndMs: Long,
        repair: ChannelRepair,
    ): List<ScheduleBlock>? {
        val kept = repair.blocks
        var cursor = repair.keptFromMs
        val contiguous =
            kept.isNotEmpty() &&
                repair.keptFromMs >= dayStartMs &&
                kept.all { block ->
                    val ok = block.startMs == cursor && block.endMs > block.startMs && block.mediaStartMs >= 0
                    cursor = block.endMs
                    ok
                } &&
                cursor == dayEndMs
        if (!contiguous) return null
        val past =
            if (repair.keptFromMs > dayStartMs) {
                listOf(ScheduleBlock(dayStartMs, repair.keptFromMs, ScheduleContent.Slate))
            } else {
                emptyList()
            }
        return past + kept.map { it.asBlock() }
    }

    private fun replaces(
        fix: LostFix,
        content: ScheduleContent.Program,
    ): Boolean = fix.gone == content.itemId && fits(fix)

    // Pieces start at the block's start, touch end to end, and stay inside the block.
    private fun fits(fix: LostFix): Boolean {
        var cursor = fix.blockStartMs
        for (piece in fix.pieces) {
            if (piece.startMs != cursor || piece.endMs <= piece.startMs || piece.mediaStartMs < 0) return false
            cursor = piece.endMs
        }
        return cursor <= fix.blockEndMs
    }

    private fun KeptBlock.asBlock(): ScheduleBlock = ScheduleBlock(startMs, endMs, itemId.toContent(mediaStartMs))

    private fun CanonicalId?.toContent(mediaStartMs: Long): ScheduleContent =
        if (this == null) ScheduleContent.Slate else ScheduleContent.Program(this, mediaStartMs)
}
