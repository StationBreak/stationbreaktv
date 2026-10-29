// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.GuideFocus
import tv.stationbreak.feature.guide.R
import java.time.ZoneId

private val CellInset = 1.dp
private val MinCellWidth = 20.dp
private val CompactBelow = 80.dp

/** The narrowest cell that has room for a "…": the glyph (about 16 dp at 16 sp bold) and 4 dp either side of it. */
internal val EllipsisFrom = 28.dp
private val EllipsisInset = 4.dp
private const val ELLIPSIS = "…"
private val EpisodeFrom = 160.dp
private val RingWidth = 3.dp
private val TextStart = 10.dp
private val TextEnd = 4.dp
private val ChevronInset = 6.dp
private val ChevronSize = 5.dp
private val ChevronRoom = 14.dp
private const val TINT_ALPHA = 0.08f
private const val DASH_ALPHA = 0.6f

/**
 * Called with a cell's channel and start each time that cell composes. Tests count compositions with it; null (the
 * default) in the app.
 */
internal val LocalCellComposed = staticCompositionLocalOf<((ChannelId, Long) -> Unit)?> { null }

/**
 * Where a cell sits on its row: by time from the grid's [originMs], never from the viewport, so paging the viewport
 * moves the row (one shared offset, in the layout phase) and leaves the cell's composition alone. A cell that began
 * before the origin starts at it.
 */
@Immutable
internal class CellPlace(
    private val startMs: Long,
    private val endMs: Long,
    private val originMs: Long,
    private val geometry: GuideGeometry,
) {
    private val fromMs = maxOf(startMs, originMs)

    /** The cell's left edge from the origin. */
    val x: Dp = geometry.widthOf(fromMs - originMs)

    /** The cell's width: its length from the origin, at least [MinCellWidth] so a 3-minute cell can be seen. */
    val width: Dp = maxOf(geometry.widthOf(endMs - fromMs), MinCellWidth)

    /**
     * Too narrow for a title: the cell is its fill and nothing else (the gap to the next cell is its divider), and the
     * ring when focused. The details panel names it; nothing is drawn over its neighbours.
     */
    val compact: Boolean = width < CompactBelow

    /**
     * A compact cell wide enough ([EllipsisFrom]) to say "…": something airs here, though no part of its title fits.
     * Decided from the width alone, so it needs no text measurement; narrower cells stay blank.
     */
    val showsEllipsis: Boolean = compact && width >= EllipsisFrom

    /**
     * Compact with the viewport at [viewportStartMs]: [compact], or so little of the cell in view (a long program
     * scrolled to a sliver at either edge) that a title would not fit there either. Read while laying out and drawing,
     * so paging composes nothing.
     */
    fun compactIn(viewportStartMs: Long): Boolean {
        val inView = visibleRight(viewportStartMs) - visibleLeft(viewportStartMs)
        return compact || inView < CompactBelow
    }

    /** The viewport's left edge from the cell's, clamped to the cell: where its title starts (sticky title). */
    fun visibleLeft(viewportStartMs: Long): Dp = (viewportLeft(viewportStartMs) - x).coerceIn(0.dp, width)

    /** The viewport's right edge from the cell's, clamped to the cell. */
    fun visibleRight(viewportStartMs: Long): Dp = (viewportRight(viewportStartMs) - x).coerceIn(0.dp, width)

    /** The viewport's left edge on the row, from the origin. */
    fun viewportLeft(viewportStartMs: Long): Dp = geometry.widthOf(viewportStartMs - originMs)

    /** The viewport's right edge on the row, from the origin. */
    fun viewportRight(viewportStartMs: Long): Dp = viewportLeft(viewportStartMs) + geometry.viewportWidth

    /** "‹": the cell runs on before the viewport, and that earlier time can be scrolled to (not at the origin). */
    fun continuesEarlier(viewportStartMs: Long): Boolean = viewportStartMs > originMs && startMs < viewportStartMs

    /** "›": the cell runs on after the viewport. */
    fun continuesLater(viewportStartMs: Long): Boolean = endMs > viewportStartMs + geometry.spanMs

    /**
     * What the cell draws over its fill with the viewport at [viewportStartMs], [focused] or not: see [CellMarks]. The
     * ring goes round the part of the cell that is in view, so a cell cut by either edge of the time columns has a
     * closed ring inside them (and so inside the safe area); that edge is the ring's, and its chevron is not drawn.
     */
    fun marks(
        viewportStartMs: Long,
        focused: Boolean,
    ): CellMarks {
        val inner = width - CellInset * 2
        val from = (visibleLeft(viewportStartMs) - CellInset).coerceIn(0.dp, inner)
        val to = (visibleRight(viewportStartMs) - CellInset).coerceIn(from, inner)
        return CellMarks(
            ring = if (focused) from..to else null,
            earlier = !focused && !compactIn(viewportStartMs) && continuesEarlier(viewportStartMs),
            later = !focused && !compactIn(viewportStartMs) && continuesLater(viewportStartMs),
        )
    }
}

/**
 * What a cell draws over its fill, in its own coordinates (inside its 1 dp inset): the focus [ring]'s left and right
 * edges when it is focused, and the "‹" ([earlier]) and "›" ([later]) chevrons at the viewport's edges.
 */
internal data class CellMarks(
    val ring: ClosedRange<Dp>?,
    val earlier: Boolean,
    val later: Boolean,
)

/**
 * One cell, placed by time from the origin. It reads only whether it is the focused cell (derived from [focusOf] by
 * identity: channel and start), so a focus move recomposes the cell it left and the one it reached, and a horizontal
 * page recomposes none: [viewportOf] is read only while laying out (the sticky title) and drawing (the ring and the
 * chevrons). Fill, ring, slate dashes and chevrons are drawn; nothing animates or scales. A compact cell writes
 * no title, focused or not (the details panel names the focused cell), only a "…" if it has room ([EllipsisFrom]).
 */
@Suppress("LongParameterList") // One cell's identity, place and the three readers it needs.
@Composable
internal fun GuideCellView(
    channel: ChannelId,
    cell: GuideCell,
    place: CellPlace,
    focusOf: () -> GuideFocus?,
    viewportOf: () -> Long,
    nowOf: () -> Long?,
) {
    val focused by remember(channel, cell.startMs, focusOf) {
        derivedStateOf { focusOf()?.let { it.channel == channel && it.cellStartMs == cell.startMs } == true }
    }
    LocalCellComposed.current?.invoke(channel, cell.startMs)
    val copy = programCopy()
    val title = cellTitle(cell, copy)
    // Read here, in composition, so the cell whose "on now" flips (and only it) composes again and its semantics with
    // it; the description itself is put together only when something asks for the semantics (a screen reader, a
    // test), not in every cell's composition.
    val onNow =
        remember(cell, nowOf) {
            derivedStateOf { nowOf()?.let { cell.startMs <= it && it < cell.endMs } == true }
        }.value
    val resources = LocalResources.current
    val is24h = is24Hour()
    Box(
        Modifier
            .offset(x = place.x)
            .width(place.width)
            .fillMaxHeight()
            .padding(CellInset)
            .cellLook(cell.kind == CellKind.Slate, place, viewportOf) { focused }
            .semantics {
                selected = focused
                contentDescription = cellDescription(resources, cell, title, onNow, is24h)
            }.testTag(GuideTags.cell(channel, cell.startMs)),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (title.isEmpty()) return@Box
        if (!place.compact) {
            val tag = Modifier.testTag(GuideTags.cellText(channel, cell.startMs))
            CellText(cell, title, place, focused, tag.stickyText(place, viewportOf))
        } else if (place.showsEllipsis) {
            // The cell's own description carries the title; this mark is only for the eye, so it says nothing itself.
            val tag = Modifier.clearAndSetSemantics { testTag = GuideTags.cellEllipsis(channel, cell.startMs) }
            CellText(cell, ELLIPSIS, place, focused, tag.padding(horizontal = EllipsisInset))
        }
    }
}

/**
 * Lays the title out inside the visible part of the cell: from the viewport's left edge for a cell that began before
 * it, and short of its right edge for one that runs past it, leaving room for the chevrons. With too little of the cell
 * in view for a title ([CellPlace.compactIn]) nothing is placed. The viewport is read here, in the measure pass, so
 * paging moves the title without composing anything.
 */
private fun Modifier.stickyText(
    place: CellPlace,
    viewportOf: () -> Long,
): Modifier =
    layout { measurable, constraints ->
        val viewport = viewportOf()
        // Not measured at all: a title measured to no width is still laid out, on every pass.
        if (place.compactIn(viewport)) return@layout layout(constraints.maxWidth, 0) {}
        val inset = CellInset.roundToPx()
        val start =
            (place.visibleLeft(viewport).roundToPx() - inset).coerceAtLeast(0) +
                (TextStart + if (place.continuesEarlier(viewport)) ChevronRoom else 0.dp).roundToPx()
        val end =
            (place.visibleRight(viewport).roundToPx() - inset).coerceAtMost(constraints.maxWidth) -
                (if (place.continuesLater(viewport)) ChevronRoom else TextEnd).roundToPx()
        val text = measurable.measure(Constraints(maxWidth = (end - start).coerceAtLeast(0)))
        layout(constraints.maxWidth, text.height) { text.placeRelative(start, 0) }
    }

/** The cell's title (and "S2 E5" when it is 20 minutes or more wide), or a slate's "Off air until". */
@Composable
private fun CellText(
    cell: GuideCell,
    title: String,
    place: CellPlace,
    focused: Boolean,
    modifier: Modifier,
) {
    val slate = cell.kind == CellKind.Slate
    val main =
        when {
            focused -> BroadcastColors.Ink
            slate -> BroadcastColors.Ground
            else -> BroadcastColors.Cream
        }
    val numbers =
        cell.info?.takeIf { it.kind == ScheduleItemKind.Episode && place.width >= EpisodeFrom }?.let(::numbersOf)
    // The title's colour is the text's own, not a span of it: a focus move changes only that, which is drawn, so the
    // text is not laid out again. The numbers keep a span of their own colour, so a cell that shows them is.
    val minor =
        when {
            numbers == null -> Color.Unspecified
            focused -> BroadcastColors.Ink
            else -> BroadcastColors.Ground
        }
    val text =
        remember(title, numbers, minor) {
            buildAnnotatedString {
                append(title)
                numbers?.let { withStyle(SpanStyle(color = minor, fontWeight = FontWeight.Normal)) { append("  $it") } }
            }
        }
    Text(
        text = text,
        modifier = modifier,
        color = main,
        style =
            if (slate) {
                TextStyle(fontSize = 16.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal)
            } else {
                TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
        maxLines = 1,
        softWrap = false,
        overflow = if (place.compact) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

private fun numbersOf(info: ProgramInfo): String? {
    val season = info.seasonNumber
    val episode = info.episodeNumber
    return when {
        season != null && episode != null -> "S$season E$episode"
        episode != null -> "E$episode"
        else -> null
    }
}

/** "Title, 8:30 – 9:00 PM", and ", on now" for the program airing: what a screen reader says about a cell. */
private fun cellDescription(
    resources: Resources,
    cell: GuideCell,
    title: String,
    onNow: Boolean,
    is24h: Boolean,
): String {
    val slot = ProgramText.slot(cell.startMs, cell.endMs, ZoneId.systemDefault(), is24h)
    val described = resources.getString(R.string.guide_cell_description, title, slot)
    return if (onNow) resources.getString(R.string.guide_cell_on_now, described) else described
}

/**
 * The cell's look, drawn: Cream at 8% (a slate: a dashed Ground border instead), or when [focused] Accent with a 3 dp
 * White ring inside the part of the cell in view, so the row's clip can never cut it. Chevrons mark a program running
 * on past either edge of the viewport, at that edge, unless it is focused; they read [viewportOf] while drawing. A
 * compact cell has none.
 */
private fun Modifier.cellLook(
    slate: Boolean,
    place: CellPlace,
    viewportOf: () -> Long,
    focused: () -> Boolean,
): Modifier =
    drawWithCache {
        val ring = RingWidth.toPx()
        val dashWidth = 1.5.dp.toPx()
        val dash = Stroke(dashWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        val chevron = Stroke(width = 2.dp.toPx())
        val edge = ChevronInset.toPx()
        val inset = CellInset.toPx()
        val half = ChevronSize.toPx()
        val middle = size.height / 2
        // Drawn at x = 0 and moved to the viewport's edge as it is drawn.
        val left =
            Path().apply {
                moveTo(edge + half, middle - half)
                lineTo(edge, middle)
                lineTo(edge + half, middle + half)
            }
        val right =
            Path().apply {
                moveTo(-edge - half, middle - half)
                lineTo(-edge, middle)
                lineTo(-edge - half, middle + half)
            }
        onDrawBehind {
            val isFocused = focused()
            val at = viewportOf()
            val marks = place.marks(at, isFocused)
            when {
                isFocused -> {
                    drawRect(BroadcastColors.Accent)
                    marks.ring?.let {
                        val from = it.start.toPx()
                        val to = it.endInclusive.toPx()
                        drawRect(
                            Color.White,
                            Offset(from + ring / 2, ring / 2),
                            Size(to - from - ring, size.height - ring),
                            style = Stroke(ring),
                        )
                    }
                }

                slate -> {
                    drawRect(
                        BroadcastColors.Ground.copy(alpha = DASH_ALPHA),
                        Offset(dashWidth / 2, dashWidth / 2),
                        Size(size.width - dashWidth, size.height - dashWidth),
                        style = dash,
                    )
                }

                else -> {
                    drawRect(BroadcastColors.Cream.copy(alpha = TINT_ALPHA))
                }
            }
            val mark = if (isFocused) BroadcastColors.Ink else BroadcastColors.Cream
            val from = place.visibleLeft(at).toPx() - inset
            val to = place.visibleRight(at).toPx() - inset
            if (marks.earlier) translate(left = from) { drawPath(left, mark, style = chevron) }
            if (marks.later) translate(left = to) { drawPath(right, mark, style = chevron) }
        }
    }
