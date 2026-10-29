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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.guide.GUIDE_VISIBLE_ROWS
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.GuideFocus
import tv.stationbreak.feature.guide.GuideModel
import tv.stationbreak.feature.guide.SLOT_MS
import java.time.Instant
import java.time.ZoneId

private val TickMarkHeight = 8.dp
private val NowLineWidth = 2.dp
private val NowMarkWidth = 10.dp
private val PlayingBarWidth = 4.dp
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
    Box(modifier.testTag(GuideTags.GRID).tracedLayout(TRACE_GRID_MEASURE, TRACE_GRID_PLACE)) {
        CompositionLocalProvider(LocalEllipsisMarks provides ellipsis) {
            Column {
                ClockStrip(nowOf, viewportOf, geometry)
                for (index in firstVisibleRow until last) {
                    val row = model.rows[index]
                    key(row.channel) {
                        Row(Modifier.height(RowHeight)) {
                            ChannelCell(row.number, row.name, playing = row.channel == current)
                            GuideRowCells(row.channel, row.cells, model.originMs, geometry, focusOf, viewportOf, nowOf)
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
 * The channel column: the number in Accent, then the name, each cut with an ellipsis when it does not fit. A bar at
 * the left edge marks the channel playing; there is no focused-row highlight, which would recompose a whole row on
 * every move.
 */
@Composable
private fun ChannelCell(
    number: Int,
    name: String,
    playing: Boolean,
) {
    Row(
        Modifier
            .width(ChannelColumnWidth)
            .fillMaxHeight()
            .drawBehind {
                if (playing) {
                    val inset = 1.dp.toPx()
                    drawRect(
                        BroadcastColors.Accent,
                        Offset(0f, inset),
                        Size(PlayingBarWidth.toPx(), size.height - 2 * inset),
                    )
                }
            }.padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number.toString(),
            modifier = Modifier.width(NumberWidth),
            style = mono(16.sp),
            color = BroadcastColors.Accent,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = name,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            color = BroadcastColors.Cream,
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
    Row(Modifier.height(ClockHeight).testTag(GuideTags.CLOCK), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = ProgramText.dayTime(nowMs, zone, is24h),
            modifier = Modifier.width(ChannelColumnWidth).padding(start = 10.dp),
            style = mono(16.sp),
            color = BroadcastColors.Cream,
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
                        .size(1.dp, TickMarkHeight)
                        .background(BroadcastColors.Ground),
                )
                Text(
                    text = label,
                    modifier = Modifier.offset(x = x + LabelGap).align(Alignment.CenterStart),
                    style = mono(16.sp),
                    color = BroadcastColors.Ground,
                    maxLines = 1,
                )
                tick += SLOT_MS
            }
        }
    }
}

private fun dayOf(
    ms: Long,
    zone: ZoneId,
) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

/**
 * The "now" line: 2 dp of Cream down the grid with an Accent mark in the clock strip, one for the whole grid. The time
 * and the viewport are read in its offset (layout phase) and its drawing, so the minute and a page move it without
 * composing it; it is not drawn while now is outside the visible time range.
 */
@Composable
private fun NowLine(
    nowOf: () -> Long?,
    viewportOf: () -> Long,
    geometry: GuideGeometry,
    rows: Int,
) {
    fun from(): Long? = nowOf()?.let { it - viewportOf() }?.takeIf { it >= 0 && it < geometry.spanMs }
    Box(
        Modifier
            .offset {
                val x = ChannelColumnWidth + geometry.widthOf(from() ?: 0L) - NowMarkWidth / 2
                IntOffset(x.roundToPx(), 0)
            }.size(NowMarkWidth, ClockHeight + RowHeight * rows)
            .testTag(GuideTags.NOW_LINE)
            .drawBehind {
                if (from() == null) return@drawBehind
                val centre = size.width / 2
                val line = NowLineWidth.toPx()
                drawRect(BroadcastColors.Cream, Offset(centre - line / 2, 0f), Size(line, size.height))
                val mark = size.width
                val top = ClockHeight.toPx() - mark / 2
                val triangle =
                    Path().apply {
                        moveTo(0f, top)
                        lineTo(mark, top)
                        lineTo(centre, top + mark / 2)
                        close()
                    }
                drawPath(triangle, BroadcastColors.Accent)
            },
    )
}
