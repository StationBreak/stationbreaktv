// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.runtime.Composable
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.ChannelTag

/** The "up next" banner when known, else the channel's own strip (design: "the card shows the channel only"). */
@Composable
internal fun CardBanner(
    upNext: Banner?,
    channel: ChannelTag?,
) {
    if (upNext != null) {
        CableBanner(upNext)
    } else if (channel != null) {
        ChannelBanner(channel)
    }
}
