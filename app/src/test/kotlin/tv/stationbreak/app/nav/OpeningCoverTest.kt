// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** Back on the opening: consumed and inert until launch routing has a destination, then it skips (#1084). */
class OpeningCoverTest {
    private var skips = 0

    private fun cover(launchReady: Boolean) = OpeningCover(active = true, skip = { skips++ }, launchReady = launchReady)

    @Test
    fun backBeforeARoutingDestinationDoesNothing() {
        cover(launchReady = false).onBack()
        assertEquals(0, skips)
    }

    @Test
    fun backAfterARoutingDestinationSkipsTheOpening() {
        cover(launchReady = true).onBack()
        assertEquals(1, skips)
    }

    @Test
    fun anyOtherKeySkipsEitherWay() {
        cover(launchReady = false).onKey()
        cover(launchReady = true).onKey()
        assertEquals(2, skips)
    }
}
