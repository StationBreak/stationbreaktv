// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonBorder
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.MaterialTheme

/**
 * The one focus treatment (spec 05, Adorabelle's 10-foot defaults): a scale-up plus an outline of 3 dp or more, the
 * same on every focusable thing, so the user can always see where the D-pad is. A button inverts its fill when focused,
 * so its ring is the theme's mid-tone `border` colour; a surface that keeps its fill (the address field) uses
 * `primary`.
 * `ContrastTest` pins both at 3:1 or better against everything they touch.
 */
object FocusStyle {
    private val OutlineWidth = 3.dp
    private const val BUTTON_SCALE = 1.08f

    // A full-width surface grows far more in pixels than a button does, so it scales less.
    private const val SURFACE_SCALE = 1.02f

    @Composable
    fun buttonBorder(): ButtonBorder = ButtonDefaults.border(focusedBorder = outline(MaterialTheme.colorScheme.border))

    @Composable
    fun buttonScale(): ButtonScale = ButtonDefaults.scale(focusedScale = BUTTON_SCALE)

    @Composable
    fun surfaceBorder(): ClickableSurfaceBorder =
        ClickableSurfaceDefaults.border(focusedBorder = outline(MaterialTheme.colorScheme.primary))

    @Composable
    fun surfaceScale(): ClickableSurfaceScale = ClickableSurfaceDefaults.scale(focusedScale = SURFACE_SCALE)

    private fun outline(color: Color) = Border(BorderStroke(OutlineWidth, color))
}
