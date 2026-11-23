// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.PlayKey
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/** #1108: the early lineup keeps each channel's floor, so Kids Mode can hide a channel below it. */
class EarlyAiringFloorTest {
    @Test
    fun theEarlyAiringCarriesTheChannelsOwnFloor() {
        val summary =
            ChannelSummary(
                ChannelId("custom:adult"),
                40,
                "Test Channel",
                ChannelStatus.On,
                ChannelKind.Custom,
                Instant.fromEpochMilliseconds(0),
                defaultNumber = 40,
                kid = false,
                optIn = false,
                playKey = PlayKey("test"),
                floor = 100,
            )
        val airing = summary.toEarlyAiring()
        assertEquals(100, airing.floor)
        assertEquals(40, airing.number)
    }
}
