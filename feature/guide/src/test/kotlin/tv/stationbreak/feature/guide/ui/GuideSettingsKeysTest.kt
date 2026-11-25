// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.ui.ChannelChangeStyle
import tv.stationbreak.core.ui.ScreenFit

/** The Settings sheet's keys: Up and Down move, Left, Right and OK change a choice in place, Back closes. */
class GuideSettingsKeysTest {
    private val defaults = SettingsValues()

    private fun SettingsPanel.press(
        key: Key,
        values: SettingsValues = defaults,
        repeat: Int = 0,
    ): SettingsStep {
        val step = settingsStep(this, values, key, KeyEventType.KeyDown, repeat)
        return checkNotNull(step) { "$key was not kept" }
    }

    private fun SettingsPanel.release(key: Key): SettingsStep =
        checkNotNull(settingsStep(this, defaults, key, KeyEventType.KeyUp, 0)) { "$key was not kept" }

    private fun on(row: SettingsRow) = SettingsPanel(row)

    @Test
    fun upAndDownWalkTheRowsInOrder_andStopAtTheEnds() {
        val opened = SettingsPanel()
        assertEquals(SettingsRow.Appearance, opened.row)
        assertEquals(SettingsStep(opened), opened.press(Key.DirectionUp))

        var panel = opened
        val walked = mutableListOf(panel.row)
        // No endpoint in this build (the default): the crash reports row is not on the sheet, so no walk lands on it.
        val shown = SettingsRow.entries.filter { it.isShown(defaults) }
        repeat(shown.size) {
            panel = checkNotNull(panel.press(Key.DirectionDown).panel)
            walked += panel.row
        }
        assertEquals(shown + SettingsRow.UserGuide, walked)
        // Held: every repeat moves (and stops at the end).
        val last = on(SettingsRow.UserGuide)
        assertEquals(SettingsStep(last), last.press(Key.DirectionDown, repeat = 3))
        assertEquals(SettingsStep(on(SettingsRow.ScreenFit)), on(SettingsRow.ChannelChange).press(Key.DirectionUp))
        // A release moves nothing.
        assertEquals(SettingsStep(on(SettingsRow.Libraries)), on(SettingsRow.Libraries).release(Key.DirectionUp))
    }

    @Test
    fun okOnTheProRowShowsTheProScreen_andBackFromItReturnsToTheSheetOnTheRow() {
        val pro = on(SettingsRow.Pro)
        val shown = pro.press(Key.DirectionCenter, defaults.copy(storeAvailable = true))
        assertEquals(SettingsStep(SettingsPanel(SettingsRow.Pro, proShown = true)), shown)
        assertTrue(checkNotNull(shown.panel).covered)
        // While it shows, the sheet keeps every key and does nothing: the screen has them.
        val kept = checkNotNull(shown.panel)
        assertEquals(SettingsStep(kept), kept.press(Key.DirectionDown))
        assertEquals(SettingsStep(kept), kept.press(Key.Back))
        assertEquals(SettingsPanel(SettingsRow.Pro), kept.proDone())
    }

    @Test
    fun okOnTheProRowDoesNothingWhileKidsModeIsOn() {
        val locked = defaults.copy(kidsOn = true, kidsPinSet = true, storeAvailable = true)
        assertEquals(SettingsStep(on(SettingsRow.Pro)), on(SettingsRow.Pro).press(Key.DirectionCenter, locked))
    }

    @Test
    fun crashReportsRowIsWalkedToOnlyInABuildWithAnEndpoint() {
        val signOut = on(SettingsRow.SignOut)
        assertEquals(SettingsStep(on(SettingsRow.UserGuide)), signOut.press(Key.DirectionDown))
        val offered = defaults.copy(crashReportsAvailable = true)
        assertEquals(SettingsStep(on(SettingsRow.CrashReports)), signOut.press(Key.DirectionDown, offered))
        assertEquals(SettingsStep(signOut), on(SettingsRow.CrashReports).press(Key.DirectionUp, offered))
        assertEquals(
            SettingsStep(on(SettingsRow.UserGuide)),
            on(SettingsRow.CrashReports).press(Key.DirectionDown, offered),
        )
    }

    @Test
    fun okOnTheUserGuideRowShowsItsScreen_andBackAndOkBothReturnToTheRow() {
        val row = on(SettingsRow.UserGuide)
        val shown = row.press(Key.DirectionCenter)
        assertEquals(SettingsStep(SettingsPanel(SettingsRow.UserGuide, guideShown = true)), shown)
        val screen = checkNotNull(shown.panel)
        // It has no slot of its own and no focus: the sheet's own keys are its keys.
        assertEquals(false, screen.covered)
        assertEquals(SettingsStep(row), screen.release(Key.Back))
        assertEquals(SettingsStep(row), screen.press(Key.DirectionCenter))
        // Every other D-pad key is kept and changes nothing; Back acts on its release; media keys are not its.
        for (key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
            assertEquals(SettingsStep(screen), screen.press(key))
        }
        assertEquals(SettingsStep(screen), screen.press(Key.Back))
        assertNull(settingsStep(screen, defaults, Key.MediaPlayPause, KeyEventType.KeyDown, 0))
        // Back from the row then closes the sheet, as before.
        assertEquals(SettingsStep(null), row.release(Key.Back))
    }

    @Test
    fun okOnTheUserGuideRowDoesNothingWhileFamilyModeIsOn() {
        val locked = defaults.copy(kidsOn = true, kidsPinSet = true)
        val row = on(SettingsRow.UserGuide)
        assertEquals(SettingsStep(row), row.press(Key.DirectionCenter, locked))
    }

    @Test
    fun crashReportsAreOffUntilTheViewerTurnsThemOn_andOnlyThenChosen() {
        val row = on(SettingsRow.CrashReports)
        val offered = defaults.copy(crashReportsAvailable = true)
        assertEquals(false, offered.crashReports)
        assertEquals(
            SettingsStep(row, crashReports = true, crashReportsFrom = "settingsRight"),
            row.press(Key.DirectionRight, offered),
        )
        // Already off: Left chooses nothing again.
        assertEquals(SettingsStep(row), row.press(Key.DirectionLeft, offered))
        val on = offered.copy(crashReports = true)
        assertEquals(
            SettingsStep(row, crashReports = false, crashReportsFrom = "settingsOk"),
            row.press(Key.DirectionCenter, on),
        )
    }

    @Test
    fun leftAndRightStepAChoice_inPlace_andStopAtTheEnds() {
        val appearance = on(SettingsRow.Appearance)
        assertEquals(SettingsStep(appearance, theme = GuideTheme.Light), appearance.press(Key.DirectionRight))
        // Already Dark: Left leaves it, and chooses nothing again.
        assertEquals(SettingsStep(appearance), appearance.press(Key.DirectionLeft))
        val light = defaults.copy(theme = GuideTheme.Light)
        assertEquals(SettingsStep(appearance, theme = GuideTheme.Dark), appearance.press(Key.DirectionLeft, light))
        assertEquals(SettingsStep(appearance), appearance.press(Key.DirectionRight, light))

        val fit = on(SettingsRow.ScreenFit)
        assertEquals(SettingsStep(fit, fit = ScreenFit.Edge), fit.press(Key.DirectionRight))
        assertEquals(
            SettingsStep(fit, fit = ScreenFit.Safe),
            fit.press(Key.DirectionLeft, defaults.copy(fit = ScreenFit.Edge)),
        )

        val change = on(SettingsRow.ChannelChange)
        assertEquals(
            SettingsStep(change, channelChange = ChannelChangeStyle.Snow, channelChangeFrom = "settingsRight"),
            change.press(Key.DirectionRight),
        )
        assertEquals(
            SettingsStep(change, channelChange = ChannelChangeStyle.Stan, channelChangeFrom = "settingsLeft"),
            change.press(Key.DirectionLeft, defaults.copy(channelChange = ChannelChangeStyle.Snow)),
        )

        // Even out volume starts On: Left turns it off, Right leaves it.
        val levelling = on(SettingsRow.VolumeLevelling)
        assertEquals(
            SettingsStep(levelling, levelling = false, levellingFrom = "settingsLeft"),
            levelling.press(Key.DirectionLeft),
        )
        assertEquals(SettingsStep(levelling), levelling.press(Key.DirectionRight))
    }

    @Test
    fun okOnAChoiceMovesItOnAndRoundAgain_neverClosingTheSheet() {
        val appearance = on(SettingsRow.Appearance)
        assertEquals(SettingsStep(appearance, theme = GuideTheme.Light), appearance.press(Key.DirectionCenter))
        assertEquals(
            SettingsStep(appearance, theme = GuideTheme.Dark),
            appearance.press(Key.Enter, defaults.copy(theme = GuideTheme.Light)),
        )
        val fit = on(SettingsRow.ScreenFit)
        assertEquals(SettingsStep(fit, fit = ScreenFit.Edge), fit.press(Key.DirectionCenter))
        val change = on(SettingsRow.ChannelChange)
        assertEquals(
            SettingsStep(change, channelChange = ChannelChangeStyle.Snow, channelChangeFrom = "settingsOk"),
            change.press(Key.DirectionCenter),
        )
        val levelling = on(SettingsRow.VolumeLevelling)
        assertEquals(
            SettingsStep(levelling, levelling = false, levellingFrom = "settingsOk"),
            levelling.press(Key.NumPadEnter),
        )
        for (row in SettingsRow.entries.filter { it.kind == RowKind.Choice }) {
            assertNotNull("OK on $row closed the sheet", on(row).press(Key.DirectionCenter).panel)
        }
        // Once per press: the auto-repeat and the release change nothing.
        assertEquals(SettingsStep(appearance), appearance.press(Key.DirectionCenter, repeat = 1))
        assertEquals(SettingsStep(appearance), appearance.release(Key.DirectionCenter))
    }

    @Test
    fun leftAndRightOnALinkOrAnActionDoNothing() {
        for (row in listOf(SettingsRow.Libraries, SettingsRow.SyncNow, SettingsRow.SignOut)) {
            assertEquals(SettingsStep(on(row)), on(row).press(Key.DirectionLeft))
            assertEquals(SettingsStep(on(row)), on(row).press(Key.DirectionRight))
        }
    }

    @Test
    fun okOnLibrariesShowsItsScreen_andTheSheetHoldsEveryKeyUnderIt() {
        val libraries = on(SettingsRow.Libraries)
        val showing = libraries.press(Key.DirectionCenter).panel
        assertEquals(SettingsPanel(SettingsRow.Libraries, librariesShown = true), showing)
        for (key in listOf(Key.DirectionDown, Key.DirectionRight, Key.DirectionCenter, Key.Back)) {
            assertEquals(SettingsStep(showing), showing!!.press(key))
            assertEquals(SettingsStep(showing), showing.release(key))
        }
        val back = showing!!.librariesDone()
        assertEquals(libraries, back)
        assertEquals(SettingsStep(null), back.release(Key.Back))
    }

    @Test
    fun okOnSignOutAsksFirst_andTheSheetHoldsEveryKeyUnderTheQuestion() {
        val signOut = on(SettingsRow.SignOut)
        val asking = signOut.press(Key.DirectionCenter).panel
        assertEquals(SettingsPanel(SettingsRow.SignOut, signOutShown = true), asking)
        for (key in listOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionCenter, Key.Back)) {
            assertEquals(SettingsStep(asking), asking!!.press(key))
            assertEquals(SettingsStep(asking), asking.release(key))
        }
        assertEquals(signOut, asking!!.signOutDone())
        // The auto-repeat of the OK that opened the sheet's row asks nothing.
        assertEquals(SettingsStep(signOut), signOut.press(Key.DirectionCenter, repeat = 2))
    }

    @Test
    fun backClosesTheSheetAtItsRelease_fromAnyRow() {
        for (row in SettingsRow.entries) {
            assertEquals(SettingsStep(on(row)), on(row).press(Key.Back))
            assertEquals(SettingsStep(null), on(row).release(Key.Back))
        }
    }

    @Test
    fun theMediaKeysAreNotTheSheets() {
        assertNull(settingsStep(SettingsPanel(), defaults, Key.MediaPlayPause, KeyEventType.KeyDown, 0))
        assertNull(settingsStep(SettingsPanel(), defaults, Key.Menu, KeyEventType.KeyDown, 0))
    }
}
