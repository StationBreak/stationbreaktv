// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.stationbreak.core.domain.lineup.BandNeeded
import tv.stationbreak.core.domain.lineup.ChannelNumbers
import tv.stationbreak.feature.channels.R

/** The line for a number below the lowest band the channel may use (#1097), the numbers taken from the bands. */
@Composable
internal fun needsText(needs: BandNeeded): String =
    when (needs) {
        BandNeeded.Family -> {
            stringResource(R.string.channels_refused_needs_family, needs.first)
        }

        BandNeeded.Teen -> {
            stringResource(R.string.channels_refused_needs_teen, needs.first)
        }

        BandNeeded.General -> {
            stringResource(R.string.channels_refused_needs_general, ChannelNumbers.all.first, needs.first - 1)
        }
    }
