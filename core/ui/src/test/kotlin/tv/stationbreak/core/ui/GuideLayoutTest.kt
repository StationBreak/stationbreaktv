// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.unit.DpRect
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

    private companion object {
        const val EPS = 0.01f
    }
}
