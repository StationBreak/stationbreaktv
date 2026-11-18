// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import tv.stationbreak.core.ui.BroadcastType

/**
 * The brand face (Ranchers, [BroadcastType.Voice]) for Stan's glyphs: the Zs and the question mark. Each character is
 * measured once, at [REFERENCE_PX] (large, so scaling down stays sharp), and kept; the painter scales the
 * drawing, so no frame lays out text.
 */
internal class StanGlyphs(
    private val measurer: TextMeasurer,
    density: Density,
) {
    private val style =
        TextStyle(
            fontFamily = BroadcastType.Voice,
            fontSize = with(density) { REFERENCE_PX.toSp() },
        )
    private val layouts = HashMap<String, TextLayoutResult>()

    fun layout(ch: String): TextLayoutResult = layouts.getOrPut(ch) { measurer.measure(ch, style) }

    companion object {
        /** The size, in pixels, that glyphs are measured at. */
        const val REFERENCE_PX = 200f
    }
}
