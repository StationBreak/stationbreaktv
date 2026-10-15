// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dark theme's colours against WCAG: 3:1 for the focus ring and icons, 4.5:1 for text (spec 05, Adorabelle). */
class ContrastTest {
    private val colors = StationBreakDarkColors

    private fun ratio(
        a: Color,
        b: Color,
    ): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun assertAtLeast(
        minimum: Double,
        a: Color,
        b: Color,
        what: String,
    ) = assertTrue("$what is ${ratio(a, b)}, needs $minimum", ratio(a, b) >= minimum)

    @Test
    fun theButtonRingShowsAgainstThePageAndTheInvertedFocusedFill() {
        // A focused button fills with onSurface; the ring sits between that fill and the page.
        assertAtLeast(3.0, colors.border, colors.surface, "ring on the page")
        assertAtLeast(3.0, colors.border, colors.onSurface, "ring on the focused fill")
    }

    @Test
    fun theFieldRingShowsAgainstThePageAndTheFieldFill() {
        assertAtLeast(3.0, colors.primary, colors.surface, "field ring on the page")
        assertAtLeast(3.0, colors.primary, colors.surfaceVariant, "field ring on the field fill")
    }

    @Test
    fun textReadsOnItsBackground() {
        assertAtLeast(4.5, colors.onSurface, colors.surface, "text on the page")
        assertAtLeast(4.5, colors.onSurfaceVariant, colors.surface, "details line on the page")
        assertAtLeast(4.5, colors.onSurface, colors.surfaceVariant, "typed text on the field fill")
        assertAtLeast(4.5, colors.onSurfaceVariant, colors.surfaceVariant, "full-strength hint on the field fill")
    }

    @Test
    fun theErrorIconShowsOnThePage() {
        assertAtLeast(3.0, colors.error, colors.surface, "error colour on the page")
    }

    @Test
    fun theHintAtItsAlphaStillReadsOnTheFieldFill() {
        val hint = colors.onSurfaceVariant.copy(alpha = HINT_ALPHA).compositeOver(colors.surfaceVariant)
        assertAtLeast(4.5, hint, colors.surfaceVariant, "hint")
    }
}
