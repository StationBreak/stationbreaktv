// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideSettingsKeysTest {
    private fun SettingsPanel.press(
        key: Key,
        repeat: Int = 0,
    ): SettingsStep = checkNotNull(settingsStep(this, key, KeyEventType.KeyDown, repeat)) { "$key was not kept" }

    private fun SettingsPanel.release(key: Key): SettingsStep =
        checkNotNull(settingsStep(this, key, KeyEventType.KeyUp, 0)) { "$key was not kept" }

    @Test
    fun settings_upDownMovesRows_leftRightStillPicksTheme() {
        val opened = SettingsPanel()
        assertEquals(SettingsRow.Appearance, opened.row)

        // On Appearance, Left and Right pick Dark and Light; the row stays.
        assertEquals(SettingsStep(opened, GuideTheme.Dark), opened.press(Key.DirectionLeft))
        assertEquals(SettingsStep(opened, GuideTheme.Light), opened.press(Key.DirectionRight))
        // Up at the top row stays there, and picks no theme any more.
        assertEquals(SettingsStep(opened), opened.press(Key.DirectionUp))

        // Down moves to Libraries, and stops at the last row, held or not.
        val libraries = opened.press(Key.DirectionDown).panel
        assertEquals(SettingsRow.Libraries, libraries?.row)
        assertEquals(SettingsStep(libraries), libraries!!.press(Key.DirectionDown, repeat = 2))
        // Left and Right on Libraries are kept, and do nothing.
        assertEquals(SettingsStep(libraries), libraries.press(Key.DirectionLeft))
        assertEquals(SettingsStep(libraries), libraries.press(Key.DirectionRight))

        // Up goes back to Appearance; releases change nothing.
        assertEquals(SettingsStep(opened), libraries.press(Key.DirectionUp))
        assertEquals(SettingsStep(libraries), libraries.release(Key.DirectionUp))

        // The media keys are not the panel's.
        assertNull(settingsStep(opened, Key.MediaPlayPause, KeyEventType.KeyDown, 0))
    }

    @Test
    fun selectLibraries_opensSlot_backReturns() {
        val onLibraries = SettingsPanel(SettingsRow.Libraries)

        // Select opens the slot, once per press: its auto-repeat and its release do nothing.
        val showing = onLibraries.press(Key.DirectionCenter).panel
        assertEquals(SettingsPanel(SettingsRow.Libraries, librariesShown = true), showing)
        assertEquals(SettingsStep(onLibraries), onLibraries.press(Key.DirectionCenter, repeat = 1))
        assertEquals(SettingsStep(onLibraries), onLibraries.release(Key.Enter))

        // While the slot shows, the guide's keys stand down: kept, and nothing moves or closes.
        for (key in listOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionCenter, Key.Back)) {
            assertEquals(SettingsStep(showing), showing!!.press(key))
            assertEquals(SettingsStep(showing), showing.release(key))
        }

        // The slot's Back returns to the panel, on Libraries; the next Back, at its release, closes the panel.
        val back = showing!!.librariesDone()
        assertEquals(onLibraries, back)
        assertEquals(SettingsStep(back), back.press(Key.Back))
        assertEquals(SettingsStep(null), back.release(Key.Back))
    }

    @Test
    fun selectOnAppearance_closesThePanel_asBefore() {
        assertEquals(SettingsStep(null), SettingsPanel().press(Key.Enter))
        assertEquals(SettingsStep(null), SettingsPanel().release(Key.Back))
    }
}
