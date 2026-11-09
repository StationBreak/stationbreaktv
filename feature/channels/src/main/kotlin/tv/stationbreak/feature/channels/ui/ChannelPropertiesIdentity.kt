// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelProperties
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.R
import java.text.DateFormat
import java.util.Date

// The top and the bottom line of the properties sheet: who the channel is, and whether it is on the air.

private const val NAME_SP = 20

@Composable
private fun nameStyle(): TextStyle =
    MaterialTheme.typography.titleMedium.copy(
        fontSize = NAME_SP.sp,
        fontWeight = FontWeight.SemiBold,
    )

/** The date of a status line, in the viewer's own format. */
internal fun formatDate(epochMs: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

@Composable
internal fun Identity(
    row: ChannelRow,
    properties: ChannelProperties?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MarkTile(row.name)
        Column {
            val number = row.number?.let { stringResource(R.string.channel_props_number, it) }
            Text(
                number ?: stringResource(R.string.channel_props_unnumbered),
                style = small(),
                color = ChannelsPalette.secondary,
            )
            Text(
                row.name,
                Modifier.testTag(ChannelsTags.ABOUT_NAME),
                style = nameStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Tags(row, properties)
    properties?.personality?.let {
        Text(
            stringResource(R.string.channel_props_personality, it),
            Modifier.testTag(ChannelsTags.ABOUT_PERSONALITY),
            style = small().copy(fontStyle = FontStyle.Italic),
            color = ChannelsPalette.secondary,
        )
    }
}

// The channel's mark, not yet drawn here: the guide's logos are bundled in feature:guide, so this is its initials.
@Composable
private fun MarkTile(name: String) {
    Box(
        Modifier.size(width = 50.dp, height = 28.dp).background(ChannelsPalette.sheetLine),
        contentAlignment = Alignment.Center,
    ) {
        Text(initialsOf(name), style = small().copy(fontWeight = FontWeight.SemiBold), color = ChannelsPalette.text)
    }
}

internal fun initialsOf(name: String): String =
    name
        .split(' ')
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }

@Composable
private fun Tags(
    row: ChannelRow,
    properties: ChannelProperties?,
) {
    val kind =
        when (row.kind) {
            ChannelKind.Starter -> stringResource(R.string.channel_props_tag_builtin)
            ChannelKind.Custom -> stringResource(R.string.channels_label_custom)
            ChannelKind.Leftovers -> stringResource(R.string.channels_label_leftovers)
            ChannelKind.Unusable, ChannelKind.UnusableCustom -> stringResource(R.string.channels_label_unusable)
        }
    val ceiling = properties?.description?.ceiling ?: properties?.leftoversCeiling
    Row(Modifier.testTag(ChannelsTags.ABOUT_TAGS), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(kind.uppercase(), style = small(), color = ChannelsPalette.secondary)
        if (row.kid) {
            val kids =
                if (ceiling == null) {
                    stringResource(R.string.channel_props_tag_kids_plain)
                } else {
                    stringResource(R.string.channel_props_tag_kids, ceiling)
                }
            Text(kids.uppercase(), style = small(), color = ChannelsPalette.kidsTag)
        }
    }
}

// One line: a dot in the state's colour, the state, and why (the channel list's own words for the reason).
@Composable
internal fun Status(
    row: ChannelRow,
    properties: ChannelProperties?,
) {
    val since = properties?.summary?.onSince?.let { formatDate(it.toEpochMilliseconds()) }
    val (state, why) =
        when (row.group) {
            ChannelGroup.On -> {
                val on = since?.let { stringResource(R.string.channel_props_status_on_since, it) }.orEmpty()
                val hidden = row.reason
                on to
                    if (hidden == null) {
                        stringResource(R.string.channel_props_status_showing)
                    } else {
                        stringResource(R.string.channel_props_status_hidden, stringResource(reasonText(hidden)))
                    }
            }

            ChannelGroup.Shelf -> {
                stringResource(R.string.channel_props_status_not_on) to
                    row.shelfReason?.let { stringResource(shelfReasonText(it)) }.orEmpty()
            }

            ChannelGroup.Off -> {
                stringResource(R.string.channel_props_status_off) to
                    stringResource(R.string.channel_props_status_off_why)
            }
        }
    val line = listOf(state, why).filter { it.isNotEmpty() }.joinToString("  ·  ")
    Row(Modifier.testTag(ChannelsTags.ABOUT_STATUS), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(line, style = body(), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
