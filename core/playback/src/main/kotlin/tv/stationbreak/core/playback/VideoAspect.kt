// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.VideoSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The display shape of [size] (width over height, pixel shape applied), or null while it is unknown: no width or no
 * height (`VideoSize.UNKNOWN`). A pixel ratio that is not a positive number counts as square.
 */
internal fun displayAspect(size: VideoSize): Float? = displayAspect(size.width, size.height, size.pixelWidthHeightRatio)

internal fun displayAspect(
    width: Int,
    height: Int,
    pixelRatio: Float,
): Float? {
    if (width <= 0 || height <= 0) return null
    val par = if (pixelRatio.isFinite() && pixelRatio > 0f) pixelRatio else 1f
    return width * par / height
}

/**
 * The shape of the picture as the player last reported it. An unknown size (0x0, as between items) changes nothing, so
 * the last picture's shape stays until the next item reports its own: a screen sized by it does not flash to stretched.
 */
internal class VideoAspectHolder {
    private val state = MutableStateFlow<Float?>(null)
    val aspect: StateFlow<Float?> = state.asStateFlow()

    fun onVideoSize(size: VideoSize) {
        displayAspect(size)?.let { state.value = it }
    }
}
