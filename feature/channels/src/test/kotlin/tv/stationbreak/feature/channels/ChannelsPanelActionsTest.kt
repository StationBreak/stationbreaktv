// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelChange
import tv.stationbreak.core.domain.lineup.ChannelStatus.Shelf

/** The panel's choice and the number picker live in the state, so a key acts on what the state shows (spec 21). */
class ChannelsPanelActionsTest {
    private val trio = listOf(summary(A, 5), summary(B, 7), summary(C, 9))

    @Test
    fun theChoiceMovesAndStaysInsideTheList() =
        channelsTest(trio) { rig ->
            rig.open(this, current = A)
            rig.vm.select()
            assertEquals(0, rig.ui.choice)
            repeat(5) { rig.vm.panelMove(PanelMove.Down) }
            // Watch, Switch off, Copy to customise, Change number.
            assertEquals(3, rig.ui.choice)
            rig.vm.panelMove(PanelMove.Up)
            assertEquals(2, rig.ui.choice)
            repeat(5) { rig.vm.panelMove(PanelMove.Up) }
            assertEquals(0, rig.ui.choice)
        }

    @Test
    fun watchIsHandedBackToTheCaller() =
        channelsTest(trio) { rig ->
            rig.open(this, current = A)
            rig.vm.select()
            assertEquals(A, rig.vm.panelSelect())
            assertTrue(rig.editor.changes.isEmpty())
        }

    @Test
    fun theOptInQuestionStartsOnCancel() =
        channelsTest(listOf(summary(A, 5), summary(B, 30, Shelf, optIn = true))) { rig ->
            rig.open(this, current = B)
            rig.vm.select()
            assertNull(rig.vm.panelSelect())
            assertEquals(ChannelsPanel.ConfirmOptIn(B), rig.ui.panel)
            assertEquals(1, rig.ui.choice)
            // A second Select at once is Cancel, never the switch-on.
            rig.vm.panelSelect()
            runCurrent()
            assertNull(rig.ui.panel)
            assertTrue(rig.editor.changes.isEmpty())
        }

    @Test
    fun theOptInQuestionSwitchesOnWhenTheViewerMovesToIt() =
        channelsTest(listOf(summary(A, 5), summary(B, 30, Shelf, optIn = true))) { rig ->
            rig.open(this, current = B)
            rig.vm.select()
            rig.vm.panelSelect()
            rig.vm.panelMove(PanelMove.Up)
            rig.vm.panelSelect()
            runCurrent()
            val expected = listOf<ChannelChange>(ChannelChange.SwitchOn(B, confirmedOptIn = true))
            assertEquals(expected, rig.editor.changes.map { it.second })
        }

    @Test
    fun theResetQuestionStartsOnCancel() =
        channelsTest(trio) { rig ->
            rig.open(this, current = A)
            repeat(3) { rig.vm.move(up = false) }
            rig.vm.select()
            assertTrue(rig.ui.confirmingReset)
            assertEquals(1, rig.ui.choice)
            rig.vm.panelSelect()
            runCurrent()
            assertEquals(false, rig.ui.confirmingReset)
            assertTrue(rig.editor.resets.isEmpty())
        }

    @Test
    fun theResetQuestionResetsWhenTheViewerMovesToIt() =
        channelsTest(trio) { rig ->
            rig.open(this, current = A)
            repeat(3) { rig.vm.move(up = false) }
            rig.vm.select()
            rig.vm.panelMove(PanelMove.Up)
            rig.vm.panelSelect()
            runCurrent()
            assertEquals(1, rig.editor.resets.size)
        }

    private suspend fun kotlinx.coroutines.test.TestScope.pickerOn(rig: ChannelsRig) {
        rig.open(this, current = A)
        rig.vm.select()
        repeat(3) { rig.vm.panelMove(PanelMove.Down) }
        rig.vm.panelSelect()
    }

    @Test
    fun theNumberPickerIsMovedAndConfirmedThroughTheState() =
        channelsTest(trio) { rig ->
            pickerOn(rig)
            assertEquals(5, rig.ui.picker?.value)
            // 005: the middle digit up is 015.
            rig.vm.panelMove(PanelMove.Right)
            rig.vm.panelMove(PanelMove.Up)
            assertEquals(15, rig.ui.picker?.value)
            rig.vm.panelSelect()
            runCurrent()
            assertEquals(listOf<ChannelChange>(ChannelChange.SetNumber(A, 15)), rig.editor.changes.map { it.second })
        }

    @Test
    fun selectOnZeroZeroZeroDoesNothing() =
        channelsTest(trio) { rig ->
            pickerOn(rig)
            repeat(2) { rig.vm.panelMove(PanelMove.Right) }
            repeat(5) { rig.vm.panelMove(PanelMove.Down) }
            assertEquals(0, rig.ui.picker?.value)
            rig.vm.panelSelect()
            runCurrent()
            assertTrue(rig.editor.changes.isEmpty())
            assertEquals(ChannelsPanel.Number(A, 5), rig.ui.panel)
        }

    @Test
    fun aResultAfterCloseDoesNotShowAgain() =
        channelsTest(trio) { rig ->
            rig.open(this, current = A)
            rig.gated.changeGate = CompletableDeferred()
            rig.vm.switchOff(A)
            runCurrent()
            rig.vm.close()
            rig.gated.changeGate?.complete(Unit)
            runCurrent()
            assertEquals(ChannelsUi.Empty, rig.ui)
        }
}
