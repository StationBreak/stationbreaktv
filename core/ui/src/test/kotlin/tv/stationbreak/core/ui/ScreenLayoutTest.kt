// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenLayoutTest {
    @Test
    fun pictureIsTopRightFortyEightPercentSixteenByNine() {
        val safeArea = DpRect(left = 48.dp, top = 27.dp, right = (48 + 864).dp, bottom = (27 + 486).dp)
        val picture = ScreenLayout.pictureRect(safeArea)
        assertEquals(48f + 864f - 414.72f, picture.left.value, EPS)
        assertEquals(27f, picture.top.value, EPS)
        assertEquals(414.72f, picture.width.value, EPS)
        assertEquals(233.28f, picture.height.value, EPS)
        assertEquals(safeArea.right.value, picture.right.value, EPS)
    }

    @Test
    fun safeAreaIsFivePercentInFromEveryEdge() {
        val safe = ScreenLayout.safeArea(DpSize(960.dp, 540.dp))
        assertEquals(48f, safe.left.value, EPS)
        assertEquals(27f, safe.top.value, EPS)
        assertEquals(912f, safe.right.value, EPS)
        assertEquals(513f, safe.bottom.value, EPS)
    }

    @Test
    fun edgeFitKeepsOnePointFivePercent() {
        val safe = ScreenLayout.safeArea(DpSize(960.dp, 540.dp), ScreenFit.Edge)
        assertEquals(14.4f, safe.left.value, EPS)
        assertEquals(8.1f, safe.top.value, EPS)
        assertEquals(945.6f, safe.right.value, EPS)
        assertEquals(531.9f, safe.bottom.value, EPS)
        // The picture is the same share of the (wider) safe area, in its top-right corner.
        val picture = ScreenLayout.pictureRect(safe)
        assertEquals(931.2f * 0.48f, picture.width.value, EPS)
        assertEquals(safe.right.value, picture.right.value, EPS)
        assertEquals(safe.top.value, picture.top.value, EPS)
    }

    @Test
    fun safeIsTheDefaultFit() {
        val screen = DpSize(960.dp, 540.dp)
        assertEquals(ScreenLayout.safeArea(screen, ScreenFit.Safe), ScreenLayout.safeArea(screen))
    }

    @Test
    fun theSavedFitIsSafeUnlessItIsEdge() {
        assertEquals(ScreenFit.Safe, ScreenFit.decode(null))
        assertEquals(ScreenFit.Safe, ScreenFit.decode(""))
        assertEquals(ScreenFit.Safe, ScreenFit.decode("edge"))
        assertEquals(ScreenFit.Safe, ScreenFit.decode("Safe"))
        assertEquals(ScreenFit.Edge, ScreenFit.decode(ScreenFit.encode(ScreenFit.Edge)))
        assertEquals(0.05f, ScreenFit.Safe.overscan, 0f)
        assertEquals(0.015f, ScreenFit.Edge.overscan, 0f)
    }

    @Test
    fun theHoleAndThePictureComeFromTheSameSafeArea() {
        // The guide's hole and the corner picture are both pictureRect of safeArea: one source, so they cannot drift.
        val picture = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(960.dp, 540.dp)))
        assertEquals(912f - 414.72f, picture.left.value, EPS)
        assertEquals(27f, picture.top.value, EPS)
        assertEquals(27f + 233.28f, picture.bottom.value, EPS)
    }

    private companion object {
        const val EPS = 0.01f
    }
}
