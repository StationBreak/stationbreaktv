// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.kids.PinResult
import tv.stationbreak.feature.guide.PinSubmit

/** The Kids Mode rows' keys and the PIN pad (#789): set, on and off, change, wrong PINs, lockout, inert rows. */
class KidsPinTest {
    private val off = SettingsValues()
    private val offWithPin = SettingsValues(kidsPinSet = true)
    private val on = SettingsValues(kidsOn = true, kidsPinSet = true)

    private fun SettingsPanel.press(
        key: Key,
        values: SettingsValues = off,
    ): SettingsStep = checkNotNull(settingsStep(this, values, key, KeyEventType.KeyDown, 0)) { "$key was not kept" }

    // Types [digits] on hardware number keys, each one a press; the step of the last one.
    private fun SettingsPanel.type(
        digits: String,
        values: SettingsValues = off,
    ): SettingsStep {
        var step = SettingsStep(this)
        for (d in digits) step = checkNotNull(step.panel).press(NUMBER_KEYS.getValue(d), values)
        return step
    }

    private fun SettingsPanel.padOf() = checkNotNull(pin)

    @Test
    fun selectOnKidsModeWithNoPinAsksForOne() {
        val step = SettingsPanel(SettingsRow.KidsMode).press(Key.DirectionCenter)
        assertEquals(PinEntry.start(PinPurpose.Set), step.panel?.pin)
        assertEquals(PinStage.New, step.panel?.pin?.stage)
        assertFalse(step.kidsEnable)
    }

    @Test
    fun selectOnKidsModeWithAPinSetTurnsItOnAtOnce() {
        val step = SettingsPanel(SettingsRow.KidsMode).press(Key.DirectionCenter, offWithPin)
        assertTrue(step.kidsEnable)
        assertNull(step.panel?.pin)
        assertEquals(SettingsRow.KidsMode, step.panel?.row)
    }

    @Test
    fun selectOnKidsModeWhileOnAsksForThePinToTurnItOff() {
        val step = SettingsPanel(SettingsRow.KidsMode).press(Key.DirectionCenter, on)
        assertEquals(PinEntry.start(PinPurpose.TurnOff), step.panel?.pin)
        assertFalse(step.kidsEnable)
    }

    private val kidsRow = SettingsPanel(SettingsRow.KidsMode)

    @Test
    fun rightAndLeftOnKidsModeWhileOffMoveTheCapAtOnce_withNoPin() {
        val atKids = off.copy(kidsCap = KidsCap.Kids)
        assertEquals(SettingsStep(kidsRow, kidsCap = KidsCap.Family), kidsRow.press(Key.DirectionRight, atKids))
        val atFamily = off.copy(kidsCap = KidsCap.Family)
        assertEquals(SettingsStep(kidsRow, kidsCap = KidsCap.Teen), kidsRow.press(Key.DirectionRight, atFamily))
        assertEquals(SettingsStep(kidsRow, kidsCap = KidsCap.Kids), kidsRow.press(Key.DirectionLeft, atFamily))
    }

    @Test
    fun theCapStopsAtItsEnds_noWrap() {
        val atTeen = off.copy(kidsCap = KidsCap.Teen)
        assertEquals(SettingsStep(kidsRow), kidsRow.press(Key.DirectionRight, atTeen))
        assertEquals(SettingsStep(kidsRow), kidsRow.press(Key.DirectionLeft, off.copy(kidsCap = KidsCap.Kids)))
        val onAtTeen = on.copy(kidsCap = KidsCap.Teen)
        assertEquals(SettingsStep(kidsRow), kidsRow.press(Key.DirectionRight, onAtTeen))
    }

    @Test
    fun rightOnKidsModeWhileOnAsksForThePinForTheNewCap_andChangesNothingYet() {
        val step = kidsRow.press(Key.DirectionRight, on.copy(kidsCap = KidsCap.Kids))
        assertEquals(PinEntry.start(PinPurpose.Cap, KidsCap.Family), step.panel?.pin)
        assertEquals(PinStage.Current, step.panel?.pin?.stage)
        assertNull(step.kidsCap)
        assertNull(step.pinSubmit)
    }

    @Test
    fun theCapPadAsksTheLockForTheCapAfterFourDigits_andOkClosesIt_andAWrongPinStaysOnThePad() {
        val panel = SettingsPanel(SettingsRow.KidsMode, pin = PinEntry.start(PinPurpose.Cap, KidsCap.Teen))
        val step = panel.type("1234", on)
        val ask = step.pinSubmit as PinSubmit.SetCap
        assertEquals(KidsCap.Teen, ask.cap)
        assertEquals("1234", ask.pin)
        assertEquals(true, step.panel?.pin?.busy)
        assertNull(checkNotNull(step.panel).pinAnswered(PinResult.Ok).pin)
        val wrong = checkNotNull(step.panel).pinAnswered(PinResult.Wrong(triesLeft = 1)).padOf()
        assertEquals(PinNotice.Wrong(1), wrong.notice)
        assertEquals(PinPurpose.Cap, wrong.purpose)
        assertEquals(KidsCap.Teen, wrong.cap)
        assertEquals(PinStage.Current, wrong.stage)
        val locked = checkNotNull(step.panel).pinAnswered(PinResult.LockedOut(60_000)).padOf()
        assertTrue(locked.locked)
        assertEquals(PinNotice.Failed, checkNotNull(step.panel).pinAnswered(PinResult.Unavailable).padOf().notice)
    }

    @Test
    fun theCapDoesNothingBeforeTheLockIsRead_orOnAnyOtherRow() {
        val unread = SettingsValues(kidsOn = true, kidsKnown = false)
        assertEquals(SettingsStep(kidsRow), kidsRow.press(Key.DirectionRight, unread))
        assertEquals(SettingsStep(kidsRow), kidsRow.press(Key.DirectionLeft, unread.copy(kidsCap = KidsCap.Teen)))
        val libraries = SettingsPanel(SettingsRow.Libraries)
        assertNull(libraries.press(Key.DirectionRight, on).kidsCap)
        assertNull(libraries.press(Key.DirectionRight, on).panel?.pin)
    }

    @Test
    fun okOnKidsModeStillOpensItsOwnPad_notTheCapOne() {
        val step = kidsRow.press(Key.DirectionCenter, on.copy(kidsCap = KidsCap.Teen))
        assertEquals(PinPurpose.TurnOff, step.panel?.pin?.purpose)
    }

    @Test
    fun settingAPinAsksTwiceAndThenSetsIt() {
        var panel = SettingsPanel(SettingsRow.KidsMode, pin = PinEntry.start(PinPurpose.Set))
        val first = panel.type("1234")
        panel = checkNotNull(first.panel)
        assertNull(first.pinSubmit)
        assertEquals(PinStage.Confirm, panel.padOf().stage)
        assertEquals("", panel.padOf().digits)
        val second = panel.type("1234")
        assertTrue(second.pinSubmit is PinSubmit.SetFirst)
        assertEquals("1234", (second.pinSubmit as PinSubmit.SetFirst).pin)
        assertTrue(checkNotNull(second.panel).padOf().busy)
        // The lock said yes: the pad closes, and the sheet is on its row.
        val done = checkNotNull(second.panel).pinAnswered(PinResult.Ok)
        assertNull(done.pin)
        assertEquals(SettingsRow.KidsMode, done.row)
    }

    @Test
    fun aPinThatDoesNotMatchSaysSoAndStartsOver() {
        val asking = SettingsPanel(SettingsRow.KidsMode, pin = PinEntry.start(PinPurpose.Set))
        val panel = checkNotNull(asking.type("1234").panel)
        val step = panel.type("1235")
        assertNull(step.pinSubmit)
        val pad = checkNotNull(step.panel).padOf()
        assertEquals(PinNotice.Mismatch, pad.notice)
        assertEquals(PinStage.New, pad.stage)
        assertEquals("", pad.digits)
        assertEquals("", pad.candidate)
        // Typing again clears the message.
        assertNull(checkNotNull(checkNotNull(step.panel).type("1").panel).padOf().notice)
    }

    @Test
    fun turningOffAsksTheLockAfterFourDigits_andAWrongPinShowsTriesLeft() {
        val panel = SettingsPanel(SettingsRow.KidsMode, pin = PinEntry.start(PinPurpose.TurnOff))
        val step = panel.type("0000", on)
        assertTrue(step.pinSubmit is PinSubmit.TurnOff)
        val wrong = checkNotNull(step.panel).pinAnswered(PinResult.Wrong(triesLeft = 2))
        assertEquals(PinNotice.Wrong(2), wrong.padOf().notice)
        assertEquals("", wrong.padOf().digits)
        assertFalse(wrong.padOf().busy)
        // And the right one closes the pad.
        val right = checkNotNull(wrong.type("1234", on).panel).pinAnswered(PinResult.Ok)
        assertNull(right.pin)
    }

    @Test
    fun aLockoutRefusesEveryDigitUntilItPasses() {
        val busy = checkNotNull(SettingsPanel(pin = PinEntry.start(PinPurpose.TurnOff)).type("1111", on).panel)
        val locked = busy.pinAnswered(PinResult.LockedOut(remainingMillis = 90_000))
        assertTrue(locked.padOf().locked)
        assertEquals(PinNotice.LockedOut(90_000), locked.padOf().notice)
        // Digits and OK on a pad key are kept and do nothing.
        assertEquals(SettingsStep(locked), locked.press(Key.Five, on))
        assertEquals(SettingsStep(locked), locked.press(Key.DirectionCenter, on))
        assertEquals(SettingsStep(locked), locked.press(Key.Backspace, on))
        // The lockout ending takes the notice away and the pad takes digits again.
        val open = locked.pinUnlocked()
        assertNull(open.padOf().notice)
        assertEquals("5", checkNotNull(open.press(Key.Five, on).panel).padOf().digits)
    }

    @Test
    fun changingAsksTheOldPinFirst_thenTheNewOneTwice() {
        var panel = SettingsPanel(SettingsRow.ChangePin).press(Key.DirectionCenter, offWithPin).panel
        assertEquals(PinPurpose.Change, panel?.pin?.purpose)
        assertEquals(PinStage.Current, panel?.pin?.stage)
        val old = checkNotNull(panel).type("1234", offWithPin)
        assertTrue(old.pinSubmit is PinSubmit.CheckOld)
        // A wrong old PIN asks again.
        val wrong = checkNotNull(old.panel).pinAnswered(PinResult.Wrong(triesLeft = 1))
        assertEquals(PinStage.Current, wrong.padOf().stage)
        assertEquals(PinNotice.Wrong(1), wrong.padOf().notice)
        // The right one goes on to the new PIN.
        val busy = checkNotNull(wrong.type("1234", offWithPin).panel)
        panel = busy.pinAnswered(PinResult.Ok)
        assertEquals(PinStage.New, panel.padOf().stage)
        assertFalse(panel.padOf().busy)
        panel = checkNotNull(panel.type("5678", offWithPin).panel)
        assertEquals(PinStage.Confirm, panel.padOf().stage)
        val last = panel.type("5678", offWithPin)
        val ask = last.pinSubmit as PinSubmit.ChangeTo
        assertEquals("1234", ask.old)
        assertEquals("5678", ask.new)
        assertNull(checkNotNull(last.panel).pinAnswered(PinResult.Ok).pin)
    }

    @Test
    fun aLockFailureAsksAgain_fromTheStartOfTheNewPin() {
        val confirming = checkNotNull(SettingsPanel(pin = PinEntry.start(PinPurpose.Set)).type("1234").panel)
        val busy = checkNotNull(confirming.type("1234").panel)
        val failed = busy.pinAnswered(PinResult.Unavailable)
        assertEquals(PinNotice.Failed, failed.padOf().notice)
        assertEquals(PinStage.New, failed.padOf().stage)
        // An answer with nothing awaited is ignored.
        assertEquals(failed, failed.pinAnswered(PinResult.Ok))
    }

    @Test
    fun theDPadMovesAFocusOnThePad_andOkTypesTheKeyUnderIt() {
        var panel = SettingsPanel(pin = PinEntry.start(PinPurpose.Set))
        assertEquals(PadKey.D5, panel.padOf().focus)
        panel = checkNotNull(panel.press(Key.DirectionRight).panel)
        assertEquals(PadKey.D5, panel.padOf().focus)
        panel = checkNotNull(panel.press(Key.DirectionDown).panel)
        assertEquals(PadKey.D0, panel.padOf().focus)
        panel = checkNotNull(panel.press(Key.DirectionDown).panel)
        assertEquals(PadKey.Delete, panel.padOf().focus)
        panel = checkNotNull(panel.press(Key.DirectionUp).panel)
        assertEquals(PadKey.D8, panel.padOf().focus)
        panel = checkNotNull(panel.press(Key.DirectionCenter).panel)
        assertEquals("8", panel.padOf().digits)
        panel = checkNotNull(panel.press(Key.DirectionDown).panel)
        panel = checkNotNull(panel.press(Key.DirectionCenter).panel)
        assertEquals("", panel.padOf().digits)
    }

    @Test
    fun aHeldSelectTypesOnce() {
        val panel = SettingsPanel(pin = PinEntry.start(PinPurpose.Set))
        val held = settingsStep(panel, off, Key.DirectionCenter, KeyEventType.KeyDown, repeatCount = 1)
        assertEquals(SettingsStep(panel), held)
        assertEquals(SettingsStep(panel), settingsStep(panel, off, Key.DirectionCenter, KeyEventType.KeyUp, 0))
    }

    @Test
    fun backCancelsThePad_andTheSheetStaysOnItsRow() {
        val panel = SettingsPanel(SettingsRow.KidsMode, pin = PinEntry.start(PinPurpose.Set))
        val up = checkNotNull(settingsStep(panel, off, Key.Back, KeyEventType.KeyUp, 0))
        assertEquals(SettingsStep(SettingsPanel(SettingsRow.KidsMode)), up)
        // The press's own down does nothing.
        assertEquals(SettingsStep(panel), panel.press(Key.Back))
        // Back works while an answer is awaited too.
        val busy = checkNotNull(panel.type("1234").panel).type("1234").panel
        assertNotNull(busy?.pin?.busy)
        assertNull(checkNotNull(settingsStep(checkNotNull(busy), off, Key.Back, KeyEventType.KeyUp, 0)).panel?.pin)
    }

    @Test
    fun noDigitIsTakenWhileAnAnswerIsAwaited() {
        val asking = SettingsPanel(pin = PinEntry.start(PinPurpose.Set))
        val confirming = checkNotNull(asking.type("1234").panel)
        val busy = checkNotNull(confirming.type("1234").panel)
        assertEquals(SettingsStep(busy), busy.press(Key.Seven))
        assertEquals(SettingsStep(busy), busy.press(Key.DirectionCenter))
    }

    @Test
    fun otherKeysAreNotThePadsOwn() {
        val panel = SettingsPanel(pin = PinEntry.start(PinPurpose.Set))
        assertNull(settingsStep(panel, off, Key.MediaPlayPause, KeyEventType.KeyDown, 0))
        assertNull(settingsStep(panel, off, Key.Menu, KeyEventType.KeyDown, 0))
    }

    @Test
    fun theRowsKidsModeSwitchesOffDoNothing_andTheOthersStillWork() {
        for (row in listOf(SettingsRow.Libraries, SettingsRow.SyncNow, SettingsRow.SignOut)) {
            val panel = SettingsPanel(row)
            assertEquals(SettingsStep(panel), panel.press(Key.DirectionCenter, on))
            assertEquals(SettingsStep(panel), panel.press(Key.Enter, on))
        }
        // Off, they act.
        val libraries = SettingsPanel(SettingsRow.Libraries).press(Key.DirectionCenter, offWithPin)
        assertTrue(libraries.panel?.librariesShown == true)
        assertTrue(SettingsPanel(SettingsRow.SyncNow).press(Key.DirectionCenter, offWithPin).syncNow)
        val signOut = SettingsPanel(SettingsRow.SignOut).press(Key.DirectionCenter, offWithPin)
        assertTrue(signOut.panel?.signOutShown == true)
        // The look and the volume stay the viewer's while it is on.
        assertEquals(GuideTheme.Light, SettingsPanel(SettingsRow.Appearance).press(Key.DirectionCenter, on).theme)
        assertEquals(false, SettingsPanel(SettingsRow.VolumeLevelling).press(Key.DirectionCenter, on).levelling)
    }

    @Test
    fun kidsModeOnWalksPastChangePin() {
        // Kids Mode is the row above Libraries: Change PIN is not a stop while it is on, but is one when it is off.
        assertEquals(SettingsRow.Libraries, SettingsPanel(SettingsRow.KidsMode).press(Key.DirectionDown, on).panel?.row)
        val toChange = SettingsPanel(SettingsRow.KidsMode).press(Key.DirectionDown, offWithPin)
        assertEquals(SettingsRow.ChangePin, toChange.panel?.row)
    }

    @Test
    fun noPinIsInAnyText() {
        val entry = PinEntry(PinPurpose.Change, PinStage.Confirm, digits = "1234", old = "9876", candidate = "5555")
        assertFalse(PinSubmit.SetCap(KidsCap.Family, "1234").toString().contains("1234"))
        val said = entry.toString() + SettingsPanel(pin = entry).toString()
        for (secret in listOf("1234", "9876", "5555")) assertFalse(said.contains(secret))
    }

    private companion object {
        val NUMBER_KEYS =
            mapOf(
                '0' to Key.Zero,
                '1' to Key.One,
                '2' to Key.Two,
                '3' to Key.Three,
                '4' to Key.Four,
                '5' to Key.Five,
                '6' to Key.Six,
                '7' to Key.Seven,
                '8' to Key.Eight,
                '9' to Key.Nine,
            )
    }
}
