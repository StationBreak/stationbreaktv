// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import tv.stationbreak.feature.guide.R

/**
 * What Back does, as a hint. With the menu [sheet] open: leaves live TV when Back opened it ([sheetByBack]) or with
 * [noLibraries] (it is all there is), else closes it. Otherwise: back to the programs from the channel column
 * ([onChannel]), to what is playing when the focus is [away] from it, to the menu on it ([toMenu]), else leaves.
 */
@StringRes
@Suppress("LongParameterList") // One flag for each thing Back's answer depends on.
internal fun backHintOf(
    sheet: Boolean,
    sheetByBack: Boolean,
    noLibraries: Boolean,
    onChannel: Boolean,
    away: Boolean,
    toMenu: Boolean,
): Int =
    when {
        sheet && !sheetByBack && !noLibraries -> R.string.guide_hint_close_menu
        sheet -> R.string.guide_hint_exit
        onChannel -> R.string.guide_hint_programs
        away -> R.string.guide_hint_playing
        toMenu -> R.string.guide_hint_menu
        else -> R.string.guide_hint_exit
    }
