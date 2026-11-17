// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Down and Up scroll the program info's overview, only while it has more than fits (#703). */
class OverviewKeyTest {
    private val info = OverlayUp(info = true)

    @Test
    fun downAndUpScrollALongOverview() {
        assertEquals(true, overviewScrollForward(Key.DirectionDown, info, overflows = true))
        assertEquals(false, overviewScrollForward(Key.DirectionUp, info, overflows = true))
    }

    @Test
    fun aShortOverviewLeavesTheKeysToTheFlip() {
        assertNull(overviewScrollForward(Key.DirectionDown, info, overflows = false))
        assertNull(overviewScrollForward(Key.DirectionUp, info, overflows = false))
    }

    @Test
    fun onlyTheInfoOverlayAndOnlyTheDpadScroll() {
        assertNull(overviewScrollForward(Key.DirectionDown, OverlayUp.None, overflows = true))
        assertNull(overviewScrollForward(Key.DirectionDown, OverlayUp(info = true, guide = true), overflows = true))
        assertNull(overviewScrollForward(Key.ChannelDown, info, overflows = true))
        // Back still closes the panel in one press.
        assertNull(overviewScrollForward(Key.Back, info, overflows = true))
    }
}
