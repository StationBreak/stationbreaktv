// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Duration.Companion.minutes

/** What a guide cell shows: a program (or a stand-in for one), or a slate (nothing to play). */
enum class CellKind { Program, Slate }

/**
 * One cell of a guide row, half-open `[startMs, endMs)` in milliseconds since 1970. The first cell of a row may start
 * before [GuideModel.originMs]. [title] and [info] are null for a slate, and for a program no longer in the library;
 * [itemId] is null for a slate. A [compact] cell is too short to hold a title.
 */
data class GuideCell(
    val startMs: Long,
    val endMs: Long,
    val kind: CellKind,
    val title: String?,
    val info: ProgramInfo?,
    val itemId: CanonicalId?,
    val compact: Boolean,
)

/** One channel's row, in channel-number order. */
data class GuideRow(
    val channel: ChannelId,
    val number: Int,
    val name: String,
    val cells: List<GuideCell>,
)

/**
 * The grid the guide draws (spec 17): [rows] from [originMs] (the current half hour) to [endMs] (the end of the last
 * cell). [complete] is false when the schedule behind it is not all built yet.
 */
data class GuideModel(
    val rows: List<GuideRow>,
    val originMs: Long,
    val endMs: Long,
    val complete: Boolean,
)

internal val SLOT_MS: Long = 30.minutes.inWholeMilliseconds
private val COMPACT_BELOW_MS: Long = 10.minutes.inWholeMilliseconds

/** Lays [window] out as cells from the half hour that holds [nowMs]; blocks that ended before it are left out. */
fun buildGuideModel(
    window: ScheduleWindow,
    nowMs: Long,
): GuideModel {
    val origin = nowMs - nowMs.mod(SLOT_MS)
    val rows =
        window.rows.map { row ->
            GuideRow(
                row.channel.id,
                row.channel.number,
                row.channel.name,
                mergeContinuations(row.blocks).filter { it.endMs > origin }.map(::cellOf),
            )
        }
    val end = rows.maxOfOrNull { r -> r.cells.lastOrNull()?.endMs ?: origin } ?: origin
    return GuideModel(rows, origin, maxOf(end, origin), window.complete)
}

/**
 * Joins a program that carries on into the next block. The window does not say where a broadcast day ends, so a
 * continuation is recognised by what it is: the next block starts exactly where this one ends, airs the same item, and
 * picks the media up where this one left off (`mediaStartMs` plus the block's length). A repeat restarts the item at
 * its own start offset, so it never matches and stays a cell of its own.
 */
private fun mergeContinuations(blocks: List<AiringBlock>): List<AiringBlock> {
    val merged = ArrayList<AiringBlock>(blocks.size)
    for (block in blocks) {
        val last = merged.lastOrNull()
        if (last != null && continues(last, block)) {
            merged[merged.lastIndex] = last.copy(endMs = block.endMs)
        } else {
            merged += block
        }
    }
    return merged
}

private fun continues(
    first: AiringBlock,
    second: AiringBlock,
): Boolean {
    val a = first.airing as? Airing.Program
    val b = second.airing as? Airing.Program
    return a != null &&
        b != null &&
        second.startMs == first.endMs &&
        a.itemId == b.itemId &&
        b.mediaStartMs == a.mediaStartMs + (first.endMs - first.startMs)
}

private fun cellOf(block: AiringBlock): GuideCell {
    val compact = block.endMs - block.startMs < COMPACT_BELOW_MS
    val (kind, info, itemId) =
        when (val airing = block.airing) {
            is Airing.Program -> Triple(CellKind.Program, airing.info, airing.itemId)
            is Airing.StandIn -> Triple(CellKind.Program, airing.info, airing.itemId)
            Airing.Slate -> Triple(CellKind.Slate, null, null)
        }
    return GuideCell(block.startMs, block.endMs, kind, info?.title, info, itemId, compact)
}
