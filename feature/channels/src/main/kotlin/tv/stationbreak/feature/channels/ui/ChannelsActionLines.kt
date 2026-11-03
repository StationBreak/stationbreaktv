// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.drawnFocus
import tv.stationbreak.feature.channels.R

/** The two lines that are not channels: New channel above the first group and Reset after the last. */
@Composable
internal fun NewLine(focused: Boolean) {
    Text(
        stringResource(R.string.builder_new_channel),
        Modifier
            .fillMaxWidth()
            .drawnFocus(focused)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(ChannelsTags.NEW_CHANNEL)
            .semantics { selected = focused },
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
internal fun ResetLine(focused: Boolean) {
    Text(
        stringResource(R.string.channels_reset),
        Modifier
            .fillMaxWidth()
            .drawnFocus(focused)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(ChannelsTags.RESET)
            .semantics { selected = focused },
        style = MaterialTheme.typography.bodyLarge,
    )
}
