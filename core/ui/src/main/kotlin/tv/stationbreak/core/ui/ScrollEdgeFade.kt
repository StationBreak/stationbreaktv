// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

// How far the scrolling part fades out at an edge with more beyond it: about one 16 sp line.
private val EdgeFade = 24.dp

/**
 * Fades a scrolling part out at an edge that has more beyond it, so a line the edge cuts reads as "more below" (or
 * above) rather than as broken text. [scroll] is read in the draw phase only: scrolling redraws, it does not recompose.
 */
fun Modifier.scrollEdgeFade(scroll: ScrollState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = EdgeFade.toPx().coerceAtMost(size.height / 2)
            if (scroll.value > 0) {
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = fade),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (scroll.value < scroll.maxValue) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        1f to Color.Transparent,
                        startY = size.height - fade,
                        endY = size.height,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
