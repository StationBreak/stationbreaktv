// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import org.junit.Test

/** The broadcast palette (spec 14) against WCAG: 4.5:1 for the banner and slate text. */
class BroadcastStyleTest {
    @Test
    fun creamOnInkAndInkOnGroundPassContrast() {
        assertContrastAtLeast(4.5, BroadcastColors.Cream, BroadcastColors.Ink, "banner cream on ink")
        assertContrastAtLeast(4.5, BroadcastColors.Ink, BroadcastColors.Ground, "ink on ground")
    }
}
