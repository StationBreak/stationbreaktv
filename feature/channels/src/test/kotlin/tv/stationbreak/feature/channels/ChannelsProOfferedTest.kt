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
import tv.stationbreak.core.domain.lineup.ChannelChange
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.builder.BuilderMode

/**
 * The free tier with the store available (#99): Pro choices are listed and answer with a note and the way to buy. With
 * the store unavailable (the default of the rig, #1071) the note is the same and only closes: [ChannelsFreeTierTest].
 */
class ChannelsProOfferedTest {
    private val lineup = listOf(summary(A, 5), summary(B, 7, kind = ChannelKind.Custom))

    private fun ChannelsRig.freeAndOffered() {
        pro.set(false)
        offered.value = true
    }

    private fun ChannelsRig.pick(choice: Choice) {
        repeat(choicesOf(ui).size) {
            if (choicesOf(ui)[ui.choice] == choice) {
                vm.panelSelect()
                return
            }
            vm.panelMove(PanelMove.Down)
        }
        error("$choice is not offered: ${choicesOf(ui)}")
    }

    private suspend fun TestScope.openRow(
        rig: ChannelsRig,
        id: ChannelId,
    ) {
        rig.open(this, current = id)
        rig.vm.select()
    }

    @Test
    fun withTheStoreAvailable_aStarterIsListedSwitchingAndNumbering_andChoosingOneShowsTheNote() =
        channelsTest(lineup) { rig ->
            rig.freeAndOffered()
            openRow(rig, A)
            assertTrue(Choice.SwitchOff in choicesOf(rig.ui))
            assertTrue(Choice.ChangeNumber in choicesOf(rig.ui))

            rig.pick(Choice.SwitchOff)
            runCurrent()

            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reshape, A), rig.ui.panel)
            assertEquals(listOf(Choice.SeePro, Choice.Cancel), choicesOf(rig.ui))
            assertTrue("nothing was changed", rig.editor.changes.isEmpty())
        }

    @Test
    fun withTheStoreUnavailable_theNoteIsTheSameButOffersNoWayToBuy() =
        channelsTest(lineup) { rig ->
            rig.pro.set(false)
            openRow(rig, A)
            rig.pick(Choice.SwitchOff)
            runCurrent()

            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reshape, A), rig.ui.panel)
            assertEquals(listOf(Choice.Cancel), choicesOf(rig.ui))
            rig.vm.seePro()
            assertFalse(rig.ui.seeingPro)
        }

    @Test
    fun seeProOpensThePurchaseScreen_backComesBackToTheNote_andCancelClosesIt() =
        channelsTest(lineup) { rig ->
            rig.freeAndOffered()
            openRow(rig, A)
            rig.pick(Choice.ChangeNumber)

            rig.pick(Choice.SeePro)
            assertTrue(rig.ui.seeingPro)

            rig.vm.proDone()
            assertFalse(rig.ui.seeingPro)
            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reshape, A), rig.ui.panel)

            rig.pick(Choice.Cancel)
            assertNull(rig.ui.panel)
            assertTrue(rig.editor.changes.isEmpty())
        }

    @Test
    fun theNoteClosesWhenProWasBought_andTheChoiceThenActs() =
        channelsTest(lineup) { rig ->
            rig.freeAndOffered()
            openRow(rig, A)
            rig.pick(Choice.SwitchOff)
            rig.pick(Choice.SeePro)

            rig.pro.set(true)
            runCurrent()
            rig.vm.proDone()

            assertNull(rig.ui.panel)
            openRow(rig, A)
            rig.pick(Choice.SwitchOff)
            runCurrent()
            assertEquals(listOf<ChannelChange>(ChannelChange.SwitchOff(A)), rig.editor.changes.map { it.second })
        }

    @Test
    fun aNoteClosesWhenProArrivesAnotherWay_withoutBackFromThePurchaseScreen() =
        channelsTest(lineup) { rig ->
            rig.freeAndOffered()
            openRow(rig, A)
            rig.pick(Choice.SwitchOff)
            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reshape, A), rig.ui.panel)

            rig.pro.set(true)
            runCurrent()

            assertNull(rig.ui.panel)
            assertFalse(rig.ui.seeingPro)
        }

    @Test
    fun aNoteUnderThePurchaseScreenClosesWhenProArrives_andThePurchaseScreenStaysUntilBack() =
        channelsTest(lineup) { rig ->
            rig.freeAndOffered()
            openRow(rig, A)
            rig.pick(Choice.SwitchOff)
            rig.pick(Choice.SeePro)

            rig.pro.set(true)
            runCurrent()

            assertNull(rig.ui.panel)
            assertTrue(rig.ui.seeingPro)
            rig.vm.proDone()
            assertFalse(rig.ui.seeingPro)
        }

    @Test
    fun aNoteStaysWhenProIsStillNotOwned() =
        channelsTest(lineup) { rig ->
            rig.freeAndOffered()
            openRow(rig, A)
            rig.pick(Choice.SwitchOff)

            rig.pro.set(false)
            runCurrent()

            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reshape, A), rig.ui.panel)
        }

    @Test
    fun aRefund_closesTheGatesAgain_soSwitchOffAndChangeNumberOnAStarterShowTheNote() =
        channelsTest(lineup) { rig ->
            rig.freeAndOffered()
            rig.pro.set(true)
            runCurrent()
            openRow(rig, A)
            rig.pick(Choice.SwitchOff)
            runCurrent()
            assertEquals(listOf<ChannelChange>(ChannelChange.SwitchOff(A)), rig.editor.changes.map { it.second })

            rig.pro.set(false)
            runCurrent()
            openRow(rig, A)
            rig.pick(Choice.SwitchOff)
            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reshape, A), rig.ui.panel)
            rig.pick(Choice.Cancel)
            openRow(rig, A)
            rig.pick(Choice.ChangeNumber)
            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reshape, A), rig.ui.panel)
            assertEquals("nothing more was changed", 1, rig.editor.changes.size)
        }

    @Test
    fun aProNotePanelNeverResets_whateverIsChosenOnIt() =
        channelsTest(lineup) { rig ->
            rig.freeAndOffered()
            rig.open(this)
            repeat(10) { rig.vm.move(up = false) }
            rig.vm.select()
            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reset), rig.ui.panel)
            rig.pick(Choice.Cancel)
            assertTrue(rig.editor.resets.isEmpty())
        }

    @Test
    fun resetIsAStopAndAsksWithTheNote_whateverTheStore() =
        channelsTest(lineup) { rig ->
            rig.freeAndOffered()
            rig.open(this)
            repeat(10) { rig.vm.move(up = false) }
            assertEquals(ChannelsFocus.Reset, rig.ui.focus)

            rig.vm.select()

            assertEquals(ChannelsPanel.NeedsPro(ProNeed.Reset), rig.ui.panel)
            assertFalse(rig.ui.confirmingReset)
            assertTrue(rig.editor.resets.isEmpty())

            rig.offered.value = false
            runCurrent()
            assertEquals(ChannelsFocus.Reset, rig.ui.focus)
            assertEquals(listOf(Choice.Cancel), choicesOf(rig.ui))
        }

    @Test
    fun atTheCap_newShowsTheNote_withTheWayToBuyOnlyWhenOffered() =
        channelsTest(lineup) { rig ->
            rig.freeAndOffered()
            rig.builder.canAdd.value = false
            rig.open(this)
            rig.vm.select()
            assertEquals(ChannelsPanel.NeedsPro(ProNeed.CustomLimit), rig.ui.panel)
            assertNull(rig.ui.builder)

            rig.vm.dismiss()
            rig.offered.value = false
            runCurrent()
            rig.vm.select()
            assertEquals(ChannelsPanel.NeedsPro(ProNeed.CustomLimit), rig.ui.panel)
            assertEquals(listOf(Choice.Cancel), choicesOf(rig.ui))
            assertNull(rig.ui.message)
        }

    @Test
    fun theNoteOffersTheWayToBuyOnlyWithAStore() {
        val row =
            ChannelRow(
                id = A,
                number = 5,
                name = "T",
                group = ChannelGroup.On,
                kind = ChannelKind.Starter,
                kid = false,
                optIn = false,
                reason = null,
                hasOverride = false,
            )
        assertTrue(Choice.SwitchOff in actionsOf(row, LineupAccess.Editable, reasonsRead = true))
        val note = ChannelsUi.Empty.copy(panel = ChannelsPanel.NeedsPro(ProNeed.Reset))
        assertEquals(listOf(Choice.SeePro, Choice.Cancel), choicesOf(note.copy(proOffered = true)))
        assertEquals(listOf(Choice.Cancel), choicesOf(note))
        assertEquals(ProNeed.Reshape, proNeedOf(Choice.SwitchOn, row, isPro = false, canAddCustom = true))
        assertNull(proNeedOf(Choice.SwitchOn, row, isPro = true, canAddCustom = true))
        assertEquals(ProNeed.CustomLimit, proNeedOf(Choice.CopyToCustomise, row, isPro = true, canAddCustom = false))
    }

    @Test
    fun theProNoteIsHeardWithItsFirstChoice() {
        val ui = ChannelsUi.Empty.copy(panel = ChannelsPanel.NeedsPro(ProNeed.Reset), proOffered = true)
        assertEquals(listOf(Choice.SeePro, Choice.Cancel), choicesOf(ui))
        assertEquals(0, startChoice(ui.panel, confirmingReset = false))
    }

    @Test
    fun seeProFromTheBuilderOpensThePurchaseScreenOverIt_andBackLeavesTheBuilderOpen() =
        channelsTest(lineup) { rig ->
            rig.freeAndOffered()
            rig.open(this)
            rig.vm.select()
            assertEquals(BuilderMode.New, rig.ui.builder)

            rig.vm.seeProFromBuilder()
            assertTrue(rig.ui.seeingPro)

            rig.vm.proDone()
            assertFalse(rig.ui.seeingPro)
            assertEquals(BuilderMode.New, rig.ui.builder)
        }

    @Test
    fun seeProFromTheBuilderDoesNothingWithoutAStore_orWithoutTheBuilder() =
        channelsTest(lineup) { rig ->
            rig.pro.set(false)
            rig.open(this)
            rig.vm.select()
            rig.vm.seeProFromBuilder()
            assertFalse(rig.ui.seeingPro)

            rig.offered.value = true
            runCurrent()
            rig.vm.builderClosed()
            rig.vm.seeProFromBuilder()
            assertFalse("no builder is showing", rig.ui.seeingPro)
        }
}
