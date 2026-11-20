// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * True while something drawn over the whole screen hides this composition: Stan's opening, or setup's cheer held over
 * live TV. The app provides it. A screen reader cannot see a covered screen, so a polite live region that appeared
 * under the cover was never announced: key the region on this value, and it is a new region, said once, when the cover
 * lifts.
 */
val LocalCovered = compositionLocalOf { false }
