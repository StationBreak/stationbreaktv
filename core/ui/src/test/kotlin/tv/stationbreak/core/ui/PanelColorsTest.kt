// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Test

/** The default colours of a panel with no guide around it (inside the guide, the guide provides its own). */
class PanelColorsTest {
    @Test
    fun theDefaultIsWarmInkCreamMutedAndOrange() {
        assertEquals(Color(0xFF1C1B19), PanelColors.Dark.ground)
        assertEquals(Color(0xFFF3EEE3), PanelColors.Dark.text)
        assertEquals(Color(0xFFB3AB9D), PanelColors.Dark.secondary)
        assertEquals(Color(0xFFE8642C), PanelColors.Dark.ring)
        assertEquals(GuideColors.Dark.panel.ground, PanelColors.Dark.ground)
        assertEquals(GuideColors.Dark.sheet, PanelColors.Dark.sheet)
    }

    @Test
    fun theDefaultSheetReadsToo() {
        val p = PanelColors.Dark
        val s = p.sheet.surface
        assertContrastAtLeast(4.5, p.text.compositeOver(s), s, "text on the sheet")
        assertContrastAtLeast(4.5, p.secondary.compositeOver(s), s, "secondary on the sheet")
        assertContrastAtLeast(4.5, p.sheet.ratingBadge, s, "rating badge on the sheet")
    }

    // A section flag is DESIGN.md's grey in Dark, dark grey in Light, and reads on the sheet in both; key caps take the
    // guide's own stroke.
    @Test
    fun theFlagAndTheKeyCapFollowTheGuide() {
        assertEquals(Color(0xFFCFCBC2), PanelColors.Dark.flag)
        assertEquals(Color(0xFFCFCBC2), GuideColors.Dark.panel.flag)
        assertEquals(Color(0xFF5E5850), GuideColors.Light.panel.flag)
        for (guide in listOf(GuideColors.Dark, GuideColors.Light)) {
            val p = guide.panel
            assertEquals(guide.keyCapStroke, p.keyCapStroke)
            assertContrastAtLeast(4.5, p.flag, p.sheet.surface, "a flag on the sheet")
        }
    }

    @Test
    fun theDefaultReadsOnItsGround() {
        val p = PanelColors.Dark
        assertContrastAtLeast(4.5, p.text.compositeOver(p.ground), p.ground, "text")
        assertContrastAtLeast(4.5, p.secondary.compositeOver(p.ground), p.ground, "secondary")
        assertContrastAtLeast(3.0, p.ring, p.ground, "ring")
    }
}
