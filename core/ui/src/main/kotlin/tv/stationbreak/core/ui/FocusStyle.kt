// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
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
 * same on every focusable thing, so the user can always see where the D-pad is.
 */
object FocusStyle {
    private val OutlineWidth = 3.dp
    private const val FOCUSED_SCALE = 1.08f

    @Composable
    fun buttonBorder(): ButtonBorder = ButtonDefaults.border(focusedBorder = outline())

    @Composable
    fun buttonScale(): ButtonScale = ButtonDefaults.scale(focusedScale = FOCUSED_SCALE)

    @Composable
    fun surfaceBorder(): ClickableSurfaceBorder = ClickableSurfaceDefaults.border(focusedBorder = outline())

    @Composable
    fun surfaceScale(): ClickableSurfaceScale = ClickableSurfaceDefaults.scale(focusedScale = FOCUSED_SCALE)

    @Composable
    private fun outline() = Border(BorderStroke(OutlineWidth, MaterialTheme.colorScheme.primary))
}
