// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideLayoutTest {
    @Test
    fun pictureIsTopRightFortyPercentSixteenByNine() {
        val safeArea = DpRect(left = 48.dp, top = 27.dp, right = (48 + 864).dp, bottom = (27 + 486).dp)
        val picture = GuideLayout.pictureRect(safeArea)
        assertEquals(48f + 864f - 345.6f, picture.left.value, EPS)
        assertEquals(27f, picture.top.value, EPS)
        assertEquals(345.6f, picture.width.value, EPS)
        assertEquals(194.4f, picture.height.value, EPS)
        assertEquals(safeArea.right.value, picture.right.value, EPS)
    }

    @Test
    fun safeAreaIsFivePercentInFromEveryEdge() {
        val safe = GuideLayout.safeArea(DpSize(960.dp, 540.dp))
        assertEquals(48f, safe.left.value, EPS)
        assertEquals(27f, safe.top.value, EPS)
        assertEquals(912f, safe.right.value, EPS)
        assertEquals(513f, safe.bottom.value, EPS)
    }

    @Test
    fun theHoleAndThePictureComeFromTheSameSafeArea() {
        // The guide's hole and the corner picture are both pictureRect of safeArea: one source, so they cannot drift.
        val picture = GuideLayout.pictureRect(GuideLayout.safeArea(DpSize(960.dp, 540.dp)))
        assertEquals(912f - 345.6f, picture.left.value, EPS)
        assertEquals(27f, picture.top.value, EPS)
        assertEquals(27f + 194.4f, picture.bottom.value, EPS)
    }

    private companion object {
        const val EPS = 0.01f
    }
}
