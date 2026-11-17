// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.core.ui.guideFigures
import tv.stationbreak.core.ui.mark.ChannelMark
import tv.stationbreak.core.ui.mark.LocalMarkCache
import tv.stationbreak.core.ui.mark.LocalMarkTextMeasurer
import tv.stationbreak.core.ui.mark.variantForTint
import tv.stationbreak.feature.guide.GUIDE_VISIBLE_ROWS
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.GuideFocus
import tv.stationbreak.feature.guide.GuideModel
import tv.stationbreak.feature.guide.GuideRow
import tv.stationbreak.feature.guide.R
import tv.stationbreak.feature.guide.SLOT_MS
import tv.stationbreak.feature.guide.indexOf
import tv.stationbreak.feature.guide.markBandOf
import java.time.Instant
import java.time.ZoneId

private val TickMarkHeight = 6.dp
private val NowLineWidth = 1.dp
private val NowTriangle = 6.dp
private val RuleHeight = 1.dp
private val NumberWidth = 22.dp
private val NumberGap = 4.dp

// The channel tile (#803): the column's width less a 4 dp gap to the first cell (the time columns do not move), inside
// which the number and the 50 dp mark sit with 2 dp to the tile's edges.
internal val ChannelTileGap = 4.dp
private val TilePadding = 2.dp
private val TileLine = 1.dp
private val TileRadius = 2.dp

// The playing channel's marker: a 2 dp bar, 3 dp in from the tile's left, top and bottom edges.
private val MarkerWidth = 2.dp
private val MarkerInset = 3.dp
private val LabelGap = 6.dp
private val OutlineWidth = 2.dp
private val OutlineRadius = 3.dp
private const val MARK_LOOKAHEAD = 2

/**
 * The clock strip over [GUIDE_VISIBLE_ROWS] rows from [firstVisibleRow] (the vertical window the model chooses),
 * and the "now" line. Horizontally every row is laid out by time from the origin and moved by the one viewport offset
 * read from [viewportOf] while placing, so a Left/Right page recomposes no cell. Skipped on a focus move and on a
 * page: none of its inputs change.
 */
@Suppress("LongParameterList") // The model and its row window, the playing channel, the layout and three readers.
@Composable
internal fun GuideGrid(
    model: GuideModel,
    firstVisibleRow: Int,
    current: ChannelId?,
    geometry: GuideGeometry,
    focusOf: () -> GuideFocus?,
    viewportOf: () -> Long,
    nowOf: () -> Long?,
    channelFocusOf: () -> ChannelId?,
    modifier: Modifier = Modifier,
) {
    val last = minOf(firstVisibleRow + GUIDE_VISIBLE_ROWS, model.rows.size)
    // Ask for the logos of the rows in view and two more each side; request only schedules work on the cache's own
    // dispatcher, so nothing is read or decoded here.
    val marks = LocalMarkCache.current
    val markVariant = variantForTint(LocalGuideColors.current.markTint)
    LaunchedEffect(model, firstVisibleRow, marks, markVariant) {
        val from = maxOf(0, firstVisibleRow - MARK_LOOKAHEAD)
        val to = minOf(model.rows.size, last + MARK_LOOKAHEAD)
        marks?.request((from until to).mapNotNull { model.rows[it].markKey }, markVariant)
    }
    val ellipsis = rememberEllipsisMarks()
    val markText = rememberTextMeasurer()
    Box(
        modifier
            .extendRight(geometry.bleed)
            .testTag(GuideTags.GRID)
            .tracedLayout(TRACE_GRID_MEASURE, TRACE_GRID_PLACE),
    ) {
        CompositionLocalProvider(LocalEllipsisMarks provides ellipsis, LocalMarkTextMeasurer provides markText) {
            Column {
                ClockStrip(nowOf, viewportOf, geometry)
                for (index in firstVisibleRow until last) {
                    val row = model.rows[index]
                    key(row.channel) {
                        Row(Modifier.height(RowHeight)) {
                            ChannelCell(row, playing = row.channel == current, focusOf, channelFocusOf)
                            Spacer(Modifier.width(ChannelTileGap))
                            GuideRowCells(
                                row.channel,
                                row.cells,
                                model.originMs,
                                geometry,
                                focusOf,
                                viewportOf,
                                nowOf,
                                playing = row.channel == current,
                            )
                        }
                    }
                }
            }
        }
        NowLine(nowOf, viewportOf, geometry, rows = last - firstVisibleRow)
    }
}

/**
 * All the cells of one row, at their times from [originMs], clipped to the time columns. The row is moved left by the
 * viewport's distance from the origin when it is placed (the shared horizontal offset of ADR-0008), which reads
 * [viewportOf] in the layout phase only. Every cell of the row is composed, so a page brings none into composition
 * either; the model holds a few hours, a few dozen cells a row at most.
 */
@Suppress("LongParameterList") // One row's identity and cells, the origin, the layout and three readers.
@Composable
private fun GuideRowCells(
    channel: ChannelId,
    cells: List<GuideCell>,
    originMs: Long,
    geometry: GuideGeometry,
    focusOf: () -> GuideFocus?,
    viewportOf: () -> Long,
    nowOf: () -> Long?,
    playing: Boolean,
) {
    Layout(
        content = {
            for (cell in cells) {
                key(cell.startMs) {
                    val place =
                        remember(cell, originMs, geometry) { CellPlace(cell.startMs, cell.endMs, originMs, geometry) }
                    GuideCellView(channel, cell, place, focusOf, viewportOf, nowOf, playing)
                }
            }
        },
        // The surfaces run on past the viewport to the screen's edge; the cells' text stops at the viewport's.
        modifier = Modifier.width(geometry.viewportWidth + geometry.bleed).fillMaxHeight().clipToBounds(),
    ) { measurables, constraints ->
        val free = Constraints(maxHeight = constraints.maxHeight)
        val placeables = measurables.map { it.measure(free) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val shift = geometry.widthOf(viewportOf() - originMs).roundToPx()
            placeables.forEach { it.placeRelative(-shift, 0) }
        }
    }
}

/**
 * The channel cell, drawn as a tile (#803): card fill, a 1 dp line outline and a 2 dp radius, the column's width less
 * the 4 dp gap to the first cell. Inside it the number, right-aligned, then the mark in a 50 by 28 dp box. The
 * channel's name is not drawn here: the details panel has it. On the row that holds the programme focus the number is
 * in the primary colour and the outline is the key caps' (white at 28%); the channel playing is told by a primary-text
 * bar, not by an accent. While the focus is on the channel column (#631) the focused row's tile has a
 * 2 dp accent outline, inside its bounds. The channel playing also carries a 2 dp bar of primary text on the tile's
 * left edge ([TileLook.marker]), which no focus state draws, and a "playing" state for a screen reader. The outlines
 * are read in the draw phase, so a focus move redraws and recomposes nothing; only the number's colour changes with
 * the row that holds the focus, on those two cells.
 */
@Composable
private fun ChannelCell(
    row: GuideRow,
    playing: Boolean,
    focusOf: () -> GuideFocus?,
    channelFocusOf: () -> ChannelId?,
) {
    val colors = LocalGuideColors.current
    val playingLabel = stringResource(R.string.guide_playing)
    // Both read when drawing, never in composition: a move to another row recomposes no channel cell.
    val lookNow = { tileLook(focusOf()?.channel == row.channel, playing, colors) }
    Row(
        Modifier
            .width(ChannelColumnWidth - ChannelTileGap)
            .fillMaxHeight()
            .channelTile(colors.card, lookNow)
            .channelOutline(colors.accent) { channelFocusOf() == row.channel }
            .padding(horizontal = TilePadding)
            .semantics(mergeDescendants = true) { if (playing) stateDescription = playingLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = row.number.toString(),
            modifier = Modifier.width(NumberWidth),
            style = guideFigures(11.sp, FontWeight.Medium).copy(textAlign = TextAlign.End),
            color = { lookNow().number },
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(NumberGap))
        ChannelMark(row.markKey, row.initials, Modifier.testTag(GuideTags.channelMark(row.channel)))
    }
}

/**
 * Measures the content [extra] wider than the space it is given and places it at the left edge, taking only the space
 * it was given: the grid's surfaces can run on past the safe area while everything is laid out inside it.
 */
private fun Modifier.extendRight(extra: Dp): Modifier =
    layout { measurable, constraints ->
        val width = constraints.maxWidth + extra.roundToPx()
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
    }

/** A channel tile's number colour, resting outline colour and, for the channel playing, its marker bar's colour. */
internal data class TileLook(
    val number: Color,
    val line: Color,
    val marker: Color? = null,
)

/**
 * The number is primary on the playing channel and on the row that holds the programme focus ([rowFocused]), else
 * secondary; the playing channel alone has a [TileLook.marker] (primary text: the accent is for focus, now and
 * progress only), so it is told from a focused row by more than the number; the outline is the quiet line, or the
 * key caps' stroke (white at 28% in Dark, the matching token in Light) on the focused row. The channel-focus
 * accent outline is drawn over this, on its own.
 */
internal fun tileLook(
    rowFocused: Boolean,
    playing: Boolean,
    colors: GuideColors,
): TileLook =
    TileLook(
        number = if (playing || rowFocused) colors.textPrimary else colors.textSecondary,
        line = if (rowFocused) colors.keyCapStroke else colors.line,
        marker = if (playing) colors.textPrimary else null,
    )

/**
 * The tile behind a channel cell (#803): [fill] with a [TileLine] outline of the colour [lookOf] says (read when
 * drawing, so a focus move redraws it and recomposes nothing), a [TileRadius] radius, [CellInset] from the rows above
 * and below as the cells are from each other, and the [TileLook.marker] bar inside the left edge when there is one.
 */
private fun Modifier.channelTile(
    fill: Color,
    lookOf: () -> TileLook,
): Modifier =
    drawBehind {
        val inset = CellInset.toPx()
        val line = TileLine.toPx()
        val corner = CornerRadius(TileRadius.toPx())
        val height = size.height - 2 * inset
        drawRoundRect(fill, Offset(0f, inset), Size(size.width, height), corner)
        val look = lookOf()
        drawRoundRect(
            look.line,
            topLeft = Offset(line / 2, inset + line / 2),
            size = Size(size.width - line, height - line),
            cornerRadius = corner,
            style = Stroke(line),
        )
        look.marker?.let {
            val gap = MarkerInset.toPx()
            drawRoundRect(
                it,
                topLeft = Offset(gap, inset + gap),
                size = Size(MarkerWidth.toPx(), height - 2 * gap),
                cornerRadius = CornerRadius(MarkerWidth.toPx() / 2),
            )
        }
    }

/** A [OutlineWidth] outline of [color] inside the bounds, drawn only while [focused] says so (read when drawing). */
private fun Modifier.channelOutline(
    color: Color,
    focused: () -> Boolean,
): Modifier =
    drawWithContent {
        drawContent()
        if (focused()) {
            val half = OutlineWidth.toPx() / 2
            drawRoundRect(
                color,
                topLeft = Offset(half, half),
                size = Size(size.width - 2 * half, size.height - 2 * half),
                cornerRadius = CornerRadius(OutlineRadius.toPx()),
                style = Stroke(OutlineWidth.toPx()),
            )
        }
    }

/**
 * The clock ("MON 8:12 PM") over the channel column, and a tick with its time every half hour over the time columns,
 * with a 1 dp rule along the strip's foot. A tick gets its weekday where the guide crosses into another day than the
 * one before it.
 */
@Composable
private fun ClockStrip(
    nowOf: () -> Long?,
    viewportOf: () -> Long,
    geometry: GuideGeometry,
) {
    // Read here, so a page or the minute recomposes the strip and nothing else.
    val viewportStartMs = viewportOf()
    val nowMs = nowOf() ?: viewportStartMs
    val zone = remember { ZoneId.systemDefault() }
    val is24h = is24Hour()
    val colors = LocalGuideColors.current
    val clock = guideFigures(11.sp, FontWeight.Medium)
    val time = guideFigures(11.sp, FontWeight.Normal)
    Row(
        Modifier
            .height(ClockHeight)
            .testTag(GuideTags.CLOCK)
            .drawBehind {
                val rule = RuleHeight.toPx()
                // The rule runs on to both edges of the screen, past the safe area the labels keep to.
                val bleed = geometry.bleed.toPx()
                drawRect(colors.line, Offset(-bleed, size.height - rule), Size(size.width + 2 * bleed, rule))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ProgramText.dayTime(nowMs, zone, is24h).uppercase(),
            modifier = Modifier.width(ChannelColumnWidth),
            style = clock,
            color = colors.textSecondary,
            maxLines = 1,
        )
        Box(Modifier.width(geometry.viewportWidth).fillMaxHeight()) {
            var dayBefore = dayOf(nowMs, zone)
            var tick = viewportStartMs
            while (tick < viewportStartMs + geometry.spanMs) {
                val day = dayOf(tick, zone)
                val label =
                    if (day == dayBefore) {
                        ProgramText.time(tick, zone, is24h)
                    } else {
                        ProgramText.dayTime(tick, zone, is24h)
                    }
                dayBefore = day
                val x = geometry.widthOf(tick - viewportStartMs)
                Box(
                    Modifier
                        .offset(x = x)
                        .align(Alignment.BottomStart)
                        .padding(bottom = RuleHeight)
                        .size(1.dp, TickMarkHeight)
                        .background(colors.line),
                )
                Text(
                    text = label,
                    modifier = Modifier.offset(x = x + LabelGap).align(Alignment.CenterStart),
                    style = time,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
                tick += SLOT_MS
            }
        }
    }
}

/**
 * Where [row] sits in the window that starts at [firstVisibleRow], or null when it is outside that window. The last
 * window can be shorter than [GUIDE_VISIBLE_ROWS], and an empty band below the last row is not a row.
 */
internal fun focusedRowBand(
    row: Int,
    firstVisibleRow: Int,
    rowCount: Int,
): Int? {
    val shown = minOf(GUIDE_VISIBLE_ROWS, rowCount - firstVisibleRow)
    val band = row - firstVisibleRow
    return band.takeIf { it in 0 until shown }
}

private fun dayOf(
    ms: Long,
    zone: ZoneId,
) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

/**
 * The now-line: a 6 dp accent triangle on the clock strip's rule, and a 1 dp line through the whole grid at 35% that
 * may cross cells (a program's own progress line shows how far along it is). The time and the viewport are read in its
 * offset (layout phase) and its drawing, so the minute and a page move it without composing the grid.
 */
@Composable
private fun NowLine(
    nowOf: () -> Long?,
    viewportOf: () -> Long,
    geometry: GuideGeometry,
    rows: Int,
) {
    fun from(): Long? = nowOf()?.let { it - viewportOf() }?.takeIf { it >= 0 && it < geometry.spanMs }
    val colors = LocalGuideColors.current
    Box(
        Modifier
            .offset {
                val x = ChannelColumnWidth + geometry.widthOf(from() ?: 0L) - NowTriangle / 2
                IntOffset(x.roundToPx(), 0)
            }.size(NowTriangle, ClockHeight + RowHeight * rows)
            .testTag(GuideTags.NOW_LINE)
            .drawWithCache {
                val clock = ClockHeight.toPx()
                val triangle = NowTriangle.toPx()
                val line = NowLineWidth.toPx()
                // Built once per size and density, not on every draw.
                val marker =
                    Path().apply {
                        moveTo(0f, clock - triangle)
                        lineTo(triangle, clock - triangle)
                        lineTo(triangle / 2, clock)
                        close()
                    }
                onDrawBehind {
                    if (from() == null) return@onDrawBehind
                    drawRect(
                        colors.nowLine.copy(alpha = colors.nowRailAlpha),
                        Offset((size.width - line) / 2, clock),
                        Size(line, size.height - clock),
                    )
                    drawPath(marker, colors.nowLine)
                }
            },
    )
}
