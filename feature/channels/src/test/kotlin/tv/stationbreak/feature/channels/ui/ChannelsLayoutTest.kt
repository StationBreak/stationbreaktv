// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.ui.ScreenLayout

/** The Channels screen's room at 960x540: the actions panel's bound (the properties sheet's is core:ui's). */
class ChannelsLayoutTest {
    private val screen = DpSize(960.dp, 540.dp)
    private val safe = ScreenLayout.safeArea(screen)

    @Test
    fun `the actions panel stops at the bottom of the safe area`() {
        val hole = ScreenLayout.pictureRect(safe)
        val top = hole.bottom + 16.dp
        // Hole: 864 dp wide safe area * 0.48 = 414.72 wide, 233.28 tall, from 27 dp down: bottom 260.28,
        // so the panel starts at 276.28.
        assertEquals(276.28f, top.value, 0.01f)
        assertEquals(236.72f, panelMaxHeight(top, safe.bottom).value, 0.01f)
    }

    @Test
    fun `a panel that starts below the safe area has no height`() {
        assertEquals(0.dp, panelMaxHeight(520.dp, 513.dp))
    }
}
