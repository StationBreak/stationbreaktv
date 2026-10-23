// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue

/** WCAG contrast ratio between two colours, shared by the contrast tests. */
internal fun contrastRatio(
    a: Color,
    b: Color,
): Double {
    val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
    return (light + 0.05) / (dark + 0.05)
}

internal fun assertContrastAtLeast(
    minimum: Double,
    a: Color,
    b: Color,
    what: String,
) = assertTrue("$what is ${contrastRatio(a, b)}, needs $minimum", contrastRatio(a, b) >= minimum)
