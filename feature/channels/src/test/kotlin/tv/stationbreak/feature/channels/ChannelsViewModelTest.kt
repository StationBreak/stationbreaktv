// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChangeRefusal
import tv.stationbreak.core.domain.lineup.ChangeResult
import tv.stationbreak.core.domain.lineup.ChannelChange
import tv.stationbreak.core.domain.lineup.ChannelStatus.On
import tv.stationbreak.core.domain.lineup.ChannelStatus.Shelf
import tv.stationbreak.core.domain.lineup.ChannelStatus.UserHidden
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.domain.lineup.ResetResult

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

    private suspend fun kotlinx.coroutines.test.TestScope.refusal(
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
            val slow =
                object : tv.stationbreak.core.domain.lineup.ChannelEditor by rig.editor {
                    override suspend fun reset(account: tv.stationbreak.core.domain.LibraryAccount): ResetResult {
                        gate.await()
                        return rig.editor.reset(account)
                    }
                }
            val vm =
                ChannelsViewModel(
                    rig.library,
                    rig.repo,
                    slow,
                    rig.schedule,
                    rig.clock,
                    kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                )
            vm.open(null)
            runCurrent()
            vm.askReset()
            assertTrue(vm.ui.value.confirmingReset)
            vm.reset()
            runCurrent()
            assertTrue(vm.ui.value.choosing)
            assertEquals(false, vm.ui.value.confirmingReset)
            gate.complete(Unit)
            runCurrent()
            assertEquals(false, vm.ui.value.choosing)
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
    fun focusStartsOnTheFirstRowWhenCurrentIsUnknownAndMovesWithTheKeys() =
        channelsTest(trio) { rig ->
            rig.open(this, current = null)
            assertEquals(ChannelsFocus.Row(A), rig.ui.focus)
            rig.vm.move(true)
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

    @Test
    fun closingWrapsToTheLowestOn() =
        channelsTest(trio) { rig ->
            rig.open(this, current = C)
            rig.repo.list.value = listOf(summary(A, 5), summary(B, 7), summary(C, 9, Shelf))
            runCurrent()
            assertEquals(A, rig.vm.closing())
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
