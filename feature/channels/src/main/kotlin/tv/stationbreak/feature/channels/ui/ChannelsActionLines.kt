// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.drawnFocus
import tv.stationbreak.feature.channels.R

/** "Use my collections", a switch at the top of the list: its state is read from the semantics, not from the text. */
@Composable
internal fun HouseholdLine(
    on: Boolean,
    focused: Boolean,
) {
    val state = stringResource(if (on) R.string.channels_switch_on else R.string.channels_switch_off)
    Column(
        Modifier
            .fillMaxWidth()
            .drawnFocus(focused)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(ChannelsTags.HOUSEHOLD)
            .semantics(mergeDescendants = true) {
                selected = focused
                role = Role.Switch
                toggleableState = ToggleableState(on)
            },
    ) {
        Text(
            stringResource(R.string.channels_use_my_collections) + "  " + state,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.channels_use_my_collections_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = ChannelsPalette.secondary,
        )
    }
}

/** The two lines that are not switches: New channel above the first group and Reset after the last. */
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
