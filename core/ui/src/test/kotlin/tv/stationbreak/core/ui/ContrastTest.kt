// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Test

/**
 * The dark theme's colours against WCAG: 3:1 for the focus ring and icons, 4.5:1 for text (spec 05, Adorabelle).
 * [BroadcastFocus]'s ring is checked against the page. It is drawn outside the Accent fill, across an Ink gap, so it
 * is not a neighbour of the fill (Cream on Accent is under 3:1).
 */
class ContrastTest {
    private val colors = StationBreakDarkColors

    private fun assertAtLeast(
        minimum: Double,
        a: Color,
        b: Color,
        what: String,
    ) = assertContrastAtLeast(minimum, a, b, what)

    @Test
    fun theBroadcastFocusRingShowsAgainstThePage() {
        assertAtLeast(3.0, BroadcastFocus.ring, BroadcastFocus.gap, "ring on the page")
        assertAtLeast(4.5, BroadcastFocus.content, BroadcastFocus.fill, "focused label on the fill")
        assertAtLeast(3.0, BroadcastFocus.restingOutline, BroadcastFocus.gap, "resting outline on the page")
    }

    @Test
    fun inkOnFocusFillIsReadable() {
        assertAtLeast(4.5, BrandColors.Ink, BrandColors.Orange, "ink label on the orange focus fill")
        assertAtLeast(4.5, colors.onPrimary, colors.primary, "onPrimary on primary")
    }

    @Test
    fun creamOnGroundIsReadable() {
        assertAtLeast(4.5, colors.onSurface, colors.surface, "cream on the ground")
    }

    @Test
    fun greyOnGroundIsReadable() {
        assertAtLeast(4.5, colors.onSurfaceVariant, colors.surface, "grey on the ground")
    }

    @Test
    fun focusRingStandsOut() {
        assertAtLeast(3.0, colors.border, colors.surface, "ring on the ground")
        assertAtLeast(3.0, colors.border, colors.surfaceVariant, "ring on the panel")
    }

    @Test
    fun restingEdgeShowsOnTheGround() {
        assertAtLeast(3.0, colors.borderVariant, BrandColors.Ink, "resting edge on ink")
        assertAtLeast(3.0, colors.borderVariant, BrandColors.ScreenLiftTop, "resting edge on the lift's lightest point")
    }

    @Test
    fun errorIsReadable() {
        assertAtLeast(4.5, colors.error, colors.surface, "error on the ground")
    }

    @Test
    fun theDrawnRowRingShowsAgainstInk() {
        // The channel screen draws its focused row's ring itself, on the broadcast ground.
        assertAtLeast(3.0, colors.primary, BroadcastColors.Ink, "drawn ring on Ink")
    }

    @Test
    fun textReadsOnItsBackground() {
        assertAtLeast(4.5, colors.onSurface, colors.surface, "text on the page")
        assertAtLeast(4.5, colors.onSurfaceVariant, colors.surface, "details line on the page")
        assertAtLeast(4.5, colors.onSurface, colors.surfaceVariant, "typed text on the field fill")
        assertAtLeast(4.5, colors.onSurfaceVariant, colors.surfaceVariant, "full-strength hint on the field fill")
    }

    @Test
    fun theHintAtItsAlphaStillReadsOnTheFieldFill() {
        val hint = colors.onSurfaceVariant.copy(alpha = HINT_ALPHA).compositeOver(colors.surfaceVariant)
        assertAtLeast(4.5, hint, colors.surfaceVariant, "hint")
    }
}
