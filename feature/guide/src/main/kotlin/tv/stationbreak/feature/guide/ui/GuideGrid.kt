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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * The clock strip over [GUIDE_VISIBLE_ROWS] rows from [firstVisibleRow], the time columns from [viewportStartMs], and
 * the "now" line at [nowMs]. Skipped on a focus move: none of its inputs change, only the snapshot that holds them.
 */
@Suppress("LongParameterList") // The parts of the Ready state the grid draws, the playing channel and the layout.
@Composable
internal fun GuideGrid(
    model: GuideModel,
    viewportStartMs: Long,
    firstVisibleRow: Int,
    nowMs: Long,
    current: ChannelId?,
    geometry: GuideGeometry,
    focusOf: () -> GuideFocus?,
    modifier: Modifier = Modifier,
) {
    val last = minOf(firstVisibleRow + GUIDE_VISIBLE_ROWS, model.rows.size)
    Box(modifier.testTag(GuideTags.GRID)) {
        Column {
            ClockStrip(nowMs, viewportStartMs, geometry)
            for (index in firstVisibleRow until last) {
                val row = model.rows[index]
                key(row.channel) {
                    Row(Modifier.height(RowHeight)) {
                        ChannelCell(row.number, row.name, playing = row.channel == current)
                        GuideRowCells(row.channel, row.cells, viewportStartMs, model.originMs, geometry, focusOf)
                    }
                }
            }
        }
        NowLine(nowMs, viewportStartMs, geometry, rows = last - firstVisibleRow)
    }
}

/** The cells of one row that show in the time range from [viewportStartMs]. */
@Suppress("LongParameterList") // One row's identity and cells, the visible range and the layout.
@Composable
private fun GuideRowCells(
    channel: ChannelId,
    cells: List<GuideCell>,
    viewportStartMs: Long,
    originMs: Long,
    geometry: GuideGeometry,
    focusOf: () -> GuideFocus?,
) {
    val end = viewportStartMs + geometry.spanMs
    Box(Modifier.width(geometry.viewportWidth).fillMaxHeight()) {
        for (cell in cells) {
            if (cell.endMs > viewportStartMs && cell.startMs < end) {
                key(cell.startMs) { GuideCellView(channel, cell, viewportStartMs, originMs, geometry, focusOf) }
            }
        }
    }
}

/**
 * The channel column: the number in Accent, then the name. A bar at the left edge marks the channel playing; there is
 * no focused-row highlight, which would recompose a whole row on every move.
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
    nowMs: Long,
    viewportStartMs: Long,
    geometry: GuideGeometry,
) {
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
 * The "now" line: 2 dp of Cream down the grid with an Accent mark in the clock strip, one for the whole grid and only
 * while now is in the visible time range. Its place is read in the layout phase.
 */
@Composable
private fun NowLine(
    nowMs: Long,
    viewportStartMs: Long,
    geometry: GuideGeometry,
    rows: Int,
) {
    val from = nowMs - viewportStartMs
    if (from < 0 || from >= geometry.spanMs) return
    val x = ChannelColumnWidth + geometry.widthOf(from) - NowMarkWidth / 2
    Box(
        Modifier
            .offset { IntOffset(x.roundToPx(), 0) }
            .size(NowMarkWidth, ClockHeight + RowHeight * rows)
            .testTag(GuideTags.NOW_LINE)
            .drawBehind {
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
