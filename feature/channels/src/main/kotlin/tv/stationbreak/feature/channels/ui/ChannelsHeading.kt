// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.BroadcastType
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.R

private fun headingTag(group: ChannelGroup): String =
    when (group) {
        ChannelGroup.On -> ChannelsTags.HEADING_ON
        ChannelGroup.Shelf -> ChannelsTags.HEADING_SHELF
        ChannelGroup.Off -> ChannelsTags.HEADING_OFF
    }

/**
 * A group's flag (DESIGN.md "Flag"): a 1dp rule, then the name in capitals and how many channels it holds, in the
 * teletype. Not focusable (the list's focus is the model's); a heading for a screen reader, which hears the count too.
 */
@Composable
internal fun HeadingLine(
    group: ChannelGroup,
    count: Int,
) {
    val name = stringResource(groupName(group))
    val shown = stringResource(R.string.channels_group_flag, name.uppercase(LocalConfiguration.current.locales[0]), count)
    val spoken = pluralStringResource(R.plurals.channels_group_spoken, count, name, count)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag(headingTag(group))
            .clearAndSetSemantics {
                heading()
                contentDescription = spoken
            },
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(ChannelsPalette.sheetLine))
        Text(
            shown,
            Modifier.padding(top = 8.dp, bottom = 4.dp),
            style = flagStyle(),
            color = ChannelsPalette.secondary,
        )
    }
}

/** Space Mono Bold, 10sp, +13%: the flag of DESIGN.md's Typography. */
@Composable
internal fun flagStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontFamily = BroadcastType.Mono,
        fontWeight = FontWeight.Bold,
        fontSize = FLAG_SP.sp,
        letterSpacing = FLAG_SPACING_EM.em,
    )

private const val FLAG_SP = 10
private const val FLAG_SPACING_EM = 0.13f

private fun groupName(group: ChannelGroup): Int =
    when (group) {
        ChannelGroup.On -> R.string.channels_group_on
        ChannelGroup.Shelf -> R.string.channels_group_shelf
        ChannelGroup.Off -> R.string.channels_group_off
    }
