// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.FocusStyle
import tv.stationbreak.feature.channels.PickerState

/** The three digits side by side; the focused one is drawn with the shared focus treatment. */
@Composable
internal fun NumberPicker(
    state: PickerState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.testTag(ChannelsTags.PICKER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.digits.forEachIndexed { i, digit ->
            val focused = i == state.at
            Box(
                Modifier
                    .size(width = 48.dp, height = 64.dp)
                    .then(FocusStyle.drawn(focused))
                    .testTag(ChannelsTags.digit(i))
                    .semantics { selected = focused },
                contentAlignment = Alignment.Center,
            ) {
                Text(digit.toString(), Modifier.padding(4.dp), style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}
