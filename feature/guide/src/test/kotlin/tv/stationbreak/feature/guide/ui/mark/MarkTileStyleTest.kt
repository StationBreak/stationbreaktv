// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.guide.MarkBand

/** The band tile colours from the design partner's ChannelMarks spec (spec 27). */
class MarkTileStyleTest {
    @Test
    fun everyBandTileKeepsInitialsContrast() {
        for (band in MarkBand.entries) {
            val s = markTileStyle(band)
            assertTrue("$band initials on tile is ${contrast(s.initials, s.tile)}", contrast(s.initials, s.tile) >= 4.5)
        }
        val ink = Color(0xFF1C1B19)
        val cream = Color(0xFFF3EEE3)
        val accent = Color(0xFFE8642C)
        val grey = Color(0xFF5A554C)
        for (band in listOf(MarkBand.Kids, MarkBand.General, MarkBand.Custom)) {
            assertEquals(MarkTileStyle(tile = cream, border = ink, initials = ink, tick = accent), markTileStyle(band))
        }
        for (band in listOf(MarkBand.Drama, MarkBand.Genre, MarkBand.World)) {
            assertEquals(MarkTileStyle(tile = ink, border = grey, initials = cream, tick = accent), markTileStyle(band))
        }
        val premium = MarkTileStyle(tile = ink, border = ink, initials = accent, tick = cream)
        assertEquals(premium, markTileStyle(MarkBand.Premium))
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }
}
