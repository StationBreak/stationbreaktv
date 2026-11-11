// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.core.ui.guideFigures
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.guide.CellDetails
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.R
import tv.stationbreak.feature.guide.panelTagsOf
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

private val ProgressHeight = 2.dp
private val ProgressRadius = 1.dp
private val LineGap = 3.dp
private val MetaGap = 8.dp

// The description takes every whole line the height left over holds (four to six on the reference panel): a text that
// ends in an ellipsis reads better than one clipped mid-line.
private val OVERVIEW_LINE_HEIGHT = 15.sp

/** ON NOW: 10 sp, not the spec's 8.5, which is under the 10 sp floor the guide holds; bold, tracked +6%. */
private const val ON_NOW_TRACKING = 0.06f
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

private enum class PanelSlot { Channel, Title, Episode, Meta, Tags, Progress, Time, Overview }

/**
 * What drops first when the band is short: the last of these goes first, whole, never clipped through its glyphs. The
 * description gives way before any of them (it takes only what is left), then the tags.
 */
private val DropOrder =
    listOf(
        PanelSlot.Channel,
        PanelSlot.Title,
        PanelSlot.Time,
        PanelSlot.Progress,
        PanelSlot.Episode,
        PanelSlot.Meta,
        PanelSlot.Tags,
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
        PanelTitle(details.channelNumber to cell.startMs, cellTitle(cell, copy), details.logo, colors)
    }
    episode?.let {
        Slot(PanelSlot.Episode) {
            Text(
                text = it,
                modifier = Modifier.testTag(GuideTags.PANEL_EPISODE),
                style = guideText(11.sp, FontWeight.Normal),
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val onNow = cell.startMs <= nowMs && nowMs < cell.endMs
    if (onNow || meta != null) Slot(PanelSlot.Meta) { MetaRow(onNow, meta) }
    val tagCopy = tagCopy()
    val tags = remember(item, tagCopy) { item?.let { panelTagsOf(it, tagCopy) }.orEmpty() }
    if (tags.isNotEmpty()) Slot(PanelSlot.Tags) { TagRow(tags) }
    if (onNow && cell.kind == CellKind.Program) Slot(PanelSlot.Progress) { Progress(cell.startMs, cell.endMs, nowMs) }
    Slot(PanelSlot.Time) { TimeRow(cell, nowMs, onNow, copy) }
    details.item?.overview?.takeIf { describe }?.let {
        Slot(PanelSlot.Overview) { Overview(it) }
    }
}

/**
 * The overview takes the height the lines above it leave, as many whole lines as that holds, and ends in an ellipsis
 * on the last; with room for no whole line it is not drawn. The panel clips at its bounds, and a line cut through its
 * glyphs reads worse than a shorter text.
 */
@Composable
private fun Overview(text: String) {
    val lineHeight = with(LocalDensity.current) { OVERVIEW_LINE_HEIGHT.toDp() }
    BoxWithConstraints {
        val lines = (maxHeight / lineHeight).toInt()
        if (lines > 0) {
            Text(
                text = text,
                modifier = Modifier.testTag(GuideTags.PANEL_OVERVIEW),
                style = guideText(11.sp, FontWeight.Normal).copy(lineHeight = OVERVIEW_LINE_HEIGHT),
                color = LocalGuideColors.current.textSecondary,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * "ON NOW" as accent text, then the meta line. There is no chip: the one orange slab on screen is the focused cell.
 * [meta] is the "year · runtime · genres" line, tagged on its own so it can be read and tested as that.
 */
@Composable
private fun MetaRow(
    onNow: Boolean,
    meta: String?,
) {
    val colors = LocalGuideColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(MetaGap), verticalAlignment = Alignment.CenterVertically) {
        if (onNow) {
            Text(
                text = stringResource(R.string.guide_now).uppercase(),
                style = guideText(10.sp, FontWeight.Bold).copy(letterSpacing = ON_NOW_TRACKING.em),
                color = colors.accent,
                maxLines = 1,
            )
        }
        meta?.let {
            Text(
                text = it,
                modifier = Modifier.testTag(GuideTags.PANEL_META),
                style = guideText(11.sp, FontWeight.Normal),
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Under the progress line of a program on now: its start, the time left and its end, spread across the panel. For a
 * later program: its slot and how soon it starts. A screen reader reads the slot once, as before, not the two ends.
 */
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
    if (onNow && cell.kind == CellKind.Program) {
        NowTimes(cell, nowMs, slot, copy)
    } else {
        LaterTimes(cell, nowMs, onNow, slot, copy)
    }
}

/** A program on now: its start, the time left and its end, spread across the panel under the progress line. */
@Composable
private fun NowTimes(
    cell: GuideCell,
    nowMs: Long,
    slot: String,
    copy: ProgramCopy,
) {
    val zone = remember { ZoneId.systemDefault() }
    val is24h = is24Hour()
    val colors = LocalGuideColors.current
    val time = guideFigures(11.sp, FontWeight.Normal)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(GuideTags.PANEL_TIME)
            .semantics { contentDescription = slot },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val ends = Modifier.clearAndSetSemantics {}
        Text(ProgramText.time(cell.startMs, zone, is24h), ends, colors.textSecondary, style = time, maxLines = 1)
        Text(
            text = copy.minutesLeft(ProgramText.minutesUntil(cell.endMs, nowMs)),
            modifier = OutsideTheLiveRegion,
            style = time,
            color = colors.textSecondary,
            maxLines = 1,
        )
        Text(ProgramText.time(cell.endMs, zone, is24h), ends, colors.textSecondary, style = time, maxLines = 1)
    }
}

/** Anything else: the slot, then the time left for a slate on now, or how soon a later program starts. */
@Composable
private fun LaterTimes(
    cell: GuideCell,
    nowMs: Long,
    onNow: Boolean,
    slot: String,
    copy: ProgramCopy,
) {
    val colors = LocalGuideColors.current
    val time = guideFigures(11.sp, FontWeight.Normal)
    Row(
        Modifier.testTag(GuideTags.PANEL_TIME),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = slot, style = time, color = colors.textSecondary, maxLines = 1)
        val note =
            when {
                onNow -> {
                    copy.minutesLeft(ProgramText.minutesUntil(cell.endMs, nowMs)) to colors.textSecondary
                }

                cell.startMs > nowMs -> {
                    copy.startsIn(ProgramText.minutesUntil(cell.startMs, nowMs)) to colors.textPrimary
                }

                else -> {
                    null
                }
            }
        note?.let { (text, color) ->
            Text(text = text, modifier = OutsideTheLiveRegion, style = time, color = color, maxLines = 1)
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
                drawRoundRect(colors.line, cornerRadius = radius)
                drawRoundRect(
                    colors.progressFill,
                    size = Size(size.width * done, size.height),
                    cornerRadius = radius,
                )
            },
    )
}
