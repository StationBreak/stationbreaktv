// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonBorder
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.MaterialTheme

/**
 * The setup focus treatment (onboarding, spec 05): colour, not scale. A focused button fills orange with ink text; a
 * focused surface (a text field, a collection row) keeps its fill and gets a 4 dp orange ring. Nothing grows, so a
 * focused row never pushes into its neighbour. The guide paints its own focus; the channel screens draw it with
 * [drawnFocus]. `ContrastTest` pins the ring and the label at 3:1 and 4.5:1 or better against what they touch.
 */
object FocusStyle {
    internal val OutlineWidth = 3.dp
    internal val OutlineRadius = 4.dp
    private val RingWidth = 4.dp
    private val EmphasisWidth = 1.dp

    // [drawnFocus] still grows a full-width row a little; setup's own focus does not.
    internal const val SURFACE_SCALE = 1.02f

    /**
     * A button's colours: panel at rest, orange with ink text when focused. The main action of a screen differs only
     * by its orange 1 dp border at rest, which is a border, not a colour: see [buttonBorder].
     */
    @Composable
    fun buttonColors(): ButtonColors {
        val colors = MaterialTheme.colorScheme
        return ButtonDefaults.colors(
            containerColor = colors.surfaceVariant,
            contentColor = colors.onSurface,
            focusedContainerColor = colors.primary,
            focusedContentColor = colors.onPrimary,
            pressedContainerColor = colors.primary,
            pressedContentColor = colors.onPrimary,
        )
    }

    @Composable
    fun buttonBorder(emphasis: Boolean = false): ButtonBorder {
        val ring = MaterialTheme.colorScheme.border
        return ButtonDefaults.border(
            border = if (emphasis) Border(BorderStroke(EmphasisWidth, ring)) else Border.None,
            focusedBorder = Border.None,
        )
    }

    @Composable
    fun buttonScale(): ButtonScale = ButtonDefaults.scale(focusedScale = 1f)

    @Composable
    fun surfaceBorder(): ClickableSurfaceBorder =
        ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(RingWidth, MaterialTheme.colorScheme.border)),
        )

    @Composable
    fun surfaceScale(): ClickableSurfaceScale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
}

/**
 * The surface treatment for a row whose focus a screen draws itself from its state (the channel screen's model-driven
 * focus, ADR-0008 addendum): the same outline (`primary`, 3 dp) and scale as [FocusStyle.surfaceBorder] and
 * [FocusStyle.surfaceScale], without a Compose focus target. A screen on a ground other than the theme's passes its
 * own [ring]. A row inside the guide family uses [panelFocus] instead (the panel's ring, no scale).
 */
@Composable
fun Modifier.drawnFocus(
    focused: Boolean,
    ring: Color = MaterialTheme.colorScheme.primary,
    scaled: Boolean = true,
): Modifier {
    if (!focused) return this
    val grown =
        if (scaled) {
            graphicsLayer {
                scaleX = FocusStyle.SURFACE_SCALE
                scaleY = FocusStyle.SURFACE_SCALE
            }
        } else {
            this
        }
    return grown.border(FocusStyle.OutlineWidth, ring, RoundedCornerShape(FocusStyle.OutlineRadius))
}

/**
 * A row's focus inside the guide family (the menu sheet, the channel screens), the guide's way: the guide's focus
 * colour as a ring ([LocalPanelColors] `ring`) and no scale, which would push a row's edge into its neighbour's. Drawn
 * from the screen's own state, as these rows have no Compose focus target.
 */
@Composable
fun Modifier.panelFocus(focused: Boolean): Modifier {
    val ring = LocalPanelColors.current.ring
    return drawnFocus(focused, ring = ring, scaled = false)
}
