// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
 * One cell, cut to the visible range from [viewportStartMs], so a program that began before it is titled from the
 * visible left edge. It reads only whether it is the focused cell (derived from [focusOf] by identity: channel and
 * start), so a focus move recomposes the cell it left and the one it reached, and no other. Fill, focus ring, slate
 * dashes, chevrons and the compact tick are drawn, not composed; nothing animates and nothing scales.
 */
@Suppress("LongParameterList", "LongMethod") // One cell's identity, range and layout; its parts are plain draw calls.
@Composable
internal fun GuideCellView(
    channel: ChannelId,
    cell: GuideCell,
    viewportStartMs: Long,
    originMs: Long,
    geometry: GuideGeometry,
    focusOf: () -> GuideFocus?,
) {
    val focused by remember(channel, cell.startMs, focusOf) {
        derivedStateOf { focusOf()?.let { it.channel == channel && it.cellStartMs == cell.startMs } == true }
    }
    LocalCellComposed.current?.invoke(channel, cell.startMs)
    val viewportEnd = viewportStartMs + geometry.spanMs
    val from = maxOf(cell.startMs, viewportStartMs)
    val to = minOf(cell.endMs, viewportEnd)
    val width = maxOf(geometry.widthOf(to - from), MinCellWidth)
    val x = minOf(geometry.widthOf(from - viewportStartMs), geometry.viewportWidth - width)
    val compact = width < CompactBelow
    // "‹" only where earlier time can be scrolled to: at the first half hour the past is not on the grid.
    val earlier = cell.startMs < viewportStartMs && viewportStartMs > originMs
    val later = cell.endMs > viewportEnd
    val copy = programCopy()
    val title = cellTitle(cell, copy)
    val description = cellDescription(cell, title, earlier, later)
    Box(
        Modifier
            .offset(x = x)
            .width(width)
            .fillMaxHeight()
            .padding(CellInset)
            .cellLook(cell.kind == CellKind.Slate, compact, earlier, later) { focused }
            .semantics {
                selected = focused
                contentDescription = description
            }.testTag(GuideTags.cell(channel, cell.startMs)),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (!compact && title.isNotEmpty()) {
            val tag = Modifier.testTag(GuideTags.cellText(channel, cell.startMs))
            CellText(cell, title, width, focused, tag, earlier, later)
        }
    }
    if (compact && focused && title.isNotEmpty()) CompactLabel(title, x, width, geometry.viewportWidth)
}

/** The cell's title (and "S2 E5" when it is 20 minutes or more wide), or a slate's "Off air until". */
@Suppress("LongParameterList") // The cell, its words, its width and where its chevrons leave room.
@Composable
private fun CellText(
    cell: GuideCell,
    title: String,
    width: Dp,
    focused: Boolean,
    modifier: Modifier,
    earlier: Boolean,
    later: Boolean,
) {
    val slate = cell.kind == CellKind.Slate
    val main =
        if (focused) {
            BroadcastColors.Ink
        } else if (slate) {
            BroadcastColors.Ground
        } else {
            BroadcastColors.Cream
        }
    val minor = if (focused) BroadcastColors.Ink else BroadcastColors.Ground
    val numbers = cell.info?.takeIf { it.kind == ScheduleItemKind.Episode && width >= EpisodeFrom }?.let(::numbersOf)
    val text =
        buildAnnotatedString {
            withStyle(SpanStyle(color = main)) { append(title) }
            numbers?.let { withStyle(SpanStyle(color = minor, fontWeight = FontWeight.Normal)) { append("  $it") } }
        }
    Text(
        text = text,
        modifier =
            modifier.padding(
                start = TextStart + if (earlier) ChevronRoom else 0.dp,
                end = if (later) ChevronRoom else 4.dp,
            ),
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
 * The title of a focused cell too narrow to hold it: one line, Ink on Accent, floating beside the cell (to its right,
 * or to its left when there is no room) above its neighbours. It takes no space in the row.
 */
@Composable
private fun CompactLabel(
    title: String,
    cellX: Dp,
    cellWidth: Dp,
    viewportWidth: Dp,
) {
    Text(
        text = title,
        modifier =
            Modifier
                .zIndex(1f)
                .layout { measurable, constraints ->
                    val label = measurable.measure(Constraints(maxWidth = viewportWidth.roundToPx()))
                    layout(0, 0) {
                        val right = (cellX + cellWidth + LabelGap).roundToPx()
                        val x =
                            if (right + label.width <= viewportWidth.roundToPx()) {
                                right
                            } else {
                                maxOf(0, (cellX - LabelGap).roundToPx() - label.width)
                            }
                        label.placeRelative(x, (constraints.maxHeight - label.height) / 2)
                    }
                }.background(BroadcastColors.Accent)
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .testTag(GuideTags.COMPACT_LABEL),
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        color = BroadcastColors.Ink,
        maxLines = 1,
        softWrap = false,
    )
}

/** "Title, 8:30 – 9:00 PM", and whether it continues past the visible times: what a screen reader says. */
@Composable
private fun cellDescription(
    cell: GuideCell,
    title: String,
    earlier: Boolean,
    later: Boolean,
): String {
    val slot = ProgramText.slot(cell.startMs, cell.endMs, ZoneId.systemDefault(), is24Hour())
    val parts =
        listOfNotNull(
            stringResource(R.string.guide_cell_description, title, slot),
            if (earlier) stringResource(R.string.guide_continues_earlier) else null,
            if (later) stringResource(R.string.guide_continues_later) else null,
        )
    return parts.joinToString(", ")
}

/**
 * The cell's look, drawn: Cream at 8% (a slate: a dashed Ground border instead), or when [focused] Accent with a 3 dp
 * White ring inside the bounds, so the row's clip can never cut it. Chevrons mark a program running on past either
 * edge; a compact cell has a small tick in its centre. [focused] is read while drawing.
 */
private fun Modifier.cellLook(
    slate: Boolean,
    compact: Boolean,
    earlier: Boolean,
    later: Boolean,
    focused: () -> Boolean,
): Modifier =
    drawWithCache {
        val ring = RingWidth.toPx()
        val dashWidth = 1.5.dp.toPx()
        val dash =
            Stroke(
                width = dashWidth,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        val chevron = Stroke(width = 2.dp.toPx())
        val inset = ChevronInset.toPx()
        val half = ChevronSize.toPx()
        val middle = size.height / 2
        val left =
            Path().apply {
                moveTo(inset + half, middle - half)
                lineTo(inset, middle)
                lineTo(inset + half, middle + half)
            }
        val right =
            Path().apply {
                moveTo(size.width - inset - half, middle - half)
                lineTo(size.width - inset, middle)
                lineTo(size.width - inset - half, middle + half)
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
            if (compact && !isFocused) {
                val tick = CompactTick.toPx()
                val tall = size.height * COMPACT_TICK_FRACTION
                drawRect(BroadcastColors.Ground, Offset(size.width / 2 - tick / 2, middle - tall / 2), Size(tick, tall))
            }
            val mark = if (isFocused) BroadcastColors.Ink else BroadcastColors.Cream
            if (earlier && !compact) drawPath(left, mark, style = chevron)
            if (later && !compact) drawPath(right, mark, style = chevron)
        }
    }
