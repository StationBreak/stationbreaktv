// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.feature.guide.R

/** What the hint row says Back does, in the menu sheet (#756) and out of it. */
class BackHintTest {
    @Suppress("LongParameterList") // Mirrors backHintOf, every flag defaulted off.
    private fun hint(
        sheet: Boolean = false,
        sheetByBack: Boolean = false,
        noLibraries: Boolean = false,
        onChannel: Boolean = false,
        away: Boolean = false,
        toMenu: Boolean = false,
    ) = backHintOf(sheet, sheetByBack, noLibraries, onChannel, away, toMenu)

    @Test
    fun inASheetTheMenuKeyOpenedBackClosesIt() {
        assertEquals(R.string.guide_hint_close_menu, hint(sheet = true, onChannel = true, away = true))
    }

    @Test
    fun inASheetBackOpenedBackLeavesLiveTv() {
        assertEquals(R.string.guide_hint_exit, hint(sheet = true, sheetByBack = true))
    }

    @Test
    fun inTheSheetWithNoLibrariesBackLeavesLiveTv() {
        assertEquals(R.string.guide_hint_exit, hint(sheet = true, noLibraries = true))
    }

    @Test
    fun outOfTheSheetBackGoesOutwardsAsItDid() {
        assertEquals(R.string.guide_hint_programs, hint(onChannel = true, away = true))
        assertEquals(R.string.guide_hint_playing, hint(away = true))
        assertEquals(R.string.guide_hint_menu, hint(toMenu = true))
        assertEquals(R.string.guide_hint_exit, hint())
    }
}
