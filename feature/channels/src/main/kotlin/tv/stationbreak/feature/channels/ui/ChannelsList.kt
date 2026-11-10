// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import tv.stationbreak.core.domain.lineup.ShelfReason
import tv.stationbreak.core.ui.drawnFocus
import tv.stationbreak.core.ui.keepVisible
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.ChannelsFocus
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.R

/**
 * One line of the list: the "Use my collections" switch, the New channel row, a group's heading, a channel, or the
 * Reset row after the last group.
 */
private sealed interface Line {
    data object Household : Line

    data object New : Line

    data class Heading(
        val group: ChannelGroup,
    ) : Line

    data class Channel(
        val row: ChannelRow,
    ) : Line

    data object Reset : Line
}

private fun linesOf(rows: List<ChannelRow>): List<Line> =
    buildList {
        add(Line.Household)
        add(Line.New)
        ChannelGroup.entries.forEach { group ->
            val inGroup = rows.filter { it.group == group }
            if (inGroup.isEmpty()) return@forEach
            add(Line.Heading(group))
            inGroup.forEach { add(Line.Channel(it)) }
        }
        add(Line.Reset)
    }

@Composable
internal fun ChannelList(
    ui: ChannelsUi,
    modifier: Modifier = Modifier,
) {
    val lines = remember(ui.rows) { linesOf(ui.rows) }
    val state = rememberLazyListState()
    val focus = ui.focus
    val focused = remember(lines, focus) { lines.indexOfFirst { it.isFocus(focus) } }
    LaunchedEffect(focused, lines) { if (focused >= 0) state.keepVisible(focused, linesAbove = 1) }
    LazyColumn(
        modifier.fillMaxWidth().testTag(ChannelsTags.LIST),
        state = state,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(lines.size, key = { lines[it].key() }) { i ->
            when (val line = lines[i]) {
                Line.Household -> HouseholdLine(on = ui.useMyCollections, focused = i == focused)
                Line.New -> NewLine(focused = i == focused)
                is Line.Heading -> HeadingLine(line.group)
                is Line.Channel -> RowLine(line.row, focused = i == focused)
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
        is Line.Heading -> false
    }

private fun Line.key(): String =
    when (this) {
        Line.Household -> "household"
        Line.New -> "new"
        is Line.Heading -> "heading:$group"
        is Line.Channel -> "row:${row.id.value}"
        Line.Reset -> "reset"
    }

private fun headingTag(group: ChannelGroup): String =
    when (group) {
        ChannelGroup.On -> ChannelsTags.HEADING_ON
        ChannelGroup.Shelf -> ChannelsTags.HEADING_SHELF
        ChannelGroup.Off -> ChannelsTags.HEADING_OFF
    }

@Composable
private fun HeadingLine(group: ChannelGroup) {
    val text = stringResource(groupName(group))
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(headingTag(group)),
        style = MaterialTheme.typography.labelLarge,
        color = ChannelsPalette.secondary,
    )
}

private fun groupName(group: ChannelGroup): Int =
    when (group) {
        ChannelGroup.On -> R.string.channels_group_on
        ChannelGroup.Shelf -> R.string.channels_group_shelf
        ChannelGroup.Off -> R.string.channels_group_off
    }

@Composable
private fun RowLine(
    row: ChannelRow,
    focused: Boolean,
) {
    val reason = row.reason?.let { stringResource(reasonText(it)) }
    val shelfReason = row.shelfReason?.let { stringResource(shelfReasonText(it)) }
    val labels = labelsOf(row)
    val spoken = spokenRow(row.number, row.name, labels, reason, shelfReason)
    Column(
        Modifier
            .fillMaxWidth()
            .drawnFocus(focused)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(ChannelsTags.row(row.id))
            // One announcement for the whole row, reasons included; the state stays the row's own.
            .clearAndSetSemantics {
                contentDescription = spoken
                selected = focused
            },
    ) {
        val number = row.number?.toString().orEmpty()
        // Only the number and name are dimmed: the reasons stay at full strength so they can be read.
        Text(
            listOf(number, row.name, labels).filter { it.isNotEmpty() }.joinToString("  "),
            Modifier.alpha(if (reason != null) DIMMED_ALPHA else 1f),
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

internal fun shelfReasonText(reason: ShelfReason): Int =
    when (reason) {
        ShelfReason.Empty -> R.string.channels_shelf_empty
        ShelfReason.TooThin -> R.string.channels_shelf_thin
        ShelfReason.BelowCap -> R.string.channels_shelf_below_cap
        ShelfReason.OptIn -> R.string.channels_shelf_opt_in
    }

@Composable
private fun labelsOf(row: ChannelRow): String =
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
