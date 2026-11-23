// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.NumberBand
import tv.stationbreak.core.domain.lineup.ShelfReason
import tv.stationbreak.core.domain.lineup.bandOf
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.keepVisible
import tv.stationbreak.core.ui.mark.ChannelMark
import tv.stationbreak.core.ui.mark.LocalMarkCache
import tv.stationbreak.core.ui.mark.markInitialsOf
import tv.stationbreak.core.ui.mark.variantForTint
import tv.stationbreak.core.ui.panelFocus
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.ChannelsFocus
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.R

/**
 * One line of the list: the "Use my collections" switch, the New channel row, a group's heading, the flag of a number
 * band within a group, a channel, or the Reset row after the last group. The heading and the band flag are not
 * focusable.
 */
internal sealed interface Line {
    data object Household : Line

    data object New : Line

    data class Heading(
        val group: ChannelGroup,
        val count: Int,
    ) : Line

    data class Band(
        val group: ChannelGroup,
        val band: NumberBand,
    ) : Line

    data class Channel(
        val row: ChannelRow,
    ) : Line

    data object Reset : Line
}

/**
 * The list's lines. Inside a group a [Line.Band] opens each run of rows in one number band (the rows are in number
 * order); a row with no number gets none and sits after the numbered ones, as before.
 */
internal fun linesOf(rows: List<ChannelRow>): List<Line> =
    buildList {
        add(Line.Household)
        add(Line.New)
        ChannelGroup.entries.forEach { group ->
            val inGroup = rows.filter { it.group == group }
            if (inGroup.isEmpty()) return@forEach
            add(Line.Heading(group, inGroup.size))
            var band: NumberBand? = null
            inGroup.forEach { row ->
                bandOf(row.number)?.takeIf { it != band }?.let {
                    band = it
                    add(Line.Band(group, it))
                }
                add(Line.Channel(row))
            }
        }
        add(Line.Reset)
    }

/**
 * How many lines above [index] to keep in view with it when scrolling up to it: the heading and the band flag that
 * open its run, which are not focusable and have to be seen with the first row under them.
 */
internal fun linesAboveOf(
    lines: List<Line>,
    index: Int,
): Int = (index - 1 downTo 0).takeWhile { lines[it] is Line.Heading || lines[it] is Line.Band }.size

@Composable
internal fun ChannelList(
    ui: ChannelsUi,
    modifier: Modifier = Modifier,
) {
    val lines = remember(ui.rows) { linesOf(ui.rows) }
    val state = rememberLazyListState()
    val focus = ui.focus
    val focused = remember(lines, focus) { lines.indexOfFirst { it.isFocus(focus) } }
    LaunchedEffect(focused, lines) {
        if (focused >= 0) state.keepVisible(focused, linesAbove = linesAboveOf(lines, focused))
    }
    RequestVisibleMarks(lines, state)
    LazyColumn(
        modifier.fillMaxWidth().testTag(ChannelsTags.LIST),
        state = state,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(lines.size, key = { lines[it].key() }, contentType = { lines[it]::class }) { i ->
            when (val line = lines[i]) {
                Line.Household -> HouseholdLine(on = ui.useMyCollections, focused = i == focused)
                Line.New -> NewLine(focused = i == focused)
                is Line.Heading -> HeadingLine(line.group, line.count)
                is Line.Band -> BandLine(line.group, line.band)
                is Line.Channel -> RowLine(line.row, focused = i == focused, isPro = ui.isPro)
                Line.Reset -> ResetLine(focused = i == focused)
            }
        }
    }
}

private fun Line.isFocus(focus: ChannelsFocus?): Boolean =
    when (this) {
        Line.Household -> focus == ChannelsFocus.Household
        Line.New -> focus == ChannelsFocus.New
        is Line.Channel -> focus == ChannelsFocus.Row(row.id)
        Line.Reset -> focus == ChannelsFocus.Reset
        is Line.Heading, is Line.Band -> false
    }

private fun Line.key(): String =
    when (this) {
        Line.Household -> "household"
        Line.New -> "new"
        is Line.Heading -> "heading:$group"
        is Line.Band -> "band:$group:$band"
        is Line.Channel -> "row:${row.id.value}"
        Line.Reset -> "reset"
    }

@Composable
private fun RowLine(
    row: ChannelRow,
    focused: Boolean,
    isPro: Boolean,
) {
    val reason = reasonOf(row)
    val shelfReason = row.shelfReason?.let { stringResource(shelfReasonText(it, isPro)) }
    val labels = labelsOf(row)
    val spoken = spokenRow(row.number, row.name, labels, reason, shelfReason, joiner(R.string.spoken_pair))
    val stripe = bandOf(row.number)?.takeIf { it != NumberBand.GrownUps }?.let { ChannelsPalette.band.of(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .panelFocus(focused)
            .bandStripe(stripe)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(ChannelsTags.row(row.id))
            // One announcement for the whole row, reasons included; the state stays the row's own.
            .clearAndSetSemantics {
                contentDescription = spoken
                selected = focused
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only the number, the mark and the name are dimmed: the reasons stay at full strength so they can be read.
        val dim = Modifier.alpha(if (reason != null) DIMMED_ALPHA else 1f)
        Text(
            row.number?.toString().orEmpty(),
            dim.widthIn(min = NUMBER_MIN_WIDTH),
            style = MaterialTheme.typography.bodyLarge,
        )
        // Tint and initials colour come from LocalGuideColors, which the guide host sets with LocalPanelColors.
        ChannelMark(
            row.markKey,
            markInitialsOf(row.name),
            dim.size(ROW_MARK_WIDTH, ROW_MARK_HEIGHT).testTag(ChannelsTags.rowMark(row.id)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                listOf(row.name, labels).filter { it.isNotEmpty() }.joinToString("  "),
                dim,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (reason != null) {
                Text(
                    reason,
                    Modifier.testTag(ChannelsTags.reason(row.id)),
                    style = channelsSecondaryLine(),
                    color = ChannelsPalette.secondary,
                )
            }
            if (shelfReason != null) {
                Text(
                    shelfReason,
                    Modifier.testTag(ChannelsTags.shelfReason(row.id)),
                    style = channelsSecondaryLine(),
                    color = ChannelsPalette.secondary,
                )
            }
        }
    }
}

/**
 * A [STRIPE_WIDTH] stripe of the band's accent on a row's left edge, inside the 3 dp focus ring (which is drawn over
 * it), with a little room above and below. Decoration only: it has no semantics, and Grown-ups has none.
 */
private fun Modifier.bandStripe(color: Color?): Modifier =
    if (color == null) {
        this
    } else {
        drawBehind {
            val inset = STRIPE_INSET.toPx()
            drawRect(color, Offset(inset, inset), Size(STRIPE_WIDTH.toPx(), size.height - 2 * inset))
        }
    }

private val STRIPE_WIDTH = 3.dp
private val STRIPE_INSET = 4.dp

// A smaller box than the guide's 50 x 28 so the mark does not crowd a 16sp row; the same proportion.
private val ROW_MARK_WIDTH = 40.dp
private val ROW_MARK_HEIGHT = 22.dp
private val NUMBER_MIN_WIDTH = 36.dp

/**
 * Asks the cache for the logos of the channels in view and a screenful each side; it only schedules the work. The
 * cache, the mark's tint and its initials colour are the host's: the guide provides `LocalMarkCache` and
 * `LocalGuideColors` (from the same theme as `LocalPanelColors`), and with no cache a mark draws its initials.
 */
@Composable
private fun RequestVisibleMarks(
    lines: List<Line>,
    state: LazyListState,
) {
    val marks = LocalMarkCache.current
    val variant = variantForTint(LocalGuideColors.current.markTint)
    LaunchedEffect(lines, marks, variant) {
        snapshotFlow { state.firstVisibleItemIndex }.collect { first ->
            val from = maxOf(0, first - MARK_LOOKAHEAD)
            val to = minOf(lines.size, first + MARK_LOOKAHEAD * 2)
            marks?.request((from until to).mapNotNull { (lines[it] as? Line.Channel)?.row?.markKey }, variant)
        }
    }
}

private const val MARK_LOOKAHEAD = 12

/** The shelf line for [reason]: a free viewer cannot switch a channel on, so its lines do not offer it. */
internal fun shelfReasonText(
    reason: ShelfReason,
    isPro: Boolean,
): Int =
    when (reason) {
        ShelfReason.Empty -> R.string.channels_shelf_empty
        ShelfReason.TooThin -> R.string.channels_shelf_thin
        ShelfReason.BelowCap -> if (isPro) R.string.channels_shelf_below_cap else R.string.channels_shelf_below_cap_free
        ShelfReason.NoNumber -> R.string.channels_shelf_no_number
        ShelfReason.OptIn -> if (isPro) R.string.channels_shelf_opt_in else R.string.channels_shelf_opt_in_free
    }

@Composable
internal fun labelsOf(row: ChannelRow): String =
    listOfNotNull(
        if (row.kid) R.string.channels_label_kids else null,
        when (row.kind) {
            ChannelKind.Leftovers -> R.string.channels_label_leftovers
            ChannelKind.Custom -> R.string.channels_label_custom
            ChannelKind.Unusable, ChannelKind.UnusableCustom -> R.string.channels_label_unusable
            ChannelKind.Starter -> null
        },
    ).map { stringResource(it) }
        .joinToString(", ")
        .let { if (it.isEmpty()) it else "($it)" }

internal fun reasonText(reason: HiddenReason): Int =
    when (reason) {
        HiddenReason.CollectionsNotSynced -> R.string.channels_reason_collections
        HiddenReason.HouseholdCollectionsOff -> R.string.channels_reason_household_off
        HiddenReason.LibraryNotChosen -> R.string.channels_reason_library_not_chosen
        HiddenReason.NoMatches -> R.string.channels_reason_no_matches
        HiddenReason.NothingMadeForKids -> R.string.channels_reason_not_for_kids
        HiddenReason.ChildrensRatingsOnly -> R.string.channels_reason_childrens_only
        HiddenReason.AllExcluded -> R.string.channels_reason_all_excluded
        HiddenReason.AllFilteredOut -> R.string.channels_reason_all_filtered
        HiddenReason.AllBlockedByCeiling -> R.string.channels_reason_ceiling
        HiddenReason.EmptyToday -> R.string.channels_reason_empty_today
    }
