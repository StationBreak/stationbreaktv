// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The locked focus treatment (Look, 2026-11-04): widths, colours and the two scales. */
class BroadcastFocusTest {
    @Test
    fun tokensMatchTheLockedTreatment() {
        assertEquals(BroadcastColors.Accent, BroadcastFocus.fill)
        assertEquals(BroadcastColors.Ink, BroadcastFocus.content)
        assertEquals(BroadcastColors.Cream, BroadcastFocus.ring)
        assertEquals(BroadcastColors.Ink, BroadcastFocus.gap)
        assertEquals(3.dp, BroadcastFocus.ringWidth)
        assertEquals(2.dp, BroadcastFocus.ringGap)
        assertEquals(1f, BroadcastFocus.CELL_SCALE)
        assertEquals(1.04f, BroadcastFocus.BUTTON_SCALE)
        assertEquals(BroadcastColors.Ground, BroadcastFocus.restingOutline)
        assertEquals(2.dp, BroadcastFocus.restingOutlineWidth)
        assertEquals(BroadcastColors.Cream, BroadcastFocus.restingContent)
    }
}
