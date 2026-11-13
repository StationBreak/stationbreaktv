// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.feature.guide.R

/** What the hint row says Back does, in the menu sheet (#756) and out of it. */
class BackHintTest {
    @Test
    fun inTheSheetBackClosesIt() {
        assertEquals(
            R.string.guide_hint_close_menu,
            backHintOf(sheet = true, noLibraries = false, onChannel = true, away = true, toMenu = false),
        )
    }

    @Test
    fun inTheSheetWithNoLibrariesBackLeavesLiveTv() {
        assertEquals(
            R.string.guide_hint_exit,
            backHintOf(sheet = true, noLibraries = true, onChannel = false, away = false, toMenu = false),
        )
    }

    @Test
    fun outOfTheSheetBackGoesOutwardsAsItDid() {
        fun hint(
            onChannel: Boolean = false,
            away: Boolean = false,
            toMenu: Boolean = false,
        ) = backHintOf(sheet = false, noLibraries = false, onChannel = onChannel, away = away, toMenu = toMenu)
        assertEquals(R.string.guide_hint_programs, hint(onChannel = true, away = true))
        assertEquals(R.string.guide_hint_playing, hint(away = true))
        assertEquals(R.string.guide_hint_menu, hint(toMenu = true))
        assertEquals(R.string.guide_hint_exit, hint())
    }
}
