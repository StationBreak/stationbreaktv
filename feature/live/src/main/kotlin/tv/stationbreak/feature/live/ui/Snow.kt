// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.stationbreak.core.ui.BroadcastColors
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random
import android.graphics.Color as AndroidColor

private const val FRAME_COUNT = 4
private const val FRAME_MS = 80L

/** A quarter of 1080p: scaled up with no smoothing, so the grain reads as grain. */
private const val NOISE_WIDTH = 480
private const val NOISE_HEIGHT = 270
private const val NOISE_OPACITY = 0.28f
private const val VIGNETTE_START = 0.4f
private const val VIGNETTE_STRENGTH = 0.7f
private const val GREY_LEVELS = 256
private const val NOISE_SEED = 0x5B

/**
 * The snow's four frames: greyscale noise made once, at quarter resolution, and cycled. Nothing is made per frame and
 * there is no shader, because the Insignia (API 25) shares its small GPU with the video decoder. Held for the process:
 * four small bitmaps (about 2 MB) that every later tune reuses.
 */
internal object SnowFrames {
    @Volatile private var frames: List<ImageBitmap>? = null

    val cached: List<ImageBitmap>? get() = frames

    @Suppress("InjectDispatcher") // A pure computation with nothing to inject a dispatcher into; off the main thread.
    suspend fun get(): List<ImageBitmap> =
        frames ?: withContext(Dispatchers.Default) { make() }.also { frames = it }

    private fun make(): List<ImageBitmap> {
        val random = Random(NOISE_SEED)
        return List(FRAME_COUNT) {
            val pixels =
                IntArray(NOISE_WIDTH * NOISE_HEIGHT) {
                    val grey = random.nextInt(GREY_LEVELS)
                    AndroidColor.rgb(grey, grey, grey)
                }
            Bitmap.createBitmap(pixels, NOISE_WIDTH, NOISE_HEIGHT, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}

/**
 * Dim, moving snow (board D): the ink ground with the noise at about 28%, cycling about every 80 ms, under a vignette.
 * Until the frames exist (the first tune of the process) it is the plain ink ground.
 */
@Composable
internal fun Snow(modifier: Modifier = Modifier) {
    val frames by produceState(SnowFrames.cached) { value = SnowFrames.get() }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // Cooperates with a test clock, which ends an endless animation instead of waiting on it for ever.
        while (true) {
            withInfiniteAnimationFrameMillis { millis -> frame = ((millis / FRAME_MS) % FRAME_COUNT).toInt() }
        }
    }
    Canvas(modifier.fillMaxSize().testTag(LiveTags.SNOW)) {
        drawRect(BroadcastColors.Ink)
        // Read here, in the draw phase, so a new frame redraws and nothing recomposes.
        frames?.let { noise ->
            drawImage(
                noise[frame],
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                alpha = NOISE_OPACITY,
                filterQuality = FilterQuality.None,
            )
        }
        drawRect(
            Brush.radialGradient(
                VIGNETTE_START to Color.Transparent,
                1f to Color.Black.copy(alpha = VIGNETTE_STRENGTH),
                center = center,
                radius = hypot(size.width, size.height) / 2,
            ),
        )
    }
}
