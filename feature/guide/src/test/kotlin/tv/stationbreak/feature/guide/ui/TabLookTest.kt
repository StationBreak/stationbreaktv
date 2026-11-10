// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.ui.GuideColors

/** The menu's three tab states are told apart by their underline, in both themes. */
class TabLookTest {
    @Test
    fun focusedIsPrimaryTextOverATwoDpAccentLine() {
        for (c in listOf(GuideColors.Dark, GuideColors.Light)) {
            val look = tabLook(focused = true, selected = false, colors = c)
            assertEquals(c.textPrimary, look.text)
            assertEquals(c.accent, look.underline)
            assertEquals(2.dp, look.thickness)
        }
    }

    @Test
    fun selectedButNotFocusedIsPrimaryTextOverAOneDpQuietLine() {
        for (c in listOf(GuideColors.Dark, GuideColors.Light)) {
            val look = tabLook(focused = false, selected = true, colors = c)
            assertEquals(c.textPrimary, look.text)
            assertEquals(c.line, look.underline)
            assertEquals(1.dp, look.thickness)
        }
    }

    @Test
    fun anyOtherTabIsSecondaryTextWithNoLine() {
        for (c in listOf(GuideColors.Dark, GuideColors.Light)) {
            val look = tabLook(focused = false, selected = false, colors = c)
            assertEquals(c.textSecondary, look.text)
            assertNull(look.underline)
        }
    }

    @Test
    fun focusedAndSelectedNeverLookAlike() {
        for (c in listOf(GuideColors.Dark, GuideColors.Light)) {
            val focused = tabLook(focused = true, selected = true, colors = c)
            val selected = tabLook(focused = false, selected = true, colors = c)
            assertNotEquals(focused.underline, selected.underline)
            assertNotEquals(focused.thickness, selected.thickness)
        }
    }
}
