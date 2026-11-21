// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.kids.PinRecovery
import tv.stationbreak.core.domain.kids.PinResult

/** Forgot PIN? on the PIN pad and the reset it opens (#789): where the key is, what OK does, and the reset's keys. */
class KidsPinResetTest {
    private val values = SettingsValues(kidsOn = true, kidsPinSet = true)

    private fun SettingsPanel.press(key: Key): SettingsStep =
        checkNotNull(settingsStep(this, values, key, KeyEventType.KeyDown, 0)) { "$key was not kept" }

    private fun SettingsPanel.focusedForgot(): SettingsPanel {
        var panel = this
        repeat(3) { panel = checkNotNull(panel.press(Key.DirectionDown).panel) }
        return panel
    }

    private val turnOff = SettingsPanel(SettingsRow.KidsMode, pin = PinEntry.start(PinPurpose.TurnOff))
    private val asking = SettingsPanel(SettingsRow.KidsMode, reset = PinReset(PinRecovery.Code("123456")))

    private fun resetOf(recovery: PinRecovery?) = SettingsPanel(SettingsRow.KidsMode, reset = PinReset(recovery))

    @Test
    fun forgotIsTheLastRowOfThePadsThatAskForTheCurrentPin() {
        for (purpose in listOf(PinPurpose.TurnOff, PinPurpose.Change, PinPurpose.Cap)) {
            val panel = SettingsPanel(pin = PinEntry.start(purpose)).focusedForgot()
            assertEquals(purpose.name, PadKey.Forgot, panel.pin?.focus)
            assertEquals(PadKey.Forgot, checkNotNull(panel.press(Key.DirectionDown).panel).pin?.focus)
            assertEquals(PadKey.Delete, checkNotNull(panel.press(Key.DirectionUp).panel).pin?.focus)
        }
    }

    @Test
    fun forgotIsNotOnTheSetPad_orAfterTheCurrentPinIsKnown() {
        val set = SettingsPanel(pin = PinEntry.start(PinPurpose.Set)).focusedForgot()
        assertEquals(PadKey.Delete, set.pin?.focus)
        val next = SettingsPanel(pin = PinEntry.start(PinPurpose.Change).copy(stage = PinStage.New)).focusedForgot()
        assertEquals(PadKey.Delete, next.pin?.focus)
        assertFalse(padRows(PinEntry.start(PinPurpose.Set).forgettable).flatten().contains(PadKey.Forgot))
        assertTrue(padRows(PinEntry.start(PinPurpose.TurnOff).forgettable).flatten().contains(PadKey.Forgot))
    }

    @Test
    fun okOnForgotOpensTheResetInThePadsPlace_andAsksForARequest() {
        val step = turnOff.focusedForgot().press(Key.DirectionCenter)
        assertEquals(SettingsStep(SettingsPanel(SettingsRow.KidsMode, reset = PinReset()), resetStart = true), step)
    }

    @Test
    fun okOnForgotWorksWhileTheLockRefusesPins_butNotWhileAnAnswerIsAwaited() {
        val refusing = PinNotice.LockedOut(60_000)
        val locked = PinEntry.start(PinPurpose.TurnOff).copy(notice = refusing, focus = PadKey.Forgot)
        assertTrue(SettingsPanel(pin = locked).press(Key.DirectionCenter).resetStart)
        val busy = SettingsPanel(pin = locked.copy(notice = null, busy = true))
        assertEquals(SettingsStep(busy), busy.press(Key.DirectionCenter))
    }

    @Test
    fun aFocusOnForgotMovesToTheMiddleOfThePadWhenTheCurrentPinWasRight() {
        val change = PinEntry.start(PinPurpose.Change).copy(digits = "1234", busy = true, focus = PadKey.Forgot)
        val next = checkNotNull(SettingsPanel(pin = change).pinAnswered(PinResult.Ok).pin)
        assertEquals(PinStage.New, next.stage)
        assertEquals(PadKey.D5, next.focus)
    }

    @Test
    fun backCancelsTheReset_atEveryStep() {
        for (recovery in listOf(null, PinRecovery.Code("123456"), PinRecovery.Expired, PinRecovery.Done)) {
            val reset = resetOf(recovery)
            // Back acts on its release; the press's own down does nothing.
            assertEquals(SettingsStep(reset), reset.press(Key.Back))
            val step = checkNotNull(settingsStep(reset, values, Key.Back, KeyEventType.KeyUp, 0))
            assertNull(recovery.toString(), step.panel?.reset)
            assertEquals(SettingsRow.KidsMode, step.panel?.row)
            assertFalse(step.resetStart)
        }
    }

    @Test
    fun okDoesNothingWhileTheRequestIsWaiting() {
        assertEquals(SettingsStep(resetOf(null)), resetOf(null).press(Key.DirectionCenter))
        assertEquals(SettingsStep(asking), asking.press(Key.DirectionCenter))
    }

    @Test
    fun okAfterAFailureAsksAgain_andAfterDoneCloses() {
        val failures =
            listOf(
                PinRecovery.NoServer,
                PinRecovery.NotAdministrator,
                PinRecovery.Expired,
                PinRecovery.Denied,
                PinRecovery.Disabled,
                PinRecovery.Unreachable,
                PinRecovery.Unavailable,
            )
        for (failure in failures) {
            val step = resetOf(failure).press(Key.DirectionCenter)
            assertEquals(failure.toString(), SettingsStep(resetOf(null), resetStart = true), step)
        }
        val done = resetOf(PinRecovery.Done).press(Key.DirectionCenter)
        assertEquals(SettingsStep(SettingsPanel(SettingsRow.KidsMode)), done)
    }

    @Test
    fun theOtherKeysAreKeptAndDoNothing_andTheMediaKeysAreNotTheSheets() {
        for (key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
            assertEquals(SettingsStep(asking), asking.press(key))
        }
        assertNull(settingsStep(asking, values, Key.MediaPlayPause, KeyEventType.KeyDown, 0))
        val held = settingsStep(resetOf(PinRecovery.Expired), values, Key.DirectionCenter, KeyEventType.KeyDown, 1)
        assertEquals(SettingsStep(resetOf(PinRecovery.Expired)), held)
    }

    @Test
    fun theRecoveryShowsItsStatesInOrder_andIsIgnoredOnceTheResetIsClosed() {
        val started = resetOf(null)
        val code = started.recovered(PinRecovery.Code("123456"))
        assertEquals(PinReset(PinRecovery.Code("123456")), code.reset)
        assertFalse(checkNotNull(code.reset).finished)
        val done = code.recovered(PinRecovery.Done)
        assertTrue(checkNotNull(done.reset).finished)
        val closed = SettingsPanel(SettingsRow.KidsMode)
        assertEquals(closed, closed.recovered(PinRecovery.Expired))
    }
}
