// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import androidx.compose.runtime.Immutable
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.time.Duration.Companion.minutes

// The model classes are @Immutable, a Compose runtime annotation and the one Compose import here: ProgramInfo comes
// from a module the Compose compiler does not see, so without it every cell would count as unstable, and a minute tick
// that rebuilds an equal model would recompose every cell on the grid.

/** What a guide cell shows: a program (or a stand-in for one), or a slate (nothing to play). */
internal enum class CellKind { Program, Slate }

/**
 * One cell of a guide row, half-open `[startMs, endMs)` in milliseconds since 1970. The first cell of a row may start
 * before [GuideModel.originMs]. [title] is derived from [info], the display title: the series name for an episode of a
 * known series, else the item's own title. It and [info] are null for a slate and for a program no longer in the
 * library; [itemId] is null for a slate. Whether it is too narrow for a title is the screen's call (`CellPlace`).
 */
@Immutable
internal data class GuideCell(
    val startMs: Long,
    val endMs: Long,
    val kind: CellKind,
    val title: String?,
    val info: ProgramInfo?,
    val itemId: CanonicalId?,
)

/** One channel's row, in channel-number order. */
@Immutable
internal data class GuideRow(
    val channel: ChannelId,
    val number: Int,
    val name: String,
    val cells: List<GuideCell>,
)

/**
 * The grid the guide draws (spec 17): [rows] from [originMs] (the current half hour) to [endMs] (the end of the last
 * cell). [complete] is false when the schedule behind it is not all built yet.
 */
@Immutable
internal data class GuideModel(
    val rows: List<GuideRow>,
    val originMs: Long,
    val endMs: Long,
    val complete: Boolean,
)

internal val SLOT_MS: Long = 30.minutes.inWholeMilliseconds

/** Lays [window] out as cells from the half hour that holds [nowMs]; blocks that ended before it are left out. */
internal fun buildGuideModel(
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
 * its own start offset, so it never matches and stays a cell of its own; neither does a block after a gap.
 *
 * The v1 built-in scheduler never produces split blocks (`mediaStartOffsetMs` is always 0 and days are never split), so
 * this only covers future sources that do.
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
    val (kind, info, itemId) =
        when (val airing = block.airing) {
            is Airing.Program -> Triple(CellKind.Program, airing.info, airing.itemId)
            is Airing.StandIn -> Triple(CellKind.Program, airing.info, airing.itemId)
            Airing.Slate -> Triple(CellKind.Slate, null, null)
        }
    return GuideCell(block.startMs, block.endMs, kind, info?.let(::displayTitle), info, itemId)
}

private fun displayTitle(info: ProgramInfo): String {
    val series = info.seriesName
    return if (info.kind == ScheduleItemKind.Episode && series != null) series else info.title
}
