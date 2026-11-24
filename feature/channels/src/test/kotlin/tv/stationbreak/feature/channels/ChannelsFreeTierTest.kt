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

/**
 * The free tier on the channel screen with no store (#793, #1071): every Pro choice is listed and answers with the Pro
 * note, which only closes (nothing to buy from); the cap shows the same note. Never a silent no-op.
 */
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
    fun aStarterListsSwitchingAndNumberingWhenFree_andEachAnswersWithTheNoteThatOnlyCloses() =
        channelsTest(lineup) { rig ->
            rig.free()
            openRow(rig, A)
            val expected =
                listOf(Choice.Watch, Choice.SwitchOff, Choice.CopyToCustomise, Choice.ChangeNumber, Choice.About)
            assertEquals(expected, choicesOf(rig.ui))

            for (choice in listOf(Choice.SwitchOff, Choice.ChangeNumber)) {
                rig.pick(choice)
                runCurrent()
                assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reshape, A), rig.ui.panel)
                assertEquals(listOf(Choice.Cancel), choicesOf(rig.ui))
                rig.vm.seePro()
                assertFalse("no purchase screen without a store", rig.ui.seeingPro)
                rig.pick(Choice.Cancel)
                assertNull(rig.ui.panel)
                openRow(rig, A)
            }
            assertTrue("nothing was changed", rig.editor.changes.isEmpty())
        }

    @Test
    fun theDefaultNumberOfAStarterWithAnOverrideAnswersWithTheNoteToo() =
        channelsTest(listOf(summary(A, 5, default = 6))) { rig ->
            rig.free()
            openRow(rig, A)

            rig.pick(Choice.DefaultNumber)

            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reshape, A), rig.ui.panel)
            assertTrue(rig.editor.changes.isEmpty())
        }

    @Test
    fun copyingTheAdultStarterIsTheProNoteWhenFree_andOpensNoBuilder() =
        channelsTest(listOf(summary(A, 5, optIn = true))) { rig ->
            rig.free()
            openRow(rig, A)

            rig.pick(Choice.CopyToCustomise)
            runCurrent()

            assertEquals(ChannelsPanel.NeedsPro(ProNeed.AdultCopy, A), rig.ui.panel)
            assertNull(rig.ui.builder)
        }

    @Test
    fun proCopiesTheAdultStarter() =
        channelsTest(listOf(summary(A, 5, optIn = true))) { rig ->
            openRow(rig, A)

            rig.pick(Choice.CopyToCustomise)
            runCurrent()

            assertEquals(BuilderMode.Copy(A), rig.ui.builder)
        }

    @Test
    fun theViewersOwnCustomChannelStillSwitchesOff() =
        channelsTest(lineup) { rig ->
            rig.free()
            openRow(rig, B)
            assertTrue(Choice.SwitchOff in choicesOf(rig.ui))

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
    fun resetIsAStopWhenFree_andAnswersWithTheNoteThatOnlyCloses() =
        channelsTest(lineup) { rig ->
            rig.free()
            rig.open(this)
            repeat(10) { rig.vm.move(up = false) }
            assertEquals(ChannelsFocus.Reset, rig.ui.focus)

            rig.vm.select()

            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reset), rig.ui.panel)
            assertEquals(listOf(Choice.Cancel), choicesOf(rig.ui))
            assertFalse(rig.ui.confirmingReset)
            assertTrue(rig.editor.resets.isEmpty())
        }

    @Test
    fun theFocusStaysOnResetWhenTheEntitlementEnds() =
        channelsTest(lineup) { rig ->
            rig.open(this)
            repeat(10) { rig.vm.move(up = false) }
            assertEquals(ChannelsFocus.Reset, rig.ui.focus)

            rig.free()
            runCurrent()

            assertEquals(ChannelsFocus.Reset, rig.ui.focus)
        }

    @Test
    fun resetIsTheLastStopWhenPro() =
        channelsTest(lineup) { rig ->
            rig.open(this)
            repeat(10) { rig.vm.move(up = false) }

            assertEquals(ChannelsFocus.Reset, rig.ui.focus)
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

            assertEquals(ChannelsPanel.NeedsPro(ProNeed.CustomLimit), rig.ui.panel)
            assertEquals(listOf(Choice.Cancel), choicesOf(rig.ui))
            assertNull(rig.ui.message)
            assertNull(rig.ui.builder)
        }

    @Test
    fun newWaitsForTheCapBeforeOpeningTheBuilder() =
        channelsTest(lineup) { rig ->
            rig.free()
            rig.builder.capKnown.value = false
            rig.builder.canAdd.value = false
            rig.open(this)

            rig.vm.select()
            runCurrent()

            // No builder, and no note, on a cap that is not known yet.
            assertNull(rig.ui.builder)
            assertNull(rig.ui.panel)

            rig.builder.capKnown.value = true
            runCurrent()

            assertNull(rig.ui.builder)
            assertEquals(ChannelsPanel.NeedsPro(ProNeed.CustomLimit), rig.ui.panel)
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
            assertTrue(Choice.CopyToCustomise in choicesOf(rig.ui))

            rig.pick(Choice.CopyToCustomise)
            runCurrent()

            assertEquals(ChannelsPanel.NeedsPro(ProNeed.CustomLimit, A), rig.ui.panel)
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
    fun proLostBetweenTheChoiceAndTheChange_showsTheProNote_notTheGenericFailure() =
        channelsTest(lineup) { rig ->
            rig.editor.changeResult = ChangeResult.Refused(ChangeRefusal.NeedsPro)
            openRow(rig, A)

            rig.pick(Choice.SwitchOff)
            runCurrent()

            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reshape, A), rig.ui.panel)
            assertNull(rig.ui.message)
        }

    @Test
    fun proLostWhileAResetRan_showsTheProNote_notTheGenericFailure() =
        channelsTest(lineup) { rig ->
            rig.editor.resetResult = ResetResult.NeedsPro
            rig.open(this)
            rig.vm.askReset()
            rig.vm.reset()
            runCurrent()

            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reset), rig.ui.panel)
            assertNull(rig.ui.message)
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

            assertEquals(ChannelsPanel.NeedsPro(ProNeed.CustomLimit), rig.ui.panel)
            assertNull(rig.ui.builder)
            assertFalse(rig.ui.gettingReady)
        }

    @Test
    fun aDefaultNumberAStarterHoldsIsOnlyTakenWhenFree() =
        channelsTest(swapLineup) { rig ->
            rig.free()
            rig.open(this)
            rig.editor.changeResult = ChangeResult.Refused(ChangeRefusal.NumberTaken(A))

            rig.vm.defaultNumber(B)
            runCurrent()

            assertEquals(ChannelsMessage.DefaultNumberTaken, rig.ui.message)
            assertNull(rig.ui.panel)
        }

    @Test
    fun aDefaultNumberAStarterHoldsIsOfferedAsASwapToPro() =
        channelsTest(swapLineup) { rig ->
            rig.open(this)
            rig.editor.changeResult = ChangeResult.Refused(ChangeRefusal.NumberTaken(A))

            rig.vm.defaultNumber(B)
            runCurrent()

            assertTrue(rig.ui.panel is ChannelsPanel.Swap)
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
    fun aNumberAStarterHoldsIsOnlyTakenWhenFree() =
        channelsTest(swapLineup) { rig ->
            rig.free()
            rig.open(this)
            rig.editor.changeResult = ChangeResult.Refused(ChangeRefusal.NumberTaken(A))
            val calls = rig.editor.changes.size
            rig.vm.setNumber(B, 5)
            runCurrent()

            assertEquals(ChannelsMessage.NumberTaken, rig.ui.message)
            assertNull(rig.ui.panel)
            assertEquals(calls + 1, rig.editor.changes.size)
        }

    @Test
    fun aSwapWithAnOwnCustomChannelIsFreeWhenFree() =
        channelsTest(swapLineup) { rig ->
            rig.free()
            openSwap(rig, C)

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
    fun aSwapWithAStarterIsOfferedToPro() =
        channelsTest(swapLineup) { rig ->
            openSwap(rig, A)

            rig.pick(Choice.Confirm)
            runCurrent()

            assertEquals(
                ChannelChange.SetNumber(B, 5, A),
                rig.editor.changes
                    .last()
                    .second,
            )
        }
}
