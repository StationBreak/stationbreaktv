// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * The video surface, fitted to the picture's shape inside the box it is given: [aspect] (width over height, from the
 * player) pillarboxes or letterboxes it in black, centred, and null (no size known yet) fills the box. A bare surface
 * view stretches the picture to its bounds (#599), so the view itself is sized to the picture.
 *
 * The same [surface] instance stays in the same place in the composition; only its modifier changes, so it is never
 * made again and the picture plays on. The aspect is rounded ([fitAspect]) so a hair's difference lays nothing out.
 */
@Composable
internal fun VideoFrame(
    surface: SurfaceView,
    aspect: Float?,
) {
    val fitted = fitAspect(aspect)
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { surface },
            modifier = if (fitted != null) Modifier.aspectRatio(fitted) else Modifier.fillMaxSize(),
        )
    }
}

/** [aspect] to the nearest thousandth; null stays null, and so does a value that is not a usable shape. */
internal fun fitAspect(aspect: Float?): Float? {
    if (aspect == null || !aspect.isFinite() || aspect <= 0f) return null
    return (aspect * ASPECT_STEPS).roundToInt().takeIf { it > 0 }?.let { it / ASPECT_STEPS }
}

private const val ASPECT_STEPS = 1000f
