// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.GuideFocus
import tv.stationbreak.feature.guide.R

/**
 * Consecutive cells of one row, [first] to [last] (their indexes in the row), that air the same [title] back to back,
 * each too narrow to write it ([titleCut]): a channel of shorts, cartoons or music videos. The grid writes the title
 * once across them, with how many programmes the run holds ([count]), instead of cutting it in every cell. Each cell
 * stays its own focus stop, panel and description; only the drawn label is shared.
 */
@Immutable
internal data class CellRun(
    val first: Int,
    val last: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
) {
    /** The programmes in the run: true for repeats of one short as much as for episodes of a series. */
    val count: Int get() = last - first + 1
}

/**
 * What a cell is matched on: its display title, the series name for an episode of a known series (so one series is one
 * run whatever its episodes are called) and the item's own title otherwise. Only a programme has one; a slate, a
 * break and a programme that left the library never join a run.
 */
private fun runKey(cell: GuideCell): String? = cell.title?.takeIf { cell.kind == CellKind.Program && it.isNotBlank() }

/**
 * The runs of [cells] (one row, in time order): two or more cells in a row with the same [runKey], each starting where
 * the one before it ends and each [cut]. [cut] is asked only of cells beside one of the same title, so a row with no
 * repeats measures nothing. Run once per row model, never while laying out or drawing.
 */
internal fun shortRuns(
    cells: List<GuideCell>,
    cut: (GuideCell) -> Boolean,
): List<CellRun> {
    val runs = ArrayList<CellRun>()
    var i = 0
    while (i < cells.size) {
        val key = runKey(cells[i])
        // The stretch of one title, back to back.
        var end = i
        while (key != null && end + 1 < cells.size && joins(cells[end], cells[end + 1], key)) end++
        if (end == i) {
            i++
            continue
        }
        // Within it, the runs of cells too narrow for the title.
        var from = i
        while (from <= end) {
            if (!cut(cells[from])) {
                from++
                continue
            }
            var to = from
            while (to + 1 <= end && cut(cells[to + 1])) to++
            if (to > from) runs += CellRun(from, to, checkNotNull(key), cells[from].startMs, cells[to].endMs)
            from = to + 1
        }
        i = end + 1
    }
    return runs
}

private fun joins(
    before: GuideCell,
    after: GuideCell,
    key: String,
): Boolean = after.startMs == before.endMs && runKey(after) == key

/**
 * Where a run's label goes, in the run's own coordinates (from its left edge): [span] is the run laid out as one cell,
 * and the label is pinned to the part of it in view, as a long programme's title is. While one of the run's cells has
 * the focus, that cell writes its own title, so the label takes the wider side of the run in view beside it. Plain
 * arithmetic over arrays made once per row model: read while laying out, it allocates nothing.
 */
@Immutable
internal class RunPlace(
    run: CellRun,
    cells: List<GuideCell>,
    originMs: Long,
    geometry: GuideGeometry,
) {
    val span = CellPlace(run.startMs, run.endMs, originMs, geometry)
    private val starts = LongArray(run.count) { cells[run.first + it].startMs }
    private val lefts = FloatArray(run.count)
    private val rights = FloatArray(run.count)

    init {
        for (k in 0 until run.count) {
            val cell = cells[run.first + k]
            val place = CellPlace(cell.startMs, cell.endMs, originMs, geometry)
            lefts[k] = (place.x - span.x).value
            rights[k] = (place.x + place.width - span.x).value
        }
    }

    private fun focusedIndex(focusStartMs: Long?): Int = if (focusStartMs == null) -1 else starts.indexOf(focusStartMs)

    /** Whether the label takes the run's part after the focused cell [k] rather than the part before it. */
    private fun after(
        k: Int,
        viewportStartMs: Long,
    ): Boolean {
        val from = span.visibleLeft(viewportStartMs).value
        val to = span.visibleRight(viewportStartMs).value
        val before = (minOf(to, lefts[k]) - from).coerceAtLeast(0f)
        val behind = (to - maxOf(from, rights[k])).coerceAtLeast(0f)
        return behind >= before
    }

    /**
     * The label's left edge with the viewport at [viewportStartMs] and the focus on the cell starting at
     * [focusStartMs] of this row (null: the focus is elsewhere).
     */
    fun labelLeft(
        viewportStartMs: Long,
        focusStartMs: Long?,
    ): Dp {
        val from = span.visibleLeft(viewportStartMs)
        val k = focusedIndex(focusStartMs)
        if (k < 0 || !after(k, viewportStartMs)) return from
        return maxOf(from, rights[k].dp)
    }

    /** The label's right edge, as [labelLeft]. */
    fun labelRight(
        viewportStartMs: Long,
        focusStartMs: Long?,
    ): Dp {
        val to = span.visibleRight(viewportStartMs)
        val k = focusedIndex(focusStartMs)
        if (k < 0 || after(k, viewportStartMs)) return to
        return minOf(to, lefts[k].dp)
    }

    /** Whether the label has room: as a cell has for a title, [CompactBelow] or more. */
    fun labelShown(
        viewportStartMs: Long,
        focusStartMs: Long?,
    ): Boolean = labelRight(viewportStartMs, focusStartMs) - labelLeft(viewportStartMs, focusStartMs) >= CompactBelow

    /** "‹" room: the run goes on before the viewport (its first cell in view draws the chevron). */
    fun continuesEarlier(viewportStartMs: Long): Boolean = span.continuesEarlier(viewportStartMs)

    /** "›" room: the run goes on after the viewport. */
    fun continuesLater(viewportStartMs: Long): Boolean = span.continuesLater(viewportStartMs)
}

/**
 * Whether a cell writes its own title: never when [compact] (a gap, a filler, a slate or a programme too narrow for any
 * text is its fill alone, with no "…"; the details panel names it when focused), and in a run of short programmes
 * ([inRun]) only while [focused], since the run's one label writes it for the rest.
 */
internal fun titleShown(
    compact: Boolean,
    inRun: Boolean,
    focused: Boolean,
): Boolean = !compact && (!inRun || focused)

/** One row's runs, their places, and which of its cells are in one: worked out once per row model. */
@Immutable
internal class RowRuns(
    val runs: List<CellRun>,
    val places: List<RunPlace>,
    private val inRun: BooleanArray,
) {
    /** Whether the row's cell at [index] is in a run, so writes no title of its own unless focused. */
    fun inRun(index: Int): Boolean = inRun[index]
}

/** [cells]' runs, measuring titles with [titles]. A row of distinct titles measures nothing. */
internal fun rowRuns(
    cells: List<GuideCell>,
    originMs: Long,
    geometry: GuideGeometry,
    titles: TitleWidths,
): RowRuns {
    val widths = HashMap<String, Dp>()
    val runs =
        shortRuns(cells) { cell ->
            val width = CellPlace(cell.startMs, cell.endMs, originMs, geometry).width
            val title = cell.title.orEmpty()
            titleCut(width) { widths.getOrPut(title) { titles.of(title) } }
        }
    val inRun = BooleanArray(cells.size)
    for (run in runs) inRun.fill(true, run.first, run.last + 1)
    return RowRuns(runs, runs.map { RunPlace(it, cells, originMs, geometry) }, inRun)
}

/** How wide a cell's title is, in its own style, measured with the grid's measurer. */
@Immutable
internal class TitleWidths(
    private val measure: (String) -> Int,
    private val density: Density,
) {
    fun of(title: String): Dp = with(density) { measure(title).toDp() }
}

/** The grid's [TitleWidths] for the current font and density; one per grid, not per row. */
@Composable
internal fun rememberTitleWidths(): TitleWidths {
    val measurer = rememberTextMeasurer()
    val base = LocalTextStyle.current
    val density = LocalDensity.current
    return remember(measurer, base, density) {
        val style: TextStyle = base.merge(CellTitleStyle)
        TitleWidths(
            measure = { title ->
                measurer.measure(title, style, softWrap = false, maxLines = 1, density = density).size.width
            },
            density = density,
        )
    }
}

/**
 * A run's one label: its title, cut if it must, then how many programmes it holds ("Big Buck Bunny · 12"), in the
 * cells' title face. Drawing only: a screen reader hears each cell's own description and not this ([clearAndSetSemantics]
 * with nothing set). The focus and the viewport are read while laying it out, so a move or a page recomposes nothing.
 */
@Composable
internal fun RunLabelView(
    channel: ChannelId,
    run: CellRun,
    place: RunPlace,
    focusOf: () -> GuideFocus?,
    viewportOf: () -> Long,
) {
    val colors = LocalGuideColors.current
    Box(
        Modifier
            .offset(x = place.span.x)
            .width(place.span.width)
            .fillMaxHeight()
            .padding(CellInset)
            .testTag(GuideTags.runLabel(channel, run.startMs))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.runLabelLayout(channel, place, focusOf, viewportOf)) {
            Text(
                text = run.title,
                modifier = Modifier.weight(1f, fill = false).testTag(GuideTags.runLabelTitle(channel, run.startMs)),
                color = colors.programmeText,
                style = CellTitleStyle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.guide_run_count, run.count),
                modifier = Modifier.testTag(GuideTags.runLabelCount(channel, run.startMs)),
                color = colors.textSecondary,
                style = CellTitleStyle,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Lays the label out in [RunPlace.labelLeft] to [RunPlace.labelRight], or not at all where it has no room. */
private fun Modifier.runLabelLayout(
    channel: ChannelId,
    place: RunPlace,
    focusOf: () -> GuideFocus?,
    viewportOf: () -> Long,
): Modifier =
    layout { measurable, constraints ->
        val viewport = viewportOf()
        val focus = focusOf()?.takeIf { it.channel == channel }?.cellStartMs
        if (!place.labelShown(viewport, focus)) return@layout layout(constraints.maxWidth, 0) {}
        val left = place.labelLeft(viewport, focus)
        val right = place.labelRight(viewport, focus)
        // The chevrons are drawn by the cells at the viewport's edges; leave them their room, as a long title does.
        val earlier = place.continuesEarlier(viewport) && left == place.span.visibleLeft(viewport)
        val later = place.continuesLater(viewport) && right == place.span.visibleRight(viewport)
        placeText(measurable, constraints, left, right, earlier, later)
    }
