// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.content.res.Resources
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.zIndex
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
private val EpisodeFrom = 160.dp
private val RingWidth = 3.dp
private val TextStart = 10.dp
private val TextEnd = 4.dp
private val ChevronInset = 6.dp
private val ChevronSize = 5.dp
private val ChevronRoom = 14.dp
private val CompactTick = 2.dp
private val LabelGap = 4.dp
private const val TINT_ALPHA = 0.08f
private const val DASH_ALPHA = 0.6f
private const val COMPACT_TICK_FRACTION = 0.4f

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

    /** Too narrow for a title: the tick, and a floating label when focused. */
    val compact: Boolean = width < CompactBelow

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
}

/**
 * One cell, placed by time from the origin. It reads only whether it is the focused cell (derived from [focusOf] by
 * identity: channel and start), so a focus move recomposes the cell it left and the one it reached, and a horizontal
 * page recomposes none: [viewportOf] is read only while laying out (the sticky title, the compact label) and drawing
 * (the chevrons). Fill, ring, slate dashes, chevrons and the compact tick are drawn; nothing animates or scales.
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
    val onNow by remember(cell, nowOf) {
        derivedStateOf { nowOf()?.let { cell.startMs <= it && it < cell.endMs } == true }
    }
    // The description is put together only when something asks for the semantics (a screen reader, a test), not in
    // every cell's composition.
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
        if (!place.compact && title.isNotEmpty()) {
            val tag = Modifier.testTag(GuideTags.cellText(channel, cell.startMs))
            CellText(cell, title, place, focused, tag.stickyText(place, viewportOf))
        }
    }
    if (place.compact && focused && title.isNotEmpty()) CompactLabel(title, place, viewportOf)
}

/**
 * Lays the title out inside the visible part of the cell: from the viewport's left edge for a cell that began before
 * it, and short of its right edge for one that runs past it, leaving room for the chevrons. The viewport is read here,
 * in the measure pass, so paging moves the title without composing anything.
 */
private fun Modifier.stickyText(
    place: CellPlace,
    viewportOf: () -> Long,
): Modifier =
    layout { measurable, constraints ->
        val viewport = viewportOf()
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
    val minor = if (focused) BroadcastColors.Ink else BroadcastColors.Ground
    val numbers =
        cell.info?.takeIf { it.kind == ScheduleItemKind.Episode && place.width >= EpisodeFrom }?.let(::numbersOf)
    val text =
        buildAnnotatedString {
            withStyle(SpanStyle(color = main)) { append(title) }
            numbers?.let { withStyle(SpanStyle(color = minor, fontWeight = FontWeight.Normal)) { append("  $it") } }
        }
    Text(
        text = text,
        modifier = modifier,
        style =
            if (slate) {
                TextStyle(fontSize = 16.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal)
            } else {
                TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
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

/**
 * The room beside a compact cell for its floating title, in px on the row: from [start] to [end], on the cell's right
 * or its left. A label no wider than [room] placed at [x] stays inside the viewport and clear of the cell.
 */
internal class LabelSpot(
    private val start: Int,
    private val end: Int,
    private val onRight: Boolean,
    private val viewportLeft: Int,
    private val viewportRight: Int,
) {
    /** How wide the label may be. */
    val room: Int get() = maxOf(0, end - start)

    /** Where a label [width] wide starts: against the cell's side, then kept inside the viewport. */
    fun x(width: Int): Int {
        val beside = if (onRight) start else end - width
        return beside.coerceIn(viewportLeft, maxOf(viewportLeft, viewportRight - width))
    }
}

/**
 * Where the title of a compact cell from [cellLeft] to [cellRight] floats, in px on the row: [gap] beside the cell on
 * the side where the viewport (from [viewportLeft] to [viewportRight]) has more room, so it never covers the cell or
 * its focus ring, drawn inside the cell.
 */
internal fun compactLabelSpot(
    cellLeft: Int,
    cellRight: Int,
    viewportLeft: Int,
    viewportRight: Int,
    gap: Int,
): LabelSpot {
    val rightStart = cellRight + gap
    val leftEnd = cellLeft - gap
    return if (viewportRight - rightStart >= leftEnd - viewportLeft) {
        LabelSpot(rightStart, viewportRight, onRight = true, viewportLeft, viewportRight)
    } else {
        LabelSpot(viewportLeft, leftEnd, onRight = false, viewportLeft, viewportRight)
    }
}

/**
 * The title of a focused cell too narrow to hold it: one line, Ink on Accent, floating beside the cell above its
 * neighbours, on the side with more room and never wider than that room, so a long title ends in its ellipsis rather
 * than over the cell. It takes no space in the row; the side is chosen while laying out, from the viewport, so paging
 * does not compose it again.
 */
@Composable
private fun CompactLabel(
    title: String,
    place: CellPlace,
    viewportOf: () -> Long,
) {
    Text(
        text = title,
        modifier =
            Modifier
                .zIndex(1f)
                .layout { measurable, constraints ->
                    val viewport = viewportOf()
                    val spot =
                        compactLabelSpot(
                            cellLeft = place.x.roundToPx(),
                            cellRight = (place.x + place.width).roundToPx(),
                            viewportLeft = place.viewportLeft(viewport).roundToPx(),
                            viewportRight = place.viewportRight(viewport).roundToPx(),
                            gap = LabelGap.roundToPx(),
                        )
                    val label = measurable.measure(Constraints(maxWidth = spot.room))
                    val y = (constraints.maxHeight - label.height) / 2
                    layout(0, 0) { label.placeRelative(spot.x(label.width), y) }
                }.background(BroadcastColors.Accent)
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .testTag(GuideTags.COMPACT_LABEL),
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        color = BroadcastColors.Ink,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
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
 * White ring inside the bounds, so the row's clip can never cut it. Chevrons mark a program running on past either
 * edge of the viewport, at that edge; they read [viewportOf] while drawing. A compact cell has a small centre tick.
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
            when {
                isFocused -> {
                    drawRect(BroadcastColors.Accent)
                    drawRect(
                        Color.White,
                        Offset(ring / 2, ring / 2),
                        Size(size.width - ring, size.height - ring),
                        style = Stroke(ring),
                    )
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
            if (place.compact) {
                if (!isFocused) compactTick(middle)
            } else {
                val mark = if (isFocused) BroadcastColors.Ink else BroadcastColors.Cream
                val at = viewportOf()
                val from = place.visibleLeft(at).toPx() - inset
                val to = place.visibleRight(at).toPx() - inset
                if (place.continuesEarlier(at)) translate(left = from) { drawPath(left, mark, style = chevron) }
                if (place.continuesLater(at)) translate(left = to) { drawPath(right, mark, style = chevron) }
            }
        }
    }

/** The small Ground tick in the centre of a compact cell that is not focused. */
private fun DrawScope.compactTick(middle: Float) {
    val tick = CompactTick.toPx()
    val tall = size.height * COMPACT_TICK_FRACTION
    drawRect(BroadcastColors.Ground, Offset(size.width / 2 - tick / 2, middle - tall / 2), Size(tick, tall))
}
