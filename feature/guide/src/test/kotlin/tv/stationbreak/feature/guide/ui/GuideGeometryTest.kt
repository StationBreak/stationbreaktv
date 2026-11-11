// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.ui.ScreenFit
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.feature.guide.GUIDE_VIEWPORT_SPAN
import tv.stationbreak.feature.guide.GUIDE_VISIBLE_ROWS
import tv.stationbreak.feature.guide.SLOT_MS

/** The guide lays its panel and grid out around the hole live TV gives it; it does not work out where that is. */
class GuideGeometryTest {
    private val screen = DpSize(960.dp, 540.dp)

    @Test
    fun thePanelFillsTheBandBesideTheCornerPicture() {
        val geometry = GuideGeometry(screen, ScreenLayout.pictureRect(ScreenLayout.safeArea(screen)))
        // Safe area from 48 dp; the picture from 497.28 dp, 233.28 dp tall from 27 dp; 12 dp between panel and picture.
        assertEquals(437.28f, geometry.panelWidth.value, EPS)
        assertEquals(233.28f, geometry.topHeight.value, EPS)
    }

    @Test
    fun theViewportIsTheSafeWidthMinusTheWiderColumn() {
        val geometry = GuideGeometry(screen, ScreenLayout.pictureRect(ScreenLayout.safeArea(screen)))
        // Safe area 48 dp in from each side: 864 dp wide; the channel column takes 84 dp of it.
        assertEquals(864f - 84f, geometry.viewportWidth.value, EPS)
        assertEquals(84f, ChannelColumnWidth.value, EPS)
        assertEquals(GUIDE_VIEWPORT_SPAN.inWholeMilliseconds, geometry.spanMs)
    }

    @Test
    fun theGridShowsTwoHoursAt6Point5DpAMinute() {
        val geometry = GuideGeometry(screen, ScreenLayout.pictureRect(ScreenLayout.safeArea(screen)))
        assertEquals(120L * 60_000L, geometry.spanMs)
        // 780 dp over 120 minutes; each half-hour tick (four of them) is 195 dp from the last.
        assertEquals(6.5f, geometry.widthOf(60_000L).value, EPS)
        assertEquals(195f, geometry.widthOf(SLOT_MS).value, EPS)
        assertEquals(geometry.viewportWidth.value, geometry.widthOf(geometry.spanMs).value, EPS)
    }

    @Test
    fun theLayoutFollowsTheHoleItIsGiven() {
        // Live is the one that says where the picture is: a hole elsewhere moves the panel's edge and the grid's top.
        val hole = DpRect(left = 600.dp, top = 27.dp, right = 912.dp, bottom = 200.dp)
        val geometry = GuideGeometry(screen, hole)
        assertEquals(600f - 48f - 12f, geometry.panelWidth.value, EPS)
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

    @Test
    fun rowsShareTheirBandExactly() {
        assertEquals(RowsBandHeight, RowHeight * GUIDE_VISIBLE_ROWS)
    }

    @Test
    fun fiveFortyDpRowsWithOneDpBetweenThemMakeTheBand() {
        assertEquals(5, GUIDE_VISIBLE_ROWS)
        assertEquals(5 * 40f + 4 * 1f, RowsBandHeight.value, EPS)
        // A cell is its row's part less the 0.5 dp inset on each side: 40 dp to within the 0.2 dp the end gaps take.
        assertEquals(40f, RowHeight.value - 1f, 0.25f)
    }

    @Test
    fun theWholeColumnFitsTheSafeAreaInEveryFit() {
        for (fit in ScreenFit.entries) {
            val geometry = geometryFor(fit)
            val column = geometry.topHeight + TopGap + ClockHeight + RowsBandHeight + HintGap + HintHeight
            val safeHeight = geometry.safe.bottom - geometry.safe.top
            assertTrue("$fit: column $column over $safeHeight", column <= safeHeight)
        }
    }

    @Test
    fun theBleedIsHowFarTheScreenEdgeIsPastTheSafeArea() {
        // 5% of 960 dp at Safe, 1.5% at Edge: the surfaces run on by that much at each side.
        assertEquals(48f, geometryFor(ScreenFit.Safe).bleed.value, EPS)
        assertEquals(14.4f, geometryFor(ScreenFit.Edge).bleed.value, EPS)
    }

    @Test
    fun theTimeScaleDoesNotChangeWithTheBleed() {
        // The time columns are the safe width less the channel column: the bleed adds surface, not minutes.
        val geometry = geometryFor(ScreenFit.Edge)
        assertEquals(931.2f - 84f, geometry.viewportWidth.value, EPS)
        assertEquals(geometry.viewportWidth.value, geometry.widthOf(geometry.spanMs).value, EPS)
    }

    @Test
    fun theHoleStaysInTheCornerOfTheSafeAreaInEveryFit() {
        // Live puts the picture at pictureRect of the fit's safe area; the guide takes that hole in either fit.
        for (fit in ScreenFit.entries) {
            val geometry = geometryFor(fit)
            assertEquals(ScreenLayout.safeArea(screen, fit), geometry.safe)
        }
    }

    private fun geometryFor(fit: ScreenFit): GuideGeometry {
        val hole = ScreenLayout.pictureRect(ScreenLayout.safeArea(screen, fit))
        return GuideGeometry(screen, hole, fit)
    }

    private companion object {
        const val EPS = 0.01f
    }
}
