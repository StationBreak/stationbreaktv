// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import tv.stationbreak.feature.guide.R
import tv.stationbreak.feature.guide.SLOT_MS
import tv.stationbreak.feature.guide.indexOf
import java.time.Instant
import java.time.ZoneId

private val TickMarkHeight = 8.dp
private val NowLineWidth = 1.5.dp
/** The corner radius of the panel behind the clock strip and rows. */
internal val GridPanelRadius = 18.dp

/** How far a row band is drawn in from the panel's left and right edge. */
internal val GridPanelPadding = 6.dp

private val NowChipHeight = 21.dp
private val NowChipGap = 4.dp

/** Room to the left of the rail for the timeline NOW chip, so it does not cover a time label. */
private val NowChipLead = 56.dp
private val TimelineLabelWidth = 96.dp
private val PlayingBarWidth = 3.dp
private val FocusTickWidth = 3.dp
private val FocusTickHeight = 16.dp
private val NumberWidth = 36.dp
private val LabelGap = 6.dp

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
    modifier: Modifier = Modifier,
) {
    val last = minOf(firstVisibleRow + GUIDE_VISIBLE_ROWS, model.rows.size)
    val ellipsis = rememberEllipsisMarks()
    val colors = LocalGuideColors.current
    Box(
        modifier
            .testTag(GuideTags.GRID)
            .gridPanel(firstVisibleRow, last - firstVisibleRow, colors)
            .focusedRowTint(model, firstVisibleRow, focusOf, colors)
            .tracedLayout(TRACE_GRID_MEASURE, TRACE_GRID_PLACE),
    ) {
        CompositionLocalProvider(LocalEllipsisMarks provides ellipsis) {
            Column {
                ClockStrip(nowOf, viewportOf, geometry)
                for (index in firstVisibleRow until last) {
                    val row = model.rows[index]
                    key(row.channel) {
                        Row(Modifier.height(RowHeight)) {
                            ChannelCell(row.number, row.name, playing = row.channel == current)
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
 * The channel column: the number, then the name, each cut with an ellipsis when it does not fit. A bar at the left
 * edge marks the channel playing. The row wash and the orange tick are drawn on the grid, so a focus move does not
 * recompose this cell.
 */
@Composable
private fun ChannelCell(
    number: Int,
    name: String,
    playing: Boolean,
) {
    val colors = LocalGuideColors.current
    Row(
        Modifier
            .width(ChannelColumnWidth)
            .fillMaxHeight()
            .drawBehind {
                if (playing) {
                    val inset = (1.dp + GuideCardInset).toPx()
                    drawRoundRect(
                        colors.accent,
                        Offset(0f, inset),
                        Size(PlayingBarWidth.toPx(), size.height - 2 * inset),
                        CornerRadius(PlayingBarWidth.toPx() / 2f),
                    )
                }
            }.padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number.toString(),
            modifier = Modifier.width(NumberWidth),
            style = guideFigures(17.sp, FontWeight.SemiBold),
            color = colors.textSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = name,
            style = guideText(18.sp, FontWeight.Medium),
            color = colors.channelText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The clock ("Mon 8:12 PM") over the channel column, and a tick with its time every half hour over the time columns.
 * A tick gets its weekday where the guide crosses into another day than the one before it.
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
    val time = guideFigures(17.sp, FontWeight.Medium)
    Row(Modifier.height(ClockHeight).testTag(GuideTags.CLOCK), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = ProgramText.dayTime(nowMs, zone, is24h),
            modifier = Modifier.width(ChannelColumnWidth).padding(start = 10.dp),
            style = time,
            color = colors.textSecondary,
            maxLines = 1,
        )
        Box(Modifier.width(geometry.viewportWidth).fillMaxHeight()) {
            val nowInView = nowMs in viewportStartMs until viewportStartMs + geometry.spanMs
            val nowX = geometry.widthOf(nowMs - viewportStartMs)
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
                val labelLeft = x + LabelGap
                val underChip =
                    nowInView && labelLeft < nowX && labelLeft + TimelineLabelWidth > nowX - NowChipLead
                Box(
                    Modifier
                        .offset(x = x)
                        .align(Alignment.BottomStart)
                        .size(1.dp, TickMarkHeight)
                        .background(colors.textSecondary),
                )
                if (!underChip) {
                    Text(
                        text = label,
                        modifier = Modifier.offset(x = labelLeft).align(Alignment.CenterStart),
                        style = time,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
                tick += SLOT_MS
            }
        }
    }
}

/**
 * Whether the row at [rowIndex] of the model carries a band. By model index, not by place in the window, so a vertical
 * scroll does not make a row swap between band and no band.
 */
internal fun bandOf(rowIndex: Int): Boolean = rowIndex % 2 == 1

/**
 * The rounded panel over the whole grid body, and a band under each odd row of the window. Drawn behind everything
 * else, in the draw phase, from two colours and no allocation per frame; the bands are inset by [GridPanelPadding] at
 * both sides. The content itself is not padded: the body's height and the rows' width are fixed by the geometry.
 */
private fun Modifier.gridPanel(
    firstVisibleRow: Int,
    shown: Int,
    colors: GuideColors,
): Modifier =
    drawBehind {
        drawRoundRect(colors.gridPanel, Offset.Zero, size, CornerRadius(GridPanelRadius.toPx()))
        val inset = GridPanelPadding.toPx()
        val radius = CornerRadius((GuideCardRadius + 2.dp).toPx())
        val row = RowHeight.toPx()
        for (place in 0 until shown) {
            if (!bandOf(firstVisibleRow + place)) continue
            drawRoundRect(
                colors.rowBand,
                Offset(inset, ClockHeight.toPx() + row * place),
                Size(size.width - 2 * inset, row),
                radius,
            )
        }
    }

/**
 * The focused-row wash, and a short accent tick at its left. [focusOf] is read here, in the draw phase, so a focus
 * move redraws this and recomposes no programme cell. Nothing is drawn while the focus is on the menu (that reader
 * returns null) or on a row that is not in the window.
 */
private fun Modifier.focusedRowTint(
    model: GuideModel,
    firstVisibleRow: Int,
    focusOf: () -> GuideFocus?,
    colors: GuideColors,
): Modifier =
    drawBehind {
        val focus = focusOf() ?: return@drawBehind
        val row = model.indexOf(focus)?.first ?: return@drawBehind
        val band = focusedRowBand(row, firstVisibleRow, model.rows.size) ?: return@drawBehind
        val top = ClockHeight.toPx() + RowHeight.toPx() * band
        val height = RowHeight.toPx()
        drawRoundRect(
            colors.focusedRowWash,
            Offset(0f, top),
            Size(size.width, height),
            CornerRadius(GuideCardRadius.toPx()),
        )
        val tickW = FocusTickWidth.toPx()
        val tickH = FocusTickHeight.toPx()
        drawRoundRect(
            colors.accent,
            Offset(2.dp.toPx(), top + (height - tickH) / 2f),
            Size(tickW, tickH),
            CornerRadius(tickW / 2f),
        )
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
 * The now rail: a NOW chip in the clock strip and a line through the gaps between cards, not through their titles.
 * The time and the viewport are read in its offset (layout phase) and its drawing, so the minute and a page move it
 * without composing the grid. The chip is composed only while now is inside the visible time range.
 */
@Composable
private fun NowLine(
    nowOf: () -> Long?,
    viewportOf: () -> Long,
    geometry: GuideGeometry,
    rows: Int,
) {
    fun from(): Long? = nowOf()?.let { it - viewportOf() }?.takeIf { it >= 0 && it < geometry.spanMs }
    val shown by remember(nowOf, viewportOf, geometry) { derivedStateOf { from() != null } }
    val colors = LocalGuideColors.current
    // The chip sits to the left of the rail. Time labels start to the right of their tick, so a chip centred on the
    // rail covers the label when now falls on one.
    val lead = NowChipLead
    Box(
        Modifier
            .offset {
                val x = ChannelColumnWidth + geometry.widthOf(from() ?: 0L) - lead
                IntOffset(x.roundToPx(), 0)
            }.size(lead + NowChipGap, ClockHeight + RowHeight * rows)
            .testTag(GuideTags.NOW_LINE)
            .drawBehind {
                if (from() == null) return@drawBehind
                val at = lead.toPx()
                val line = NowLineWidth.toPx()
                val rail = colors.nowLine.copy(alpha = colors.nowRailAlpha)
                val gutter = (1.dp + GuideCardInset).toPx()
                val clock = ClockHeight.toPx()
                val row = RowHeight.toPx()
                drawRect(rail, Offset(at - line / 2, 0f), Size(line, clock))
                repeat(rows) { index ->
                    val top = clock + row * index
                    drawRect(rail, Offset(at - line / 2, top), Size(line, gutter))
                    drawRect(rail, Offset(at - line / 2, top + row - gutter), Size(line, gutter))
                }
            },
    ) {
        if (shown) {
            Text(
                text = stringResource(R.string.guide_now).uppercase(),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = NowChipGap)
                        .height(NowChipHeight)
                        .clip(RoundedCornerShape(NowChipHeight / 2))
                        .background(colors.nowChipFill)
                        .padding(horizontal = 8.dp),
                style = guideText(16.sp, FontWeight.Bold),
                color = colors.nowChipText,
                maxLines = 1,
            )
        }
    }
}
