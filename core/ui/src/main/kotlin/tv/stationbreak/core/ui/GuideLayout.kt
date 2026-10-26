// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize

/**
 * The screen geometry shared by live TV and the guide, kept apart from the composables so it can be tested on the JVM.
 * The banner, the info overlay, the guide and the guide's corner picture all lay out inside [safeArea], so the picture
 * and the guide's hole for it come from the one rectangle and cannot drift apart.
 */
object GuideLayout {
    /** How much a TV may crop (overscan): 5% of the width at each side, 5% of the height at top and bottom. */
    const val OVERSCAN = 0.05f

    private const val PICTURE_WIDTH_FRACTION = 0.4f
    private const val ASPECT_WIDTH = 16f
    private const val ASPECT_HEIGHT = 9f

    /** The part of a [screen] that every TV shows: [OVERSCAN] in from each edge. */
    fun safeArea(screen: DpSize): DpRect {
        val side = screen.width * OVERSCAN
        val edge = screen.height * OVERSCAN
        return DpRect(left = side, top = edge, right = screen.width - side, bottom = screen.height - edge)
    }

    /** Where the live picture sits: the top-right of [safeArea], 40% of its width, 16:9. */
    fun pictureRect(safeArea: DpRect): DpRect {
        val width = (safeArea.right - safeArea.left) * PICTURE_WIDTH_FRACTION
        val height = width * (ASPECT_HEIGHT / ASPECT_WIDTH)
        return DpRect(
            left = safeArea.right - width,
            top = safeArea.top,
            right = safeArea.right,
            bottom = safeArea.top + height,
        )
    }
}
