// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.ui.ScreenFit
import tv.stationbreak.core.ui.ScreenLayout

/** The Channels screen's room at 960x540: the actions panel's bound and the properties sheet's insets. */
class ChannelsLayoutTest {
    private val screen = DpSize(960.dp, 540.dp)
    private val safe = ScreenLayout.safeArea(screen)

    @Test
    fun `the actions panel stops at the bottom of the safe area`() {
        val hole = ScreenLayout.pictureRect(safe)
        val top = hole.bottom + 16.dp
        // Hole: 864 dp wide safe area * 0.48 = 414.72 wide, 233.28 tall, from 27 dp down: bottom 260.28, panel at 276.28.
        assertEquals(276.28f, top.value, 0.01f)
        assertEquals(236.72f, panelMaxHeight(top, safe.bottom).value, 0.01f)
    }

    @Test
    fun `a panel that starts below the safe area has no height`() {
        assertEquals(0.dp, panelMaxHeight(520.dp, 513.dp))
    }

    @Test
    fun `the sheet's text keeps to the safe area and its ground bleeds`() {
        val inset = sheetInsets(screen, safe, 380.dp, 16.dp)
        assertEquals(48f, inset.end.value, 0.01f)
        assertEquals(27f, inset.top.value, 0.01f)
        assertEquals(27f, inset.bottom.value, 0.01f)
        // 380 + 48 - 16: the text column is the width the design gave it (380 - 1 - 16 - 16 = 347, then and now).
        assertEquals(412f, inset.width.value, 0.01f)
        assertEquals(347f, inset.width.value - 1f - 16f - inset.end.value, 0.01f)
    }

    @Test
    fun `an edge-to-edge viewer keeps the design's own gutter and width`() {
        val edge = ScreenLayout.safeArea(screen, ScreenFit.Edge)
        val inset = sheetInsets(screen, edge, 380.dp, 16.dp)
        // 1.5% of 960 is 14.4 dp, under the gutter: the text keeps 16 dp, and the sheet is 380 dp as designed.
        assertEquals(16f, inset.end.value, 0.01f)
        assertEquals(380f, inset.width.value, 0.01f)
    }
}
