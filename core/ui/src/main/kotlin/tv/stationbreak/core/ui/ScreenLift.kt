// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush

private const val LIFT_RADIUS = .8f

/**
 * DESIGN.md's full-screen ink ground: screen-lift-top (#2E2A25) in the middle, falling off radially to
 * screen-lift-bottom (#151412) toward the edges. For a screen's own ground, drawn behind its content.
 */
fun Modifier.screenLift(): Modifier =
    drawWithCache {
        val radius = size.maxDimension * LIFT_RADIUS
        // A radial gradient needs a radius above zero; an unmeasured box has nothing to draw anyway.
        if (radius <= 0f) return@drawWithCache onDrawBehind {}
        val lift =
            Brush.radialGradient(
                colors = listOf(BrandColors.ScreenLiftTop, BrandColors.ScreenLiftBottom),
                center = size.center,
                radius = radius,
            )
        onDrawBehind { drawRect(lift) }
    }
