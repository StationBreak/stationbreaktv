// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.guide.CellDetails
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.R
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

private val ChipPadding = 8.dp
private val ChipRadius = (20f / 3f).dp
private val ProgressHeight = (4f / 1.5f).dp
private val ProgressRadius = (2f / 1.5f).dp
private val LineGap = 4.dp

/** The title's letter-spacing, in em: -0.015. */
private const val TITLE_TRACKING = -0.015f

// One line fewer than the band would hold: the Channels button takes the foot of it, and a text that ends in an
// ellipsis reads better than one clipped mid-line.
private const val OVERVIEW_LINES_WITH_EPISODE = 1
private const val OVERVIEW_LINES = 2
private val OVERVIEW_LINE_HEIGHT = 24.sp
private const val DESCRIBE_AFTER_MS = 150L

/**
 * A node of its own, never merged into the panel's live region: for what changes every minute (the countdown, the
 * progress bar), which a screen reader should not read out again each time. It can still be read on its own.
 */
private val OutsideTheLiveRegion = Modifier.semantics(mergeDescendants = true) {}

/**
 * The details of the focused cell: the channel, the title, the episode line, when it airs (with a NOW chip and the
 * time left for a program on now, or how soon it starts), its progress when on now, and the overview once fetched and
 * the focus has rested on the cell ([restedOn]). A block of its own, so something else (a menu) can take its place
 * later. Only it reads [panel]; the clock is read through [nowOf], which changes once a minute. Read out by a screen
 * reader as it changes (a polite live region: the channel, the title, the episode and the slot, not the minute
 * countdown), since no screen reader can see the guide's own focus.
 */
@Composable
internal fun DetailsPanel(
    panel: StateFlow<CellDetails?>,
    nowOf: () -> Long?,
    modifier: Modifier,
) {
    val details by panel.collectAsStateWithLifecycle()
    val now by remember(nowOf) { derivedStateOf(nowOf) }
    PanelColumn(
        modifier
            .clipToBounds()
            .testTag(GuideTags.PANEL)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        val shown = details
        if (shown != null) {
            val rested = restedOn(shown.channelNumber to shown.cell.startMs)
            PanelLines(shown, now ?: shown.cell.startMs, describe = rested)
        }
    }
}

/**
 * Whether the focus has rested on [cell] for [DESCRIBE_AFTER_MS]: until then the panel leaves the description out. A
 * held key moves the focus 20 times a second, too fast to read a description, and laying a few lines of it out on every
 * step costs frames on the older TV; the title and the time still follow every step. Details that arrive for a cell
 * the focus has rested on show at once.
 */
@Composable
private fun restedOn(cell: Pair<Int, Long>): Boolean {
    var rested by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(cell) {
        delay(DESCRIBE_AFTER_MS.milliseconds)
        rested = cell
    }
    return rested == cell
}

private enum class PanelSlot { Channel, Title, Episode, Meta, Time, Progress, Overview }

/** What drops first when the band is short: the last of these goes first, whole, never clipped through its glyphs. */
private val DropOrder =
    listOf(
        PanelSlot.Channel,
        PanelSlot.Title,
        PanelSlot.Time,
        PanelSlot.Progress,
        PanelSlot.Episode,
        PanelSlot.Meta,
    )

@Composable
private fun Slot(
    slot: PanelSlot,
    content: @Composable () -> Unit,
) = Box(Modifier.layoutId(slot)) { content() }

/**
 * A column of the [PanelSlot] lines in their own order, [LineGap] apart, that keeps what fits of them in the order of
 * [DropOrder] and leaves out the rest whole. The overview takes the height that is left, and is drawn by [Overview]
 * only when a whole line of it fits. A line that does not fit is not placed, so the panel's clip never cuts a glyph.
 */
@Composable
private fun PanelColumn(
    modifier: Modifier,
    content: @Composable () -> Unit,
) = Layout(content, modifier) { measurables, constraints ->
    val gap = LineGap.roundToPx()
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val bySlot = measurables.associateBy { it.layoutId as PanelSlot }
    val measured = DropOrder.mapNotNull { slot -> bySlot[slot]?.let { slot to it.measure(loose) } }.toMap()
    var used = 0
    var count = 0
    val kept = mutableSetOf<PanelSlot>()
    for ((slot, placeable) in measured) {
        val next = used + placeable.height + if (count > 0) gap else 0
        if (next <= constraints.maxHeight) {
            used = next
            count++
            kept += slot
        }
    }
    val left = constraints.maxHeight - used - gap
    val overview =
        bySlot[PanelSlot.Overview]?.takeIf { left > 0 }?.measure(loose.copy(maxHeight = left))?.takeIf { it.height > 0 }
    val lines =
        PanelSlot.entries.mapNotNull { slot ->
            if (slot == PanelSlot.Overview) overview else measured[slot]?.takeIf { slot in kept }
        }
    val width = lines.maxOfOrNull { it.width } ?: 0
    layout(constraints.constrainWidth(width), constraints.maxHeight) {
        var y = 0
        for (line in lines) {
            line.placeRelative(0, y)
            y += line.height + gap
        }
    }
}

@Composable
private fun PanelLines(
    details: CellDetails,
    nowMs: Long,
    describe: Boolean,
) {
    val copy = programCopy()
    val cell = details.cell
    val episode = remember(cell, copy) { episodeLine(cell, copy) }
    val metaCopy = metaCopy()
    val item = details.item
    val meta = remember(item, metaCopy) { item?.let { metaLine(it.year, it.runtime, it.genres, metaCopy) } }
    val colors = LocalGuideColors.current
    Slot(PanelSlot.Channel) {
        Text(
            text = channelLine(details.channelNumber, details.channelName),
            modifier = Modifier.testTag(GuideTags.PANEL_CHANNEL),
            style = guideFigures(11.sp, FontWeight.Normal),
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Slot(PanelSlot.Title) {
        Text(
            text = cellTitle(cell, copy),
            modifier = Modifier.fillMaxWidth().testTag(GuideTags.PANEL_TITLE),
            style = guideText(24.sp, FontWeight.SemiBold).copy(letterSpacing = TITLE_TRACKING.em),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    episode?.let {
        Slot(PanelSlot.Episode) {
            Text(
                text = it,
                modifier = Modifier.testTag(GuideTags.PANEL_EPISODE),
                style = guideText(11.sp, FontWeight.Normal),
                color = colors.textSupporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    meta?.let {
        Slot(PanelSlot.Meta) {
            Text(
                text = it,
                modifier = Modifier.testTag(GuideTags.PANEL_META),
                style = guideText(11.sp, FontWeight.Normal),
                color = colors.textSupporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val onNow = cell.startMs <= nowMs && nowMs < cell.endMs
    Slot(PanelSlot.Time) { TimeRow(cell, nowMs, onNow, copy) }
    if (onNow && cell.kind == CellKind.Program) Slot(PanelSlot.Progress) { Progress(cell.startMs, cell.endMs, nowMs) }
    details.item?.overview?.takeIf { describe }?.let {
        Slot(PanelSlot.Overview) { Overview(it, if (episode != null) OVERVIEW_LINES_WITH_EPISODE else OVERVIEW_LINES) }
    }
}

/**
 * The overview takes the height the lines above it leave, up to [maxLines], and ends in an ellipsis on the last line
 * that fits; with room for no whole line it is not drawn. The panel clips at its bounds, and a line cut through its
 * glyphs reads worse than a shorter text.
 */
@Composable
private fun Overview(
    text: String,
    maxLines: Int,
) {
    val lineHeight = with(LocalDensity.current) { OVERVIEW_LINE_HEIGHT.toDp() }
    BoxWithConstraints {
        val lines = minOf(maxLines, (maxHeight / lineHeight).toInt())
        if (lines > 0) {
            Text(
                text = text,
                modifier = Modifier.testTag(GuideTags.PANEL_OVERVIEW),
                style = guideText(18.sp, FontWeight.Normal).copy(lineHeight = OVERVIEW_LINE_HEIGHT),
                color = LocalGuideColors.current.textSupporting,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A NOW chip, the slot and the time left; or the slot and how soon it starts. */
@Composable
private fun TimeRow(
    cell: GuideCell,
    nowMs: Long,
    onNow: Boolean,
    copy: ProgramCopy,
) {
    val zone = remember { ZoneId.systemDefault() }
    val is24h = is24Hour()
    val slot = ProgramText.slotOn(cell.startMs, cell.endMs, nowMs, zone, is24h)
    val colors = LocalGuideColors.current
    val time = guideFigures(11.sp, FontWeight.Medium)
    Row(
        Modifier.testTag(GuideTags.PANEL_TIME),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onNow) {
            Text(
                text = stringResource(R.string.guide_now).uppercase(),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(ChipRadius))
                        .drawBehind { drawRect(colors.nowChipFill) }
                        .padding(horizontal = ChipPadding, vertical = 2.dp),
                style = guideText(11.sp, FontWeight.Bold),
                color = colors.nowChipText,
                maxLines = 1,
            )
        }
        Text(text = slot, style = time, color = colors.textSecondary, maxLines = 1)
        when {
            onNow -> {
                val left = ProgramText.minutesUntil(cell.endMs, nowMs)
                Text(
                    text = copy.minutesLeft(left),
                    modifier = OutsideTheLiveRegion,
                    style = time,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }

            cell.startMs > nowMs -> {
                val soon = ProgramText.minutesUntil(cell.startMs, nowMs)
                Text(
                    text = copy.startsIn(soon),
                    modifier = OutsideTheLiveRegion,
                    style = time,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** How far through its slot a program on now is. */
@Composable
private fun Progress(
    startMs: Long,
    endMs: Long,
    nowMs: Long,
) {
    val colors = LocalGuideColors.current
    val done = if (endMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(ProgressHeight)
            .then(OutsideTheLiveRegion)
            .testTag(GuideTags.PANEL_PROGRESS)
            .drawBehind {
                val radius = CornerRadius(ProgressRadius.toPx())
                drawRoundRect(colors.progressTrack, cornerRadius = radius)
                drawRoundRect(
                    colors.progressFill,
                    size = Size(size.width * done, size.height),
                    cornerRadius = radius,
                )
            },
    )
}
