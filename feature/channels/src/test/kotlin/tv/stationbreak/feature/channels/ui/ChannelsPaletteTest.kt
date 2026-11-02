// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every text colour the channel screen draws, against the Ink it is drawn on, by the WCAG 2.x formula. */
class ChannelsPaletteTest {
    private fun ratio(
        a: Color,
        b: Color,
    ): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun everyTextColourReadsOnItsBackground() {
        ChannelsPalette.pairs.forEach { (what, fg, bg) ->
            val r = ratio(fg, bg)
            assertTrue("$what is $r, needs 4.5", r >= 4.5)
        }
    }

    @Test
    fun theDimmedRowIsStillDimmerThanTheRest() {
        val dimmed = ratio(ChannelsPalette.dimmed, ChannelsPalette.ground)
        assertTrue(dimmed < ratio(ChannelsPalette.text, ChannelsPalette.ground))
    }
}
