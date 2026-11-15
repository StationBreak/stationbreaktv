// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.stationbreak.core.domain.lineup.ChannelHoldings
import tv.stationbreak.core.domain.lineup.ChannelIdentity
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.ChannelHoldingsHours
import tv.stationbreak.core.ui.ChannelHoldingsList
import tv.stationbreak.core.ui.HoldingsTags
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.core.ui.guideFigures
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.core.ui.mark.ChannelMark
import tv.stationbreak.feature.guide.GuideRow
import tv.stationbreak.feature.guide.GuideSnapshot
import tv.stationbreak.feature.guide.R
import tv.stationbreak.feature.guide.focusedRow
import java.time.ZoneId

// The mark at twice its 50 by 28 dp column box (#631).
private val LargeMarkWidth = 100.dp
private val LargeMarkHeight = 56.dp
private val MarkGap = 12.dp
private val SlotGap = 6.dp
private val HoldingsGap = 2.dp

private const val TAG_LINE_SP = 13
private const val TAG_LINE_LINES = 2
private const val SMALL_SP = 11

/** No channel has an identity: for a guide with no lineup to read them from. */
internal val NoIdentities: StateFlow<Map<ChannelId, ChannelIdentity>> =
    MutableStateFlow<Map<ChannelId, ChannelIdentity>>(emptyMap()).asStateFlow()

/** The lines of the channel box, in the order they are drawn, top to bottom. */
private enum class ChannelSlot { Identity, Tags, OnNow, Hours, List }

/**
 * What stays when the box is short: the first of these first. "Home to:" (or "Mostly:") gives way first, then the
 * hours line, then On now / Next, then the tags. The identity block (the mark, the number, the name and the tag line
 * under it) always stays. Each line is kept or left out whole, never clipped through its glyphs.
 */
private val KeepOrder =
    listOf(ChannelSlot.Identity, ChannelSlot.Tags, ChannelSlot.OnNow, ChannelSlot.Hours, ChannelSlot.List)

/**
 * The details panel while the focus is on the channel column (#631): who the channel is and what it holds. Top to
 * bottom (#777): the mark, the number and the name, with the channel's tag line under the name (a starter's
 * personality, or one line about what a channel with none plays); its tags (kids with the rating ceiling, then its
 * kind); what is on now and next, from the row's own cells; then (#757) its hours and the titles that live there
 * ("Home to:") or, with none, those with the most hours ("Mostly:").
 *
 * The tag line and the tags come with the channel at once, from the identities the session read when it opened; the
 * holdings come once focus has rested on the channel, and only the focused channel's are drawn. A channel that is off
 * or has nothing today shows none. Read out as it changes, like the program's, since a screen reader cannot see the
 * guide's own focus. Only the focused row is read, and only while this shows.
 */
@Composable
internal fun ChannelPanel(
    snapshotOf: () -> GuideSnapshot,
    readers: ChannelBoxReaders,
    nowOf: () -> Long?,
    modifier: Modifier,
) {
    val row by remember(snapshotOf) { derivedStateOf { snapshotOf().focusedRow() } }
    val now by remember(nowOf) { derivedStateOf(nowOf) }
    val shown = row ?: return
    val held =
        readers.holdings
            .collectAsStateWithLifecycle()
            .value
            ?.takeIf { it.channel == shown.channel }
    val identity = readers.identities.collectAsStateWithLifecycle().value[shown.channel]
    val lineCopy = channelLineCopy()
    val tagLine = remember(identity, lineCopy) { identity?.let { tagLineOf(it, lineCopy) } }
    val tags = remember(identity, lineCopy) { identity?.let { channelTagsOf(it, lineCopy) }.orEmpty() }
    val nowCopy = onNowCopy()
    val is24h = is24Hour()
    val onNow =
        remember(shown.cells, now, nowCopy, is24h) {
            now?.let { at ->
                onNowNextLine(shown.cells, at, nowCopy) { ProgramText.time(it, ZoneId.systemDefault(), is24h) }
            }
        }
    ChannelColumn(
        modifier
            .clipToBounds()
            .testTag(GuideTags.CHANNEL_PANEL)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Slot(ChannelSlot.Identity) { IdentityBlock(shown, tagLine) }
        if (tags.isNotEmpty()) {
            Slot(ChannelSlot.Tags) { TagRow(tags, GuideTags.CHANNEL_PANEL_TAGS, GuideTags.CHANNEL_PANEL_TAG) }
        }
        onNow?.let { Slot(ChannelSlot.OnNow) { SmallLine(it, GuideTags.CHANNEL_PANEL_ON_NOW) } }
        held?.holdings?.let { HoldingsSlots(it) }
    }
}

// The mark, then the number, the name and the tag line under it.
@Composable
private fun IdentityBlock(
    shown: GuideRow,
    tagLine: TagLine?,
) {
    val colors = LocalGuideColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChannelMark(shown.markKey, shown.initials, Modifier.size(LargeMarkWidth, LargeMarkHeight))
        Spacer(Modifier.width(MarkGap))
        Column {
            Text(
                text = stringResource(R.string.guide_channel_number, shown.number).uppercase(),
                style = guideFigures(SMALL_SP.sp, FontWeight.Normal),
                color = colors.textSecondary,
                maxLines = 1,
            )
            Text(
                text = shown.name,
                modifier = Modifier.testTag(GuideTags.CHANNEL_PANEL_NAME),
                style = guideText(20.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            tagLine?.let { TagLineText(it) }
        }
    }
}

@Composable
private fun SmallLine(
    text: String,
    tag: String,
) = Text(
    text = text,
    modifier = Modifier.testTag(tag),
    style = guideText(SMALL_SP.sp, FontWeight.Normal),
    color = LocalGuideColors.current.textSecondary,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

// The hours line and the list, each a slot of its own so the list can give way while the hours stay.
@Composable
private fun HoldingsSlots(held: ChannelHoldings) {
    val colors = LocalGuideColors.current
    Slot(ChannelSlot.Hours) {
        ChannelHoldingsHours(held.hours, held.days, held.titleCount, colors.textSecondary, HoldingsLineTags)
    }
    if (held.listedNames.isNotEmpty()) {
        Slot(ChannelSlot.List) {
            ChannelHoldingsList(
                homeTo = held.homeTo,
                names = held.listedNames,
                more = held.more,
                primary = colors.textPrimary,
                secondary = colors.textSecondary,
                tags = HoldingsLineTags,
            )
        }
    }
}

/**
 * The tag line under the name, 13 sp: a personality in `textPrimary` on up to two lines; words about what a channel
 * with no personality plays in `textSecondary` on one.
 */
@Composable
private fun TagLineText(line: TagLine) {
    val colors = LocalGuideColors.current
    Text(
        text = line.text,
        modifier = Modifier.testTag(GuideTags.CHANNEL_PANEL_TAG_LINE),
        style =
            if (line.personality) {
                guideText(TAG_LINE_SP.sp, FontWeight.Normal).copy(lineHeight = (TAG_LINE_SP + 2).sp)
            } else {
                guideText(TAG_LINE_SP.sp, FontWeight.Normal)
            },
        color = if (line.personality) colors.textPrimary else colors.textSecondary,
        maxLines = if (line.personality) TAG_LINE_LINES else 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Slot(
    slot: ChannelSlot,
    content: @Composable () -> Unit,
) = Box(Modifier.layoutId(slot)) { content() }

/**
 * The [ChannelSlot] lines in their own order, kept by [KeepOrder] while they fit the height and left out whole when
 * they do not, so the box's clip never cuts a glyph. The kept lines sit in the middle of the height, as the box's
 * column did before. The hours line and the list under it sit closer together, as one block.
 */
@Composable
private fun ChannelColumn(
    modifier: Modifier,
    content: @Composable () -> Unit,
) = Layout(content, modifier) { measurables, constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val bySlot = measurables.associateBy { it.layoutId as ChannelSlot }
    val measured = KeepOrder.mapNotNull { slot -> bySlot[slot]?.let { slot to it.measure(loose) } }.toMap()
    val gapAbove: (ChannelSlot) -> Int = { slot ->
        (if (slot == ChannelSlot.List) HoldingsGap else SlotGap).roundToPx()
    }
    val kept = mutableSetOf<ChannelSlot>()
    for ((slot, placeable) in measured) {
        val trial = kept + slot
        val height =
            ChannelSlot.entries.filter { it in trial }.foldIndexed(0) { i, sum, s ->
                sum + measured.getValue(s).height + if (i > 0) gapAbove(s) else 0
            }
        if (height <= constraints.maxHeight) kept += slot
    }
    val lines = ChannelSlot.entries.filter { it in kept }
    val used = lines.foldIndexed(0) { i, sum, s -> sum + measured.getValue(s).height + if (i > 0) gapAbove(s) else 0 }
    val width = lines.maxOfOrNull { measured.getValue(it).width } ?: 0
    layout(constraints.constrainWidth(width), constraints.maxHeight) {
        var y = (constraints.maxHeight - used) / 2
        lines.forEachIndexed { i, slot ->
            if (i > 0) y += gapAbove(slot)
            val placeable = measured.getValue(slot)
            placeable.placeRelative(0, y)
            y += placeable.height
        }
    }
}

private val HoldingsLineTags =
    HoldingsTags(GuideTags.CHANNEL_PANEL_HOURS, GuideTags.CHANNEL_PANEL_LIST_LABEL, GuideTags.CHANNEL_PANEL_LIST)
