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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.GuideFocus

/**
 * Consecutive cells of one row, [first] to [last] (their indexes in the row), that air the same [title] back to back,
 * each with its title cut and no episode line to lose ([runCut]): a channel of shorts, cartoons or music videos.
 * The grid writes the title once across them, with how many programmes the run holds in the guide ([count]), instead
 * of cutting it in every cell. Each cell stays its own focus stop, panel and description; only the drawn label is
 * shared.
 */
@Immutable
internal data class CellRun(
    val first: Int,
    val last: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
) {
    /**
     * How many programmes the run holds in the loaded guide window (from the half hour now to the end of the model;
     * airings beyond it are not counted): true for repeats of one short as much as for episodes of a series.
     */
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
 * Whether a title [titleWidth] wide is cut in a cell [width] wide, all of it in view: the room the cell's title is laid
 * out in ([textRoom]) is narrower than the title, or the cell is compact and writes no title at all, in which case
 * [titleWidth] is not asked.
 */
internal fun titleCut(
    width: Dp,
    titleWidth: () -> Dp,
): Boolean = width < CompactBelow || titleWidth() > textRoom(0.dp, width, width, earlier = false, later = false)

/**
 * Whether a cell [width] wide showing [info] joins a run of short programmes ([shortRuns]): its title is cut
 * ([titleCut]) and it has no second line ("S3 E4", [secondLineOf] at [fontScale]) to lose. An episode with its line
 * keeps its own title and line, as before runs; a short or a movie of any width whose title is cut can join.
 */
internal fun runCut(
    info: ProgramInfo?,
    width: Dp,
    fontScale: Float,
    titleWidth: () -> Dp,
): Boolean = secondLineOf(info, width.value, fontScale, RowHeight.value) == null && titleCut(width, titleWidth)

/** The least of a title a run's label must show: what the narrowest titled cell ([CompactBelow]) has room for. */
internal val MinLabelTitle: Dp
    get() = textRoom(0.dp, CompactBelow, CompactBelow, earlier = false, later = false)

/**
 * Where a run's label goes, in the run's own coordinates (from its left edge): [span] is the run laid out as one cell,
 * and the label is pinned to the part of it in view, as a long programme's title is. While one of the run's cells has
 * the focus, that cell writes its own title (or, compact, carries the focus fill, which the label's cream must not
 * cross), so the label takes the wider side of the run in view beside it; on a tie, the side after it. [need] is the
 * least room the label is shown in: its count whole and at least [MinLabelTitle] of its title. Plain arithmetic over
 * arrays made once per row model: read while laying out, it allocates nothing.
 */
@Immutable
internal class RunPlace(
    run: CellRun,
    cells: List<GuideCell>,
    originMs: Long,
    geometry: GuideGeometry,
    val need: Dp,
) {
    val span = CellPlace(run.startMs, run.endMs, originMs, geometry)
    private val places =
        Array(run.count) { cells[run.first + it].let { c -> CellPlace(c.startMs, c.endMs, originMs, geometry) } }
    private val starts = LongArray(run.count) { cells[run.first + it].startMs }

    // Each cell's drawn edges from the run's left edge (a cell under the least width is drawn that wide).
    private val lefts = FloatArray(run.count) { (places[it].x - span.x).value }
    private val rights = FloatArray(run.count) { (places[it].x + places[it].width - span.x).value }

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

    /**
     * Room for the "‹" before the label: the label starts at the viewport's left edge and the run's cell there draws
     * the chevron ([CellPlace.drawsEarlier]: it crosses that edge, is not compact there and is not focused). A run that
     * merely began earlier, its cell at the edge a sliver, draws none, so none is kept, as with a sticky title.
     */
    fun earlierRoom(
        viewportStartMs: Long,
        focusStartMs: Long?,
    ): Boolean =
        labelLeft(viewportStartMs, focusStartMs) == span.visibleLeft(viewportStartMs) &&
            edgeChevron(focusStartMs) { it.drawsEarlier(viewportStartMs) }

    /** Room for the "›" after the label, as [earlierRoom] at the viewport's right edge. */
    fun laterRoom(
        viewportStartMs: Long,
        focusStartMs: Long?,
    ): Boolean =
        labelRight(viewportStartMs, focusStartMs) == span.visibleRight(viewportStartMs) &&
            edgeChevron(focusStartMs) { it.drawsLater(viewportStartMs) }

    // Whether a cell of the run other than the focused one [draws] its chevron. Inline: no lambda is made.
    private inline fun edgeChevron(
        focusStartMs: Long?,
        draws: (CellPlace) -> Boolean,
    ): Boolean {
        val k = focusedIndex(focusStartMs)
        var any = false
        for (i in places.indices) any = any || (i != k && draws(places[i]))
        return any
    }

    /**
     * Whether the label is shown: the room it is laid out in ([textRoom], less the chevrons' where they are drawn)
     * holds [need]. Where it does not, no label is drawn rather than a clipped count.
     */
    fun labelShown(
        viewportStartMs: Long,
        focusStartMs: Long?,
    ): Boolean =
        textRoom(
            labelLeft(viewportStartMs, focusStartMs),
            labelRight(viewportStartMs, focusStartMs),
            span.width,
            earlierRoom(viewportStartMs, focusStartMs),
            laterRoom(viewportStartMs, focusStartMs),
        ) >= need
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

/** One row's runs, their places and count lines, and which of its cells are in one: worked out once per row model. */
@Immutable
internal class RowRuns(
    val runs: List<CellRun>,
    val places: List<RunPlace>,
    val counts: List<String>,
    private val inRun: BooleanArray,
) {
    /** Whether the row's cell at [index] is in a run, so writes no title of its own unless focused. */
    fun inRun(index: Int): Boolean = inRun[index]
}

/**
 * [cells]' runs ([runCut]), each with its count line ([countLine], " · 12") and the room its label needs: the count's
 * measured width and [MinLabelTitle] of the title (all of it, if shorter). Titles and counts are measured with
 * [titles], which keeps what it has measured, so a row of distinct titles measures nothing and a row paged back into
 * view measures nothing again.
 */
internal fun rowRuns(
    cells: List<GuideCell>,
    originMs: Long,
    geometry: GuideGeometry,
    titles: TitleWidths,
    countLine: (Int) -> String,
): RowRuns {
    val runs =
        shortRuns(cells) { cell ->
            val width = CellPlace(cell.startMs, cell.endMs, originMs, geometry).width
            runCut(cell.info, width, titles.fontScale) { titles.of(cell.title.orEmpty()) }
        }
    val inRun = BooleanArray(cells.size)
    for (run in runs) inRun.fill(true, run.first, run.last + 1)
    val counts = runs.map { countLine(it.count) }
    val places =
        runs.mapIndexed { i, run ->
            val need = titles.of(counts[i]) + minOf(titles.of(run.title), MinLabelTitle)
            RunPlace(run, cells, originMs, geometry, need)
        }
    return RowRuns(runs, places, counts, inRun)
}

/**
 * How wide a title (or a count line) is in a cell's title face, measured with the grid's measurer once and kept: one
 * per grid, for one font and density ([rememberTitleWidths] makes a new one when either changes), so paging and rows
 * coming back into view measure nothing they have measured before.
 */
@Stable
internal class TitleWidths(
    private val measure: (String) -> Int,
    private val density: Density,
) {
    // A cache of what [measure] gives, not part of the identity: equal inputs always give equal widths, so filling it
    // changes nothing anyone reading this sees, and no recomposition is owed.
    private val widths = HashMap<String, Dp>()

    /** The font scale the titles are measured at: a cell's second line depends on it ([runCut]). */
    val fontScale: Float get() = density.fontScale

    /** [title]'s width, measured the first time it is asked for. */
    fun of(title: String): Dp = widths.getOrPut(title) { with(density) { measure(title).toDp() } }
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
 * A run's one label: its title, cut if it must, then how many programmes of the run the guide holds ([count], " · 12"),
 * both in the cells' 13 sp Medium title face, the count in the second line's colour. The count is never cut: where
 * there is no room for it and some of the title, there is no label ([RunPlace.labelShown]). Drawing only: a screen
 * reader hears each cell's own description and not this ([clearAndSetSemantics] with nothing set). The focus and the
 * viewport are read while laying it out, so a move or a page recomposes nothing.
 */
@Suppress("LongParameterList") // The row, the run, its place and count, and the two readers.
@Composable
internal fun RunLabelView(
    channel: ChannelId,
    run: CellRun,
    place: RunPlace,
    count: String,
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
                text = count,
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
        placeText(
            measurable,
            constraints,
            place.labelLeft(viewport, focus),
            place.labelRight(viewport, focus),
            earlier = place.earlierRoom(viewport, focus),
            later = place.laterRoom(viewport, focus),
        )
    }
