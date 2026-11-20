// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * True while something drawn over the whole screen hides this composition: Stan's opening, or setup's cheer held over
 * live TV. The app provides it. A screen reader cannot see a covered screen, so a polite live region that appeared
 * under the cover was never announced.
 *
 * Convention: wrap such a region in `key(LocalCovered.current) { ... }`. When the cover lifts the key changes, the
 * region is composed afresh as a new node, and a screen reader says it once, as it says any region that appears.
 */
val LocalCovered = compositionLocalOf { false }
