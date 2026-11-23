// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.PanelColors

/**
 * Every colour the channel screens draw, against the ground or sheet it is drawn on, by the WCAG 2.x formula, in both
 * guide themes and in the default a screen has with no guide around.
 */
class ChannelsPaletteTest {
    private val panels =
        listOf(
            "default" to PanelColors.Dark,
            "guide dark" to GuideColors.Dark.panel,
            "guide light" to GuideColors.Light.panel,
        )

    private fun ratio(
        a: Color,
        b: Color,
    ): Double {
        // Judged on the composite: a text colour may carry an alpha, a ground never does.
        val (hi, lo) = listOf(a.compositeOver(b).luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun everyTextColourReadsOnItsBackgroundInEveryTheme() {
        for ((theme, panel) in panels) {
            ChannelsPalette.pairs(panel).forEach { (what, fg, bg) ->
                val r = ratio(fg, bg)
                assertTrue("$what in $theme is $r, needs 4.5", r >= 4.5)
            }
        }
    }

    @Test
    fun theFocusRingShowsOnTheGroundAndOnTheSheetInEveryTheme() {
        for ((theme, panel) in panels) {
            val onGround = ratio(panel.ring, panel.ground)
            val onSheet = ratio(panel.ring, panel.sheet.surface)
            assertTrue("ring on the ground in $theme is $onGround, needs 3", onGround >= 3.0)
            assertTrue("ring on the sheet in $theme is $onSheet, needs 3", onSheet >= 3.0)
        }
    }

    // The band stripe and dot are shapes: 3:1 on the ground, in every theme.
    @Test
    fun theBandAccentsShowOnTheGroundInEveryTheme() {
        for ((theme, panel) in panels) {
            val b = panel.band
            val accents = listOf("tots" to b.tots, "kids" to b.kids, "teens" to b.teens, "adults" to b.adults)
            for ((band, color) in accents) {
                val r = ratio(color, panel.ground)
                assertTrue("$band in $theme is $r, needs 3", r >= 3.0)
            }
        }
    }

    @Test
    fun theDimmedRowIsStillDimmerThanTheRest() {
        for ((theme, panel) in panels) {
            val dimmed = ratio(ChannelsPalette.dimmed(panel), panel.ground)
            assertTrue("dimmed row in $theme", dimmed < ratio(panel.text, panel.ground))
        }
    }

    @Test
    fun theDefaultKeepsTheSheetTheDesignGave() {
        assertEquals(Color(0xFF262320), PanelColors.Dark.sheet.surface)
        assertEquals(Color(0xFF3D3832), PanelColors.Dark.sheet.line)
        assertEquals(Color(0xFFFFD166), PanelColors.Dark.sheet.ratingBadge)
    }
}
