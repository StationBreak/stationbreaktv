// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChangeRefusal
import tv.stationbreak.core.domain.lineup.ChangeResult
import tv.stationbreak.core.domain.lineup.ChannelChange
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.domain.lineup.ResetResult
import tv.stationbreak.feature.channels.builder.BuilderMode
import tv.stationbreak.feature.channels.ui.isLocked

/** The free tier on the channel screen (#793): gated choices stay, are marked, and show a note instead of acting. */
class ChannelsFreeTierTest {
    private val lineup = listOf(summary(A, 5), summary(B, 7, kind = ChannelKind.Custom))

    private fun ChannelsRig.free() = pro.set(false)

    private fun ChannelsRig.pick(choice: Choice) {
        repeat(choicesOf(ui).size) {
            if (choicesOf(ui)[ui.choice] == choice) return vm.panelSelectUnit()
            vm.panelMove(PanelMove.Down)
        }
        error("$choice is not offered: ${choicesOf(ui)}")
    }

    private fun ChannelsViewModel.panelSelectUnit() {
        panelSelect()
    }

    private suspend fun TestScope.openRow(
        rig: ChannelsRig,
        id: tv.stationbreak.core.model.ChannelId,
    ) {
        rig.open(this, current = id)
        rig.vm.select()
    }

    @Test
    fun aStarterChoiceStaysListedAndIsMarkedWhenFree() =
        channelsTest(lineup) { rig ->
            rig.free()
            openRow(rig, A)

            val choices = choicesOf(rig.ui)
            val row = rig.ui.rows.first { it.id == A }
            assertTrue(Choice.SwitchOff in choices && Choice.ChangeNumber in choices)
            assertEquals(ChannelsMessage.NeedsPro, lockOf(Choice.SwitchOff, row, rig.ui.isPro, rig.ui.canAddCustom))
            assertEquals(ChannelsMessage.NeedsPro, lockOf(Choice.ChangeNumber, row, rig.ui.isPro, rig.ui.canAddCustom))
        }

    @Test
    fun selectingAMarkedChoiceShowsTheNoteAndChangesNothing() =
        channelsTest(lineup) { rig ->
            rig.free()
            openRow(rig, A)

            rig.pick(Choice.SwitchOff)
            runCurrent()

            assertEquals(ChannelsMessage.NeedsPro, rig.ui.message)
            assertNull(rig.ui.panel)
            assertTrue(rig.editor.changes.isEmpty())
        }

    @Test
    fun aMarkedNumberChoiceAlsoOnlyShowsTheNote() =
        channelsTest(lineup) { rig ->
            rig.free()
            openRow(rig, A)

            rig.pick(Choice.ChangeNumber)
            runCurrent()

            assertEquals(ChannelsMessage.NeedsPro, rig.ui.message)
            assertNull(rig.ui.picker)
            assertTrue(rig.editor.changes.isEmpty())
        }

    @Test
    fun theViewersOwnCustomChannelIsNotMarkedAndSwitchesOff() =
        channelsTest(lineup) { rig ->
            rig.free()
            openRow(rig, B)
            val row = rig.ui.rows.first { it.id == B }
            assertNull(lockOf(Choice.SwitchOff, row, rig.ui.isPro, rig.ui.canAddCustom))

            rig.pick(Choice.SwitchOff)
            runCurrent()

            assertEquals(listOf<ChannelChange>(ChannelChange.SwitchOff(B)), rig.editor.changes.map { it.second })
            assertNull(rig.ui.message)
        }

    @Test
    fun proSwitchesAStarterOff() =
        channelsTest(lineup) { rig ->
            openRow(rig, A)

            rig.pick(Choice.SwitchOff)
            runCurrent()

            assertEquals(listOf<ChannelChange>(ChannelChange.SwitchOff(A)), rig.editor.changes.map { it.second })
        }

    @Test
    fun theEntitlementFollowsTheFlowWhileTheScreenIsOpen() =
        channelsTest(lineup) { rig ->
            rig.free()
            rig.open(this)
            assertFalse(rig.ui.isPro)

            rig.pro.set(true)
            runCurrent()

            assertTrue(rig.ui.isPro)
        }

    @Test
    fun resetWhenFreeShowsTheNoteAndAsksNothing() =
        channelsTest(lineup) { rig ->
            rig.free()
            rig.open(this)
            rig.vm.askReset()

            assertEquals(ChannelsMessage.NeedsPro, rig.ui.message)
            assertFalse(rig.ui.confirmingReset)
            assertTrue(rig.editor.resets.isEmpty())
        }

    @Test
    fun resetWhenProAsks() =
        channelsTest(lineup) { rig ->
            rig.open(this)
            rig.vm.askReset()

            assertTrue(rig.ui.confirmingReset)
        }

    @Test
    fun newAtTheCapShowsTheLimitNoteAndOpensNoBuilder() =
        channelsTest(lineup) { rig ->
            rig.free()
            rig.builder.canAdd.value = false
            rig.open(this)
            assertFalse(rig.ui.canAddCustom)

            rig.vm.select()

            assertEquals(ChannelsMessage.CustomLimit, rig.ui.message)
            assertNull(rig.ui.builder)
        }

    @Test
    fun newUnderTheCapOpensTheBuilder() =
        channelsTest(lineup) { rig ->
            rig.free()
            rig.open(this)

            rig.vm.select()

            assertEquals(BuilderMode.New, rig.ui.builder)
        }

    @Test
    fun copyToCustomiseAtTheCapShowsTheLimitNote() =
        channelsTest(lineup) { rig ->
            rig.builder.canAdd.value = false
            openRow(rig, A)
            val row = rig.ui.rows.first { it.id == A }
            assertEquals(ChannelsMessage.CustomLimit, lockOf(Choice.CopyToCustomise, row, true, false))

            rig.pick(Choice.CopyToCustomise)
            runCurrent()

            assertEquals(ChannelsMessage.CustomLimit, rig.ui.message)
            assertNull(rig.ui.builder)
        }

    @Test
    fun editingACustomChannelAtTheCapIsFree() =
        channelsTest(lineup) { rig ->
            rig.free()
            rig.builder.canAdd.value = false
            openRow(rig, B)

            rig.pick(Choice.Edit)

            assertEquals(BuilderMode.Edit(B), rig.ui.builder)
        }

    @Test
    fun aDomainRefusalForProShowsTheNote() =
        channelsTest(lineup) { rig ->
            rig.editor.changeResult = ChangeResult.Refused(ChangeRefusal.NeedsPro)
            openRow(rig, A)

            rig.pick(Choice.SwitchOff)
            runCurrent()

            assertEquals(ChannelsMessage.NeedsPro, rig.ui.message)
        }

    @Test
    fun aDomainResetNeedsProShowsTheNote() =
        channelsTest(lineup) { rig ->
            rig.editor.resetResult = ResetResult.NeedsPro
            rig.open(this)
            rig.vm.askReset()
            rig.vm.reset()
            runCurrent()

            assertEquals(ChannelsMessage.NeedsPro, rig.ui.message)
        }

    @Test
    fun aNewPressedWhileLoadingAtTheCapShowsTheLimitNoteWhenTheAccessArrives() =
        channelsTest(lineup) { rig ->
            rig.free()
            rig.builder.canAdd.value = false
            rig.editor.setAccess(LineupAccess.Loading)
            rig.open(this)
            rig.vm.select()
            assertTrue(rig.ui.gettingReady)

            rig.editor.setAccess(LineupAccess.Editable)
            runCurrent()

            assertEquals(ChannelsMessage.CustomLimit, rig.ui.message)
            assertNull(rig.ui.builder)
            assertFalse(rig.ui.gettingReady)
        }

    private val swapLineup =
        listOf(summary(A, 5), summary(B, 7, kind = ChannelKind.Custom), summary(C, 9, kind = ChannelKind.Custom))

    /** B (the viewer's custom) asks for [holder]'s number and the domain offers the swap. */
    private suspend fun TestScope.openSwap(
        rig: ChannelsRig,
        holder: tv.stationbreak.core.model.ChannelId,
    ) {
        rig.open(this)
        rig.editor.changeResult = ChangeResult.Refused(ChangeRefusal.NumberTaken(holder))
        rig.vm.setNumber(B, 5)
        runCurrent()
        rig.editor.changeResult = ChangeResult.Done
        assertTrue(rig.ui.panel is ChannelsPanel.Swap)
    }

    @Test
    fun aSwapWithAStarterIsMarkedAndShowsTheNoteWhenFree() =
        channelsTest(swapLineup) { rig ->
            rig.free()
            openSwap(rig, A)
            val calls = rig.editor.changes.size
            assertTrue(isLocked(Choice.Confirm, null, rig.ui))

            rig.pick(Choice.Confirm)
            runCurrent()

            assertEquals(ChannelsMessage.NeedsPro, rig.ui.message)
            assertNull(rig.ui.panel)
            assertEquals(calls, rig.editor.changes.size)
        }

    @Test
    fun aSwapWithAnOwnCustomChannelIsFreeWhenFree() =
        channelsTest(swapLineup) { rig ->
            rig.free()
            openSwap(rig, C)
            assertFalse(isLocked(Choice.Confirm, null, rig.ui))

            rig.pick(Choice.Confirm)
            runCurrent()

            assertEquals(
                ChannelChange.SetNumber(B, 5, C),
                rig.editor.changes
                    .last()
                    .second,
            )
        }

    @Test
    fun aSwapWithAStarterIsUnmarkedForPro() =
        channelsTest(swapLineup) { rig ->
            openSwap(rig, A)
            assertFalse(isLocked(Choice.Confirm, null, rig.ui))

            rig.pick(Choice.Confirm)
            runCurrent()

            assertEquals(
                ChannelChange.SetNumber(B, 5, A),
                rig.editor.changes
                    .last()
                    .second,
            )
        }

    @Test
    fun theSwapMarkFollowsTheEntitlementLive() =
        channelsTest(swapLineup) { rig ->
            rig.free()
            openSwap(rig, A)
            assertTrue(isLocked(Choice.Confirm, null, rig.ui))

            rig.pro.set(true)
            runCurrent()

            assertFalse(isLocked(Choice.Confirm, null, rig.ui))
        }

    @Test
    fun theProMarkFollowsTheEntitlementAndTheCap() {
        val ui = ChannelsUi.Empty.copy(rows = listOf(starterRow()))
        val row = ui.rows.single()
        assertTrue(isLocked(Choice.SwitchOff, row, ui.copy(isPro = false)))
        assertFalse(isLocked(Choice.SwitchOff, row, ui.copy(isPro = true)))
        assertTrue(isLocked(Choice.CopyToCustomise, row, ui.copy(canAddCustom = false)))
        assertFalse(isLocked(Choice.CopyToCustomise, row, ui.copy(canAddCustom = true)))
        assertFalse(isLocked(Choice.Watch, row, ui.copy(isPro = false, canAddCustom = false)))
    }

    private fun starterRow() =
        ChannelRow(
            A,
            5,
            "A",
            ChannelGroup.On,
            ChannelKind.Starter,
            kid = false,
            optIn = false,
            reason = null,
            hasOverride = false,
        )
}
