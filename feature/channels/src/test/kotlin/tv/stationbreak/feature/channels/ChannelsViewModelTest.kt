// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChangeRefusal
import tv.stationbreak.core.domain.lineup.ChangeResult
import tv.stationbreak.core.domain.lineup.ChannelChange
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelStatus.On
import tv.stationbreak.core.domain.lineup.ChannelStatus.Shelf
import tv.stationbreak.core.domain.lineup.ChannelStatus.UserHidden
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.domain.lineup.ResetResult
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.builder.BuilderMode

class ChannelsViewModelTest {
    private val trio = listOf(summary(A, 5), summary(B, 7), summary(C, 9))

    @Test
    fun rowsAreGroupedOnShelfOffInNumberOrder() =
        channelsTest(
            listOf(
                summary(D, 2, UserHidden),
                summary(C, 9, Shelf),
                summary(B, 7, On),
                summary(A, 12, On),
            ),
        ) { rig ->
            rig.open(this)
            assertEquals(listOf(B, A, C, D), rig.ui.rows.map { it.id })
            assertEquals(
                listOf(ChannelGroup.On, ChannelGroup.On, ChannelGroup.Shelf, ChannelGroup.Off),
                rig.ui.rows.map { it.group },
            )
        }

    @Test
    fun numberlessChannelsSortLastInTheirGroup() =
        channelsTest(listOf(summary(A, null, Shelf), summary(B, 40, Shelf), summary(C, 30, Shelf))) { rig ->
            rig.open(this)
            assertEquals(listOf(C, B, A), rig.ui.rows.map { it.id })
        }

    @Test
    fun onChannelsCarryTheirReason() =
        channelsTest(listOf(summary(A, 5), summary(B, 7), summary(C, 9, Shelf))) { rig ->
            rig.schedule.channel(A, 5, emptyList(), HiddenReason.EmptyToday)
            rig.schedule.channel(B, 7, emptyList())
            rig.schedule.channel(C, 9, emptyList(), HiddenReason.NoMatches)
            rig.open(this)
            assertEquals(listOf(HiddenReason.EmptyToday, null, null), rig.ui.rows.map { it.reason })
        }

    @Test
    fun overrideShowsWhenTheNumberIsNotTheDefault() =
        channelsTest(
            listOf(summary(A, 5, default = 8), summary(B, 7), summary(C, null, Shelf, default = null)),
        ) { rig ->
            rig.open(this)
            assertEquals(listOf(true, false, false), rig.ui.rows.map { it.hasOverride })
        }

    @Test
    fun switchingOnAnOptInChannelAsksFirst() =
        channelsTest(listOf(summary(A, 5), summary(B, 7, Shelf, optIn = true))) { rig ->
            rig.open(this)
            rig.vm.switchOn(B)
            runCurrent()
            assertEquals(ChannelsPanel.ConfirmOptIn(B), rig.ui.panel)
            assertTrue(rig.editor.changes.isEmpty())
        }

    @Test
    fun confirmedOptInIsSentOnlyAfterTheConfirm() =
        channelsTest(listOf(summary(A, 5), summary(B, 7, Shelf, optIn = true))) { rig ->
            rig.open(this)
            rig.vm.switchOn(B)
            runCurrent()
            assertTrue(rig.editor.changes.isEmpty())
            rig.vm.confirmOptIn()
            runCurrent()
            val sent = rig.editor.changes.map { it.second }
            assertEquals(listOf<ChannelChange>(ChannelChange.SwitchOn(B, confirmedOptIn = true)), sent)
            assertNull(rig.ui.panel)
        }

    @Test
    fun plainSwitchOnAndOffAreSent() =
        channelsTest(listOf(summary(A, 5), summary(B, 7, Shelf))) { rig ->
            rig.open(this)
            rig.vm.switchOn(B)
            runCurrent()
            rig.vm.switchOff(A)
            runCurrent()
            assertEquals(
                listOf(ChannelChange.SwitchOn(B, false), ChannelChange.SwitchOff(A)),
                rig.editor.changes.map { it.second },
            )
            assertEquals(
                rig.account,
                rig.editor.changes
                    .first()
                    .first,
            )
        }

    @Test
    fun numberTakenOpensTheSwapPanel() =
        channelsTest(trio) { rig ->
            rig.open(this)
            rig.editor.changeResult = ChangeResult.Refused(ChangeRefusal.NumberTaken(B))
            rig.vm.setNumber(A, 7)
            runCurrent()
            assertEquals(ChannelsPanel.Swap(A, 7, "Test ch-b", toDefault = false), rig.ui.panel)
            assertNull(rig.ui.message)
        }

    @Test
    fun swapPanelNamesTheHolderFromTheList() =
        channelsTest(listOf(summary(A, 5), summary(B, 7, name = "Test Movies"))) { rig ->
            rig.open(this)
            rig.editor.changeResult = ChangeResult.Refused(ChangeRefusal.NumberTaken(B))
            rig.vm.setNumber(A, 7)
            runCurrent()
            assertEquals("Test Movies", (rig.ui.panel as ChannelsPanel.Swap).holderName)
            rig.editor.changeResult = ChangeResult.Refused(ChangeRefusal.NumberTaken(D))
            rig.vm.setNumber(A, 7)
            runCurrent()
            assertEquals("", (rig.ui.panel as ChannelsPanel.Swap).holderName)
        }

    @Test
    fun aSwapPassesTheHolderItNamed() =
        channelsTest(listOf(summary(A, 5, default = 3), summary(B, 7), summary(C, 3, Shelf))) { rig ->
            rig.open(this)
            rig.editor.changeResult = ChangeResult.Refused(ChangeRefusal.NumberTaken(B))
            rig.vm.setNumber(A, 7)
            runCurrent()
            rig.editor.changeResult = ChangeResult.Done
            rig.vm.swap()
            runCurrent()
            assertEquals(
                ChannelChange.SetNumber(A, 7, swapWith = B),
                rig.editor.changes
                    .last()
                    .second,
            )
            assertNull(rig.ui.panel)

            rig.editor.changeResult = ChangeResult.Refused(ChangeRefusal.NumberTaken(C))
            rig.vm.defaultNumber(A)
            runCurrent()
            assertEquals(
                ChannelChange.ClearNumber(A, null),
                rig.editor.changes
                    .last()
                    .second,
            )
            assertEquals(ChannelsPanel.Swap(A, 3, "Test ch-c", toDefault = true), rig.ui.panel)
            rig.editor.changeResult = ChangeResult.Done
            rig.vm.swap()
            runCurrent()
            assertEquals(
                ChannelChange.ClearNumber(A, swapWith = C),
                rig.editor.changes
                    .last()
                    .second,
            )
        }

    private suspend fun TestScope.refusal(
        result: ChangeResult,
        expected: ChannelsMessage,
    ) {
        val rig = ChannelsRig(this, trio)
        rig.open(this)
        rig.vm.select()
        rig.editor.changeResult = result
        rig.vm.switchOff(A)
        runCurrent()
        assertEquals(expected, rig.ui.message)
        assertNull(rig.ui.panel)
    }

    @Test
    fun kidsBandRefusalShowsItsMessage() =
        channelsTest { refusal(ChangeResult.Refused(ChangeRefusal.KidsBandOnly), ChannelsMessage.KidsBand) }

    @Test
    fun lastOnRefusalShowsItsMessage() =
        channelsTest {
            refusal(ChangeResult.Refused(ChangeRefusal.LastOn), ChannelsMessage.LastOn)
        }

    @Test
    fun otherRefusalsAndFailuresShowTheirMessages() =
        channelsTest {
            refusal(ChangeResult.Refused(ChangeRefusal.NoFreeNumber), ChannelsMessage.NoNumber)
            refusal(ChangeResult.NotEditable, ChannelsMessage.NotEditable)
            refusal(ChangeResult.Failed, ChannelsMessage.Failed)
            refusal(ChangeResult.Refused(ChangeRefusal.OutOfRange), ChannelsMessage.Failed)
            refusal(ChangeResult.Refused(ChangeRefusal.Invalid), ChannelsMessage.Failed)
            refusal(ChangeResult.Refused(ChangeRefusal.UnknownChannel), ChannelsMessage.Failed)
            refusal(ChangeResult.Refused(ChangeRefusal.NotSwitchable), ChannelsMessage.Failed)
            refusal(ChangeResult.Refused(ChangeRefusal.OptInNotConfirmed), ChannelsMessage.Failed)
        }

    @Test
    fun saveFailedShowsItsMessage() = channelsTest { refusal(ChangeResult.SaveFailed, ChannelsMessage.SaveFailed) }

    @Test
    fun readOnlyAccessDisablesChanges() =
        channelsTest(trio) { rig ->
            rig.editor.setAccess(LineupAccess.ReadOnly)
            rig.open(this)
            assertEquals(LineupAccess.ReadOnly, rig.ui.access)
            rig.vm.switchOff(A)
            rig.vm.setNumber(A, 20)
            rig.vm.askReset()
            runCurrent()
            assertTrue(rig.editor.changes.isEmpty())
            assertEquals(false, rig.ui.confirmingReset)
        }

    @Test
    fun loadingAccessShowsNoActions() =
        channelsTest(trio) { rig ->
            rig.editor.setAccess(LineupAccess.Loading)
            rig.open(this)
            assertEquals(LineupAccess.Loading, rig.ui.access)
            rig.vm.switchOff(A)
            rig.vm.reset()
            runCurrent()
            assertTrue(rig.editor.changes.isEmpty())
            assertTrue(rig.editor.resets.isEmpty())
        }

    @Test
    fun librariesUnavailableShowsItsMessage() =
        channelsTest(trio) { rig ->
            rig.open(this)
            rig.editor.resetResult = ResetResult.LibrariesUnavailable
            rig.vm.askReset()
            rig.vm.reset()
            runCurrent()
            assertEquals(ChannelsMessage.LibrariesUnavailable, rig.ui.message)
            assertEquals(false, rig.ui.confirmingReset)
        }

    @Test
    fun resetShowsChoosingUntilItReturns() =
        channelsTest(trio) { rig ->
            rig.open(this)
            val gate = CompletableDeferred<Unit>()
            rig.gated.resetGate = gate
            rig.vm.askReset()
            assertTrue(rig.ui.confirmingReset)
            rig.vm.reset()
            runCurrent()
            assertTrue(rig.ui.choosing)
            assertEquals(false, rig.ui.confirmingReset)
            gate.complete(Unit)
            runCurrent()
            assertEquals(false, rig.ui.choosing)
        }

    @Test
    fun closeStopsReadingReasons() =
        channelsTest(trio) { rig ->
            rig.open(this)
            val reads = rig.schedule.airingsCalls
            rig.repo.list.value = listOf(summary(A, 6), summary(B, 7), summary(C, 9))
            runCurrent()
            rig.vm.close()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(reads, rig.schedule.airingsCalls)
            rig.repo.list.value = listOf(summary(A, 8), summary(B, 7), summary(C, 9))
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(reads, rig.schedule.airingsCalls)
            // Nothing of the visit is shown again when the screen comes back.
            assertEquals(ChannelsUi.Empty, rig.ui)
        }

    @Test
    fun aDoublePressSendsOneChange() =
        channelsTest(trio) { rig ->
            rig.open(this)
            rig.gated.changeGate = CompletableDeferred()
            rig.vm.switchOff(A)
            rig.vm.switchOff(A)
            runCurrent()
            rig.gated.changeGate?.complete(Unit)
            runCurrent()
            assertEquals(1, rig.editor.changes.size)
            rig.vm.switchOff(B)
            runCurrent()
            assertEquals(2, rig.editor.changes.size)
        }

    @Test
    fun aResultLeavesAnotherChannelsPanelOpen() =
        channelsTest(trio) { rig ->
            rig.open(this, current = A)
            rig.gated.changeGate = CompletableDeferred()
            rig.vm.switchOff(A)
            runCurrent()
            rig.vm.move(false)
            rig.vm.select()
            assertEquals(ChannelsPanel.Actions(B), rig.ui.panel)
            rig.gated.changeGate?.complete(Unit)
            runCurrent()
            assertEquals(ChannelsPanel.Actions(B), rig.ui.panel)
        }

    @Test
    fun anActionWithoutAccessClosesThePanelAndSaysSo() =
        channelsTest(trio) { rig ->
            rig.open(this)
            rig.vm.select()
            rig.editor.setAccess(LineupAccess.ReadOnly)
            runCurrent()
            rig.vm.switchOff(A)
            assertNull(rig.ui.panel)
            assertEquals(ChannelsMessage.NotEditable, rig.ui.message)
            assertTrue(rig.editor.changes.isEmpty())
        }

    @Test
    fun selectOpensActionsWhateverTheAccess() =
        channelsTest(trio) { rig ->
            for (access in listOf(LineupAccess.ReadOnly, LineupAccess.Loading)) {
                rig.editor.setAccess(access)
                rig.open(this, current = A)
                rig.vm.select()
                assertEquals(ChannelsPanel.Actions(A), rig.ui.panel)
            }
        }

    @Test
    fun anEmptyingListMovesTheFocusToReset() =
        channelsTest(trio) { rig ->
            rig.open(this, current = B)
            rig.repo.list.value = emptyList()
            runCurrent()
            assertEquals(ChannelsFocus.Reset, rig.ui.focus)
        }

    @Test
    fun aStaleResultIsDropped() =
        channelsTest(trio) { rig ->
            rig.open(this)
            rig.gated.changeGate = CompletableDeferred()
            rig.editor.changeResult = ChangeResult.Failed
            rig.vm.switchOff(A)
            runCurrent()
            rig.vm.open(null)
            runCurrent()
            rig.gated.changeGate?.complete(Unit)
            runCurrent()
            assertNull(rig.ui.message)
            rig.editor.changeResult = ChangeResult.Done
            rig.vm.switchOff(B)
            runCurrent()
            assertEquals(2, rig.editor.changes.size)
        }

    @Test
    fun closingAfterAResetThatReturnsFirstWaitsForTheNewList() =
        channelsTest(trio) { rig ->
            rig.open(this, current = C)
            rig.vm.reset()
            runCurrent()
            assertNull(rig.vm.closing())
            rig.repo.list.value = listOf(summary(D, 3), summary(C, 9))
            runCurrent()
            assertEquals(D, rig.vm.closing())
        }

    @Test
    fun resetNotChosenYetShowsItsMessage() =
        channelsTest(trio) { rig ->
            rig.open(this)
            rig.editor.resetResult = ResetResult.NotChosenYet
            rig.vm.reset()
            runCurrent()
            assertEquals(ChannelsMessage.NotChosenYet, rig.ui.message)
            assertNull(rig.vm.closing())
        }

    @Test
    fun resetFailuresShowTheirMessages() =
        channelsTest(trio) { rig ->
            rig.open(this)
            for ((result, message) in listOf(
                ResetResult.NotEditable to ChannelsMessage.NotEditable,
                ResetResult.SaveFailed to ChannelsMessage.SaveFailed,
                ResetResult.Failed to ChannelsMessage.Failed,
            )) {
                rig.editor.resetResult = result
                rig.vm.reset()
                runCurrent()
                assertEquals(message, rig.ui.message)
                rig.vm.dismiss()
            }
        }

    @Test
    fun focusFollowsARowThatMovesGroup() =
        channelsTest(trio) { rig ->
            rig.open(this, current = B)
            assertEquals(ChannelsFocus.Row(B), rig.ui.focus)
            rig.repo.list.value = listOf(summary(A, 5), summary(B, 7, UserHidden), summary(C, 9))
            runCurrent()
            assertEquals(listOf(A, C, B), rig.ui.rows.map { it.id })
            assertEquals(ChannelsFocus.Row(B), rig.ui.focus)
        }

    @Test
    fun focusMovesToTheNeighbourWhenItsRowGoes() =
        channelsTest(listOf(summary(A, 5), summary(B, 7), summary(C, 9))) { rig ->
            rig.open(this, current = B)
            rig.repo.list.value = listOf(summary(A, 5), summary(C, 9))
            runCurrent()
            assertEquals(ChannelsFocus.Row(C), rig.ui.focus)
            rig.repo.list.value = listOf(summary(A, 5))
            runCurrent()
            assertEquals(ChannelsFocus.Row(A), rig.ui.focus)
        }

    @Test
    fun withNoWantedChannelTheFocusIsOnNewAtOnceBeforeAnyListOrAccount() =
        channelsTest { rig ->
            // Nothing has run: no account, no list, no access. New is always drawn, so the focus is already on it.
            rig.vm.open(null)
            assertEquals(ChannelsFocus.New, rig.ui.focus)
        }

    @Test
    fun withAWantedChannelAKeyBeforeTheListPutsTheFocusOnNew() =
        channelsTest { rig ->
            rig.vm.open(A)
            assertEquals(null, rig.ui.focus)
            rig.vm.move(false)
            assertEquals(ChannelsFocus.New, rig.ui.focus)
        }

    @Test
    fun aWantedChannelThatIsNotInTheFirstListLeavesTheFocusOnNew() =
        channelsTest { rig ->
            rig.open(this, current = A)
            assertEquals(ChannelsFocus.New, rig.ui.focus)
        }

    @Test
    fun aWantedChannelThatArrivesLaterTakesTheFocusFromNew() =
        channelsTest { rig ->
            rig.open(this, current = A)
            assertEquals(ChannelsFocus.New, rig.ui.focus)
            rig.repo.list.value = listOf(summary(A, 5), summary(B, 7))
            runCurrent()
            assertEquals(ChannelsFocus.Row(A), rig.ui.focus)
        }

    @Test
    fun aWantedChannelThatArrivesAfterTheViewerMovedDoesNotTakeTheFocus() =
        channelsTest { rig ->
            rig.open(this, current = A)
            rig.vm.move(false)
            rig.vm.move(true)
            rig.repo.list.value = listOf(summary(A, 5), summary(B, 7))
            runCurrent()
            assertEquals(ChannelsFocus.New, rig.ui.focus)
        }

    @Test
    fun theListStartsOnNewChannelWhenOpenedWithoutAChannelAndMovesWithTheKeys() =
        channelsTest(trio) { rig ->
            rig.open(this, current = null)
            assertEquals(ChannelsFocus.New, rig.ui.focus)
            rig.vm.move(true)
            assertEquals("the switch is the stop above New", ChannelsFocus.Household, rig.ui.focus)
            rig.vm.move(true)
            assertEquals(ChannelsFocus.Household, rig.ui.focus)
            rig.vm.move(false)
            assertEquals(ChannelsFocus.New, rig.ui.focus)
            rig.vm.move(false)
            assertEquals(ChannelsFocus.Row(A), rig.ui.focus)
            rig.vm.move(false)
            rig.vm.move(false)
            rig.vm.move(false)
            assertEquals(ChannelsFocus.Reset, rig.ui.focus)
            rig.vm.move(false)
            assertEquals(ChannelsFocus.Reset, rig.ui.focus)
            rig.vm.select()
            assertTrue(rig.ui.confirmingReset)
            rig.vm.dismiss()
            rig.vm.move(true)
            rig.vm.select()
            assertEquals(ChannelsPanel.Actions(C), rig.ui.panel)
        }

    @Test
    fun reopeningStartsClean() =
        channelsTest(trio) { rig ->
            rig.open(this, current = A)
            rig.vm.select()
            rig.vm.askReset()
            rig.editor.changeResult = ChangeResult.Failed
            rig.vm.switchOff(B)
            runCurrent()
            rig.editor.resetResult = ResetResult.Chosen
            rig.vm.reset()
            runCurrent()
            rig.vm.open(B)
            runCurrent()
            assertNull(rig.ui.panel)
            assertNull(rig.ui.message)
            assertEquals(false, rig.ui.confirmingReset)
            assertEquals(false, rig.ui.choosing)
            assertEquals(ChannelsFocus.Row(B), rig.ui.focus)
            assertNull(rig.vm.closing())
        }

    @Test
    fun closingAfterTheChannelPlayingWentOffTunesTheNextOn() =
        channelsTest(trio) { rig ->
            rig.open(this, current = B)
            rig.repo.list.value = listOf(summary(A, 5), summary(B, 7, UserHidden), summary(C, 9))
            runCurrent()
            assertEquals(C, rig.vm.closing())
        }

    // #557: nothing is higher than the channel that went, so cable goes down, not round to the lowest.
    @Test
    fun closingAfterTheHighestPlayingChannelWasDeletedTunesTheNextLower() =
        channelsTest(trio) { rig ->
            rig.open(this, current = C)
            rig.repo.list.value = listOf(summary(A, 5), summary(B, 7))
            runCurrent()
            assertEquals(B, rig.vm.closing())
        }

    @Test
    fun closingAfterAHighestPlayingChannelWentOffTunesTheNextLowerNotTheLowest() =
        channelsTest(trio) { rig ->
            rig.open(this, current = C)
            rig.repo.list.value = listOf(summary(A, 5), summary(B, 7), summary(C, 9, Shelf))
            runCurrent()
            assertEquals(B, rig.vm.closing())
        }

    @Test
    fun closingAfterAMiddlePlayingChannelWasDeletedTunesTheNextHigher() =
        channelsTest(trio) { rig ->
            rig.open(this, current = B)
            rig.repo.list.value = listOf(summary(A, 5), summary(C, 9))
            runCurrent()
            assertEquals(C, rig.vm.closing())
        }

    @Test
    fun closingAfterTheLowestPlayingChannelWasDeletedTunesTheNextHigherNotTheTop() =
        channelsTest(trio) { rig ->
            rig.open(this, current = A)
            rig.repo.list.value = listOf(summary(B, 7), summary(C, 9))
            runCurrent()
            assertEquals(B, rig.vm.closing())
        }

    @Test
    fun closingWhenNothingIsLeftOnTunesNothing() =
        channelsTest(trio) { rig ->
            rig.open(this, current = B)
            rig.repo.list.value = emptyList()
            runCurrent()
            assertNull(rig.vm.closing())
        }

    @Test
    fun closingAfterAResetTunesTheLowestOn() =
        channelsTest(trio) { rig ->
            rig.open(this, current = C)
            rig.editor.resetResult = ResetResult.Chosen
            rig.vm.reset()
            rig.repo.list.value = listOf(summary(D, 3), summary(C, 9))
            runCurrent()
            assertEquals(D, rig.vm.closing())
        }

    @Test
    fun closingWithNothingChangedTunesNothing() =
        channelsTest(trio) { rig ->
            rig.open(this, current = B)
            assertNull(rig.vm.closing())
            rig.repo.list.value = listOf(summary(A, 5), summary(B, 20), summary(C, 9))
            runCurrent()
            assertNull(rig.vm.closing())
        }

    @Test
    fun reasonsAreReadOncePerQuietSecond() =
        channelsTest(trio) { rig ->
            rig.schedule.channel(A, 5, emptyList())
            rig.open(this)
            assertEquals(1, rig.schedule.airingsCalls)
            rig.repo.list.value = listOf(summary(A, 6), summary(B, 7), summary(C, 9))
            runCurrent()
            advanceTimeBy(500)
            rig.repo.list.value = listOf(summary(A, 6), summary(B, 8), summary(C, 9))
            runCurrent()
            advanceTimeBy(900)
            runCurrent()
            assertEquals(1, rig.schedule.airingsCalls)
            advanceTimeBy(200)
            runCurrent()
            assertEquals(2, rig.schedule.airingsCalls)
        }

    @Test
    fun aPanelClosesWhenItsChannelChangesUnderIt() =
        channelsTest(trio) { rig ->
            rig.open(this, current = A)
            rig.vm.select()
            assertEquals(ChannelsPanel.Actions(A), rig.ui.panel)
            rig.repo.list.value = listOf(summary(A, 5), summary(B, 8), summary(C, 9))
            runCurrent()
            assertEquals(ChannelsPanel.Actions(A), rig.ui.panel)
            rig.repo.list.value = listOf(summary(A, 5, UserHidden), summary(B, 8), summary(C, 9))
            runCurrent()
            assertNull(rig.ui.panel)
        }
}
