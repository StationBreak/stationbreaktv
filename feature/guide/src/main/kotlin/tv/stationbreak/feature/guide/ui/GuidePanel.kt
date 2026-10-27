// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.BroadcastType
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.guide.CellDetails
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.R
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

private val ChipPadding = 6.dp
private const val PROGRESS_TRACK_ALPHA = 0.35f
private const val OVERVIEW_LINES_WITH_EPISODE = 2
private const val OVERVIEW_LINES = 3
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
    Column(
        modifier
            .clipToBounds()
            .testTag(GuideTags.PANEL)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val shown = details ?: return@Column
        val rested = restedOn(shown.channelNumber to shown.cell.startMs)
        PanelLines(shown, now ?: shown.cell.startMs, describe = rested)
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

@Composable
private fun PanelLines(
    details: CellDetails,
    nowMs: Long,
    describe: Boolean,
) {
    val copy = programCopy()
    val cell = details.cell
    val episode = remember(cell, copy) { episodeLine(cell, copy) }
    Text(
        text = channelLine(details.channelNumber, details.channelName).uppercase(),
        modifier = Modifier.testTag(GuideTags.PANEL_CHANNEL),
        style = mono(16.sp, LETTER_SPACING.sp),
        color = BroadcastColors.Accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = cellTitle(cell, copy),
        modifier = Modifier.fillMaxWidth().testTag(GuideTags.PANEL_TITLE),
        style = TextStyle(fontFamily = BroadcastType.Display, fontSize = 28.sp, lineHeight = 30.sp),
        color = BroadcastColors.Cream,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    episode?.let {
        Text(
            text = it,
            modifier = Modifier.testTag(GuideTags.PANEL_EPISODE),
            style = mono(16.sp),
            color = BroadcastColors.Ground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val onNow = cell.startMs <= nowMs && nowMs < cell.endMs
    TimeRow(cell, nowMs, onNow, copy)
    if (onNow && cell.kind == CellKind.Program) Progress(cell.startMs, cell.endMs, nowMs)
    details.item?.overview?.takeIf { describe }?.let {
        Text(
            text = it,
            modifier = Modifier.testTag(GuideTags.PANEL_OVERVIEW),
            style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
            color = BroadcastColors.Cream,
            maxLines = if (episode != null) OVERVIEW_LINES_WITH_EPISODE else OVERVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
        )
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
                        .background(BroadcastColors.Accent)
                        .padding(horizontal = ChipPadding, vertical = 1.dp),
                style = mono(14.sp, LETTER_SPACING.sp),
                color = BroadcastColors.Ink,
                maxLines = 1,
            )
        }
        Text(text = slot, style = mono(16.sp), color = BroadcastColors.Ground, maxLines = 1)
        when {
            onNow -> {
                val left = ProgramText.minutesUntil(cell.endMs, nowMs)
                Text(
                    text = copy.minutesLeft(left),
                    modifier = OutsideTheLiveRegion,
                    style = mono(16.sp),
                    color = BroadcastColors.Ground,
                    maxLines = 1,
                )
            }

            cell.startMs > nowMs -> {
                val soon = ProgramText.minutesUntil(cell.startMs, nowMs)
                Text(
                    text = copy.startsIn(soon),
                    modifier = OutsideTheLiveRegion,
                    style = mono(16.sp),
                    color = BroadcastColors.Cream,
                    maxLines = 1,
                )
            }
        }
    }
}

/** How far through its slot a program on now is: 4 dp, the banner's colours. */
@Composable
private fun Progress(
    startMs: Long,
    endMs: Long,
    nowMs: Long,
) {
    val done = if (endMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .then(OutsideTheLiveRegion)
            .testTag(GuideTags.PANEL_PROGRESS)
            .drawBehind {
                drawRect(BroadcastColors.Ground.copy(alpha = PROGRESS_TRACK_ALPHA))
                drawRect(BroadcastColors.Accent, size = Size(size.width * done, size.height))
            },
    )
}
