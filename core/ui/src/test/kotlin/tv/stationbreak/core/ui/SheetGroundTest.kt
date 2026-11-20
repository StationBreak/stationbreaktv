// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a sheet's text keeps clear of the screen's edges at 960x540, on either edge, at both screen fits. */
class SheetGroundTest {
    private val screen = DpSize(960.dp, 540.dp)
    private val safe = ScreenLayout.safeArea(screen)
    private val edge = ScreenLayout.safeArea(screen, ScreenFit.Edge)

    @Test
    fun aLeftSheetsTextKeepsToTheSafeArea_andItsGroundBleeds() {
        val ground = sheetGround(screen, safe, SheetMeasures.InnerPadding, onRight = false)
        assertEquals(48f, ground.outer.value, 0.01f)
        assertEquals(27f, ground.top.value, 0.01f)
        assertEquals(27f, ground.bottom.value, 0.01f)
        // Edge keeps 1.5% (14.4 dp): a left sheet's text goes as far out as the viewer chose.
        assertEquals(14.4f, sheetGround(screen, edge, SheetMeasures.InnerPadding, onRight = false).outer.value, 0.01f)
    }

    @Test
    fun aRightSheetKeepsTheSafeArea_orItsOwnGutterAtEdge() {
        val ground = sheetGround(screen, safe, 16.dp, onRight = true)
        assertEquals(48f, ground.outer.value, 0.01f)
        // The properties sheet: 364 dp of text column plus its outer inset is 412 dp of ground at Safe, as designed.
        assertEquals(412f, (364.dp + ground.outer).value, 0.01f)
        // 1.5% of 960 is 14.4 dp, under the gutter: the text keeps 16 dp, and the sheet is 380 dp as designed.
        val atEdge = sheetGround(screen, edge, 16.dp, onRight = true)
        assertEquals(16f, atEdge.outer.value, 0.01f)
        assertEquals(380f, (364.dp + atEdge.outer).value, 0.01f)
    }

    @Test
    fun theSharedMeasures() {
        assertEquals(36f, SheetMeasures.RowMinHeight.value, 0f)
        assertEquals(380f, SheetMeasures.Width.value, 0f)
        assertEquals(0.55f, SHEET_SCRIM_ALPHA, 0f)
    }
}
