// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp

/** The guide's geometry, kept apart from the composables so it can be tested on the JVM. */
object GuideLayout {
    private const val PICTURE_WIDTH_FRACTION = 0.4f
    private const val ASPECT_WIDTH = 16f
    private const val ASPECT_HEIGHT = 9f

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
