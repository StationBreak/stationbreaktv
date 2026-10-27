// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.stationbreak.core.ui.ScreenLayout

/** The guide lays its panel and grid out around the hole live TV gives it; it does not work out where that is. */
class GuideGeometryTest {
    private val screen = DpSize(960.dp, 540.dp)

    @Test
    fun thePanelFillsTheBandBesideTheCornerPicture() {
        val geometry = GuideGeometry(screen, ScreenLayout.pictureRect(ScreenLayout.safeArea(screen)))
        // Safe area from 48 dp; the picture from 566.4 dp, 194.4 dp tall from 27 dp; 16 dp between panel and picture.
        assertEquals(502.4f, geometry.panelWidth.value, EPS)
        assertEquals(194.4f, geometry.topHeight.value, EPS)
    }

    @Test
    fun theLayoutFollowsTheHoleItIsGiven() {
        // Live is the one that says where the picture is: a hole elsewhere moves the panel's edge and the grid's top.
        val hole = DpRect(left = 600.dp, top = 27.dp, right = 912.dp, bottom = 200.dp)
        val geometry = GuideGeometry(screen, hole)
        assertEquals(600f - 48f - 16f, geometry.panelWidth.value, EPS)
        assertEquals(200f - 27f, geometry.topHeight.value, EPS)
    }

    @Test
    fun aHoleThatIsNotInTheTopRightCornerIsRefused() {
        // The panel takes the band left of the hole and the grid starts below it: that only works for a hole in the
        // top-right corner of the safe area.
        val low = DpRect(left = 566.dp, top = 100.dp, right = 912.dp, bottom = 300.dp)
        assertThrows(IllegalArgumentException::class.java) { GuideGeometry(screen, low) }
        val left = DpRect(left = 48.dp, top = 27.dp, right = 400.dp, bottom = 221.dp)
        assertThrows(IllegalArgumentException::class.java) { GuideGeometry(screen, left) }
    }

    private companion object {
        const val EPS = 0.01f
    }
}
