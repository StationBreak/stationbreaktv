// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Test

/** The colours a panel drawn over the guide takes from it: the dark default reads on its ground, as before. */
class PanelColorsTest {
    @Test
    fun theDarkDefaultKeepsTheScreensOwnInkAndCream() {
        assertEquals(BroadcastColors.Ink, PanelColors.Dark.ground)
        assertEquals(BroadcastColors.Cream, PanelColors.Dark.text)
        assertEquals(StationBreakDarkColors.onSurfaceVariant, PanelColors.Dark.secondary)
        assertEquals(StationBreakDarkColors.primary, PanelColors.Dark.ring)
    }

    @Test
    fun theDarkDefaultReadsOnItsGround() {
        val p = PanelColors.Dark
        assertContrastAtLeast(4.5, p.text.compositeOver(p.ground), p.ground, "text")
        assertContrastAtLeast(4.5, p.secondary.compositeOver(p.ground), p.ground, "secondary")
        assertContrastAtLeast(3.0, p.ring, p.ground, "ring")
    }
}
