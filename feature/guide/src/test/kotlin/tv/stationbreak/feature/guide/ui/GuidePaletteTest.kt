// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.ui.GuideColors

/** The guide's own theme choice and focus geometry. The colour tokens' tests are in core:ui (`GuideColorsTest`). */
class GuidePaletteTest {
    @Test
    fun theSavedChoiceIsDarkUnlessItIsLight() {
        assertEquals(GuideTheme.Dark, GuideTheme.decode(null))
        assertEquals(GuideTheme.Dark, GuideTheme.decode(""))
        assertEquals(GuideTheme.Dark, GuideTheme.decode("system"))
        assertEquals(GuideTheme.Dark, GuideTheme.decode("Dark"))
        assertEquals(GuideTheme.Light, GuideTheme.decode(GuideTheme.encode(GuideTheme.Light)))
        assertEquals(GuideColors.Dark, GuideTheme.Dark.colors)
        assertEquals(GuideColors.Light, GuideTheme.Light.colors)
    }

    @Test
    fun focusIsAFillChangeOnly() {
        assertEquals(1f, CONTENT_SCALE)
        assertEquals(0f, GuideFocusExtendX.value)
        assertEquals(0f, GuideFocusExtendY.value)
        assertTrue("nothing in the grid is rounder than 2 dp", GuideCardRadius.value <= 2f)
    }

    @Test
    fun theFocusBoundsStillGrowByAnExtent() {
        assertEquals(1f, CONTENT_SCALE)
        val inset = 4f
        val resting =
            guideFocusBounds(100f, 40f, FocusExtent(inset = inset, extendX = 4f, extendY = 2f, amount = 0f))
        assertEquals(inset, resting.left)
        assertEquals(100f - inset, resting.right)
        assertEquals(inset, resting.top)
        assertEquals(40f - inset, resting.bottom)
        val focused =
            guideFocusBounds(100f, 40f, FocusExtent(inset = inset, extendX = 4f, extendY = 2f, amount = 1f))
        assertEquals(0f, focused.left)
        assertEquals(100f, focused.right)
        assertEquals(2f, focused.top)
        assertEquals(38f, focused.bottom)
    }
}
