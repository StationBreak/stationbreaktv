// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.ChannelHoldings
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelProperties
import tv.stationbreak.core.domain.lineup.HoldingsList
import tv.stationbreak.core.ui.ChannelHoldingsLines
import tv.stationbreak.core.ui.HoldingsFigures
import tv.stationbreak.core.ui.HoldingsTags
import tv.stationbreak.core.ui.drawnFocus
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.choicesOf
import java.text.DateFormat
import java.util.Date

// The sheet's measures, from the design (docs/design/channel-properties.md): 380 dp of the 960 dp layout.
private val SheetWidth = 380.dp
private val Gutter = 16.dp
internal const val SMALL_SP = 11
private const val BODY_SP = 12
private const val SCRIM_ALPHA = 0.55f

/**
 * What a channel is and what feeds it (#631): a right-hand sheet over the dimmed list, drawn from [ui]'s `about`
 * channel. The identity and status come from the row the list already has, so they show at once; "What it plays" and
 * "How it runs" show a loading line until the rules have been read ([ChannelsUi.properties]). Its actions are the
 * actions panel's, minus the one that opened it. [toGuide] is whether it was opened from the guide (#631), so the hint
 * says where Back returns to. Plain words only: nothing here is a wire name or an ID.
 */
@Composable
internal fun ChannelPropertiesSheet(
    ui: ChannelsUi,
    modifier: Modifier = Modifier,
    toGuide: Boolean = false,
) {
    val row = ui.rows.firstOrNull { it.id == ui.about } ?: return
    val properties = ui.properties?.takeIf { it.summary.id == row.id }
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM_ALPHA)).testTag(ChannelsTags.ABOUT)) {
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .width(SheetWidth)
                .fillMaxHeight()
                .background(ChannelsPalette.sheet),
        ) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(ChannelsPalette.sheetLine))
            Column(Modifier.fillMaxSize().padding(Gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Identity(row, properties)
                if (properties == null) {
                    Plain(stringResource(R.string.channel_props_loading), ChannelsTags.ABOUT_LOADING, secondary = true)
                } else {
                    Rules(row, properties)
                }
                Status(row, properties)
                Actions(ui, toGuide)
            }
        }
    }
}

@Composable
private fun Rules(
    row: ChannelRow,
    properties: ChannelProperties,
) {
    if (row.kind.unreadable) {
        Plain(stringResource(R.string.channels_label_unusable), ChannelsTags.ABOUT_PLAYS)
        if (row.kind == ChannelKind.UnusableCustom) {
            Plain(stringResource(R.string.builder_newer_app_channel), ChannelsTags.ABOUT_RUNS, secondary = true)
        }
        return
    }
    Section(stringResource(R.string.channel_props_section_plays), ChannelsTags.ABOUT_PLAYS) {
        if (properties.description == null) Plain(stringResource(R.string.channel_props_leftovers), null)
        playRows(properties).forEach { (label, value) -> KeyValue(label, value) }
    }
    properties.holdings?.let { Holds(it) }
    val runs = runRows(properties)
    val code = properties.timing?.scheduleCode
    if (runs.isNotEmpty() && code != null) {
        Section(stringResource(R.string.channel_props_section_runs), ChannelsTags.ABOUT_RUNS) {
            runs.forEach { (label, value) -> KeyValue(label, value) }
            KeyValue(stringResource(R.string.channel_props_row_code), code, ChannelsTags.ABOUT_CODE)
        }
    }
}

// What the channel holds (#757): the value the guide's channel box shows, in the same words.
@Composable
private fun Holds(holdings: ChannelHoldings) {
    Section(stringResource(R.string.channel_props_section_holds), ChannelsTags.ABOUT_HOLDS) {
        ChannelHoldingsLines(
            figures = holdings.figures(),
            primary = ChannelsPalette.text,
            secondary = ChannelsPalette.secondary,
            tags = HoldsTags,
        )
    }
}

private val HoldsTags =
    HoldingsTags(ChannelsTags.ABOUT_HOLDS_HOURS, ChannelsTags.ABOUT_HOLDS_LABEL, ChannelsTags.ABOUT_HOLDS_LIST)

@Composable
private fun Section(
    title: String,
    tag: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.testTag(tag).merged(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title.uppercase(), style = small().copy(fontWeight = FontWeight.Medium), color = ChannelsPalette.secondary)
        content()
    }
}

@Composable
private fun KeyValue(
    label: String,
    value: String,
    tag: String? = null,
) {
    // Only a row with its own tag is a node of its own; the rest fold into their section's node (a merged node
    // nested in a merged node is not folded into its parent).
    Row(Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag).merged() else it }) {
        Text(label, Modifier.width(96.dp), style = body(), color = ChannelsPalette.secondary)
        Text(value, Modifier.weight(1f), style = body(), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Plain(
    text: String,
    tag: String?,
    secondary: Boolean = false,
) {
    Text(
        text,
        Modifier.let { if (tag != null) it.testTag(tag) else it }.semantics { liveRegion = LiveRegionMode.Polite },
        style = body(),
        color = if (secondary) ChannelsPalette.secondary else ChannelsPalette.text,
    )
}

@Composable
private fun Actions(
    ui: ChannelsUi,
    toGuide: Boolean,
) {
    // A panel open over the sheet has the keys: the sheet's own choices show without a focus until it goes.
    val covered = ui.panel != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val choices = if (covered) sheetChoices(ui) else choicesOf(ui)
        choices.forEachIndexed { i, choice ->
            Text(
                labelOf(choice, ui.panel),
                Modifier
                    .fillMaxWidth()
                    .drawnFocus(!covered && i == ui.choice)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag(tagOf(choice))
                    .semantics { selected = !covered && i == ui.choice },
                style = body().copy(fontWeight = FontWeight.Medium),
            )
        }
    }
    val returnHint = if (toGuide) R.string.channel_props_hint_return_guide else R.string.channel_props_hint_return
    Text(
        stringResource(R.string.channel_props_hint_back) + "  " + stringResource(returnHint),
        style = small(),
        color = ChannelsPalette.secondary,
    )
}

@Composable
internal fun small() = MaterialTheme.typography.bodySmall.copy(fontSize = SMALL_SP.sp)

@Composable
internal fun body() = MaterialTheme.typography.bodyMedium.copy(fontSize = BODY_SP.sp)

/** The sheet's own actions, whatever panel is open over it. */
private fun sheetChoices(ui: ChannelsUi) = choicesOf(ui.copy(panel = null))

// One node per block, so a reader announces it whole and a test reads its text.
internal fun Modifier.merged(): Modifier = semantics(mergeDescendants = true) {}

/** The words of [this] as the sheet and the guide's channel box draw them. */
internal fun ChannelHoldings.figures() = HoldingsFigures(hours, days, titleCount, list == HoldingsList.HomeTo, listed.map { it.name }, more)
