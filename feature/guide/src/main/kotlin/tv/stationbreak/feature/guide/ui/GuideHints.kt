// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import tv.stationbreak.feature.guide.R

/**
 * What Back does, as a hint: closes the menu [sheet] (leaves live TV from it with [noLibraries], when it is all there
 * is), goes back to the programs from the channel column ([onChannel]), to what is playing when the focus is [away]
 * from it, to the menu on it ([toMenu]), else leaves live TV.
 */
@StringRes
internal fun backHintOf(
    sheet: Boolean,
    noLibraries: Boolean,
    onChannel: Boolean,
    away: Boolean,
    toMenu: Boolean,
): Int =
    when {
        sheet && !noLibraries -> R.string.guide_hint_close_menu
        sheet -> R.string.guide_hint_exit
        onChannel -> R.string.guide_hint_programs
        away -> R.string.guide_hint_playing
        toMenu -> R.string.guide_hint_menu
        else -> R.string.guide_hint_exit
    }
