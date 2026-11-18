// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import tv.stationbreak.core.ui.stan.StanPalette

private const val LIFT_RADIUS = .8f

/**
 * A ground's radial lift: [palette]'s lift colour at [center], falling off to its edge colour at [radius]. The one
 * home of the gradient, for Stan's stage ground and for [screenLift].
 */
internal fun liftBrush(
    palette: StanPalette,
    center: Offset,
    radius: Float,
): Brush = Brush.radialGradient(listOf(palette.groundLift, palette.groundEdge), center, radius)

/**
 * DESIGN.md's full-screen ground in [palette]: on Dark, screen-lift-top (#2E2A25) in the middle, falling off radially
 * to screen-lift-bottom (#151412) toward the edges. For a screen's own ground, drawn behind its content.
 */
fun Modifier.screenLift(palette: StanPalette = StanPalette.Dark): Modifier =
    drawWithCache {
        val radius = size.maxDimension * LIFT_RADIUS
        // A radial gradient needs a radius above zero; an unmeasured box has nothing to draw anyway.
        if (radius <= 0f) return@drawWithCache onDrawBehind {}
        val lift = liftBrush(palette, center = size.center, radius = radius)
        onDrawBehind { drawRect(lift) }
    }
