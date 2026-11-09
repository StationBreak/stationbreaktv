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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.guide.GUIDE_VISIBLE_ROWS
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.GuideFocus
import tv.stationbreak.feature.guide.GuideModel
import tv.stationbreak.feature.guide.GuideRow
import tv.stationbreak.feature.guide.R
import tv.stationbreak.feature.guide.SLOT_MS
import tv.stationbreak.feature.guide.indexOf
import tv.stationbreak.feature.guide.markBandOf
import tv.stationbreak.feature.guide.ui.mark.ChannelMark
import tv.stationbreak.feature.guide.ui.mark.LocalMarkCache
import tv.stationbreak.feature.guide.ui.mark.LocalMarkTextMeasurer
import tv.stationbreak.feature.guide.ui.mark.variantForTint
import java.time.Instant
import java.time.ZoneId

private val TickMarkHeight = 6.dp
private val NowLineWidth = 1.dp
private val NowTriangle = 6.dp
private val RuleHeight = 1.dp
private val NumberWidth = 22.dp
private val NumberGap = 8.dp
private val ColumnEnd = 4.dp
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
                            ChannelCell(row, playing = row.channel == current, channelFocusOf)
                            GuideRowCells(
                                row.channel,
                                row.cells,
                                model.originMs,
                                geometry,
                                focusOf,
                                viewportOf,
                                nowOf,
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
) {
    Layout(
        content = {
            for (cell in cells) {
                key(cell.startMs) {
                    val place =
                        remember(cell, originMs, geometry) { CellPlace(cell.startMs, cell.endMs, originMs, geometry) }
                    GuideCellView(channel, cell, place, focusOf, viewportOf, nowOf)
                }
            }
        },
        modifier = Modifier.width(geometry.viewportWidth).fillMaxHeight().clipToBounds(),
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
 * The channel column: the number, right-aligned, then the mark in a 50 by 28 dp box. The channel's name is not drawn
 * here: the details panel has it. The channel playing is told by its number in the primary colour, not by an accent.
 * Nothing is drawn behind it. While the focus is on the channel column (#631) the focused row's cell has a 2 dp accent
 * outline, inside its bounds; that is read in the draw phase only, so a focus move does not recompose this cell.
 */
@Composable
private fun ChannelCell(
    row: GuideRow,
    playing: Boolean,
    channelFocusOf: () -> ChannelId?,
) {
    val colors = LocalGuideColors.current
    Row(
        Modifier
            .width(ChannelColumnWidth)
            .fillMaxHeight()
            .channelOutline(colors.accent) { channelFocusOf() == row.channel }
            .padding(end = ColumnEnd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.number.toString(),
            modifier = Modifier.width(NumberWidth),
            style = guideFigures(11.sp, FontWeight.Medium),
            color = if (playing) colors.textPrimary else colors.textSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(NumberGap))
        ChannelMark(row.markKey, row.initials, Modifier.testTag(GuideTags.channelMark(row.channel)))
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
                drawRect(colors.line, Offset(0f, size.height - rule), Size(size.width, rule))
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
            .drawBehind {
                if (from() == null) return@drawBehind
                val clock = ClockHeight.toPx()
                val triangle = NowTriangle.toPx()
                val line = NowLineWidth.toPx()
                drawRect(
                    colors.nowLine.copy(alpha = colors.nowRailAlpha),
                    Offset((size.width - line) / 2, clock),
                    Size(line, size.height - clock),
                )
                val marker =
                    Path().apply {
                        moveTo(0f, clock - triangle)
                        lineTo(triangle, clock - triangle)
                        lineTo(triangle / 2, clock)
                        close()
                    }
                drawPath(marker, colors.nowLine)
            },
    )
}
