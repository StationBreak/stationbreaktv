// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.builder.BuilderMode

/** The New channel row and the builder's entry points on the channel screen (spec 22). */
class ChannelsBuilderEntryTest {
    private val trio = listOf(summary(A, 5), summary(B, 7), summary(C, 9))

    @Test
    fun openedForACurrentChannelItFocusesThatRow() =
        channelsTest(trio) { rig ->
            rig.open(this, current = B)
            assertEquals(ChannelsFocus.Row(B), rig.ui.focus)
        }

    @Test
    fun upFromTheFirstRowGoesToNew() =
        channelsTest(trio) { rig ->
            rig.open(this, current = A)
            rig.vm.move(true)
            assertEquals(ChannelsFocus.New, rig.ui.focus)
        }

    @Test
    fun anEmptyLineupStillHasNewChannel() =
        channelsTest { rig ->
            rig.open(this)
            assertEquals(ChannelsFocus.New, rig.ui.focus)
        }

    @Test
    fun selectOnNewOpensTheBuilder() =
        channelsTest(trio) { rig ->
            rig.open(this)
            rig.vm.select()
            assertEquals(BuilderMode.New, rig.ui.builder)
            assertEquals(ChannelsFocus.New, rig.ui.focus)
        }

    @Test
    fun selectOnNewWithoutAccessSaysSoAndOpensNothing() =
        channelsTest(trio) { rig ->
            rig.editor.setAccess(LineupAccess.ReadOnly)
            rig.open(this)
            rig.vm.select()
            assertNull(rig.ui.builder)
            assertEquals(ChannelsMessage.NotEditable, rig.ui.message)
        }

    @Test
    fun copyToCustomiseOpensTheBuilderForThatChannel() =
        channelsTest(trio) { rig ->
            rig.open(this, current = B)
            rig.vm.select()
            rig.vm.panelMove(PanelMove.Down)
            rig.vm.panelMove(PanelMove.Down)
            assertEquals(Choice.CopyToCustomise, choicesOf(rig.ui)[rig.ui.choice])
            rig.vm.panelSelect()
            assertEquals(BuilderMode.Copy(B), rig.ui.builder)
            assertNull(rig.ui.panel)
        }

    @Test
    fun editOpensTheBuilderOnACustomChannel() =
        channelsTest(listOf(summary(A, 5), summary(B, 7, kind = ChannelKind.Custom))) { rig ->
            rig.open(this, current = B)
            rig.vm.select()
            rig.vm.panelMove(PanelMove.Down)
            rig.vm.panelMove(PanelMove.Down)
            assertEquals(Choice.Edit, choicesOf(rig.ui)[rig.ui.choice])
            rig.vm.panelSelect()
            assertEquals(BuilderMode.Edit(B), rig.ui.builder)
        }

    @Test
    fun theKeysBelongToTheBuilderWhileItShows() =
        channelsTest(trio) { rig ->
            rig.open(this, current = B)
            rig.vm.select()
            rig.vm.panelMove(PanelMove.Down)
            rig.vm.panelMove(PanelMove.Down)
            rig.vm.panelSelect()
            rig.vm.move(false)
            rig.vm.select()
            rig.vm.dismiss()
            assertEquals(ChannelsFocus.Row(B), rig.ui.focus)
            assertEquals(BuilderMode.Copy(B), rig.ui.builder)
            assertNull(rig.ui.panel)
        }

    @Test
    fun backFromTheBuilderReturnsToTheRowItLeftFrom() =
        channelsTest(trio) { rig ->
            rig.open(this, current = B)
            rig.vm.select()
            rig.vm.panelMove(PanelMove.Down)
            rig.vm.panelMove(PanelMove.Down)
            rig.vm.panelSelect()
            rig.vm.builderClosed()
            assertNull(rig.ui.builder)
            assertNull(rig.ui.panel)
            assertEquals(ChannelsFocus.Row(B), rig.ui.focus)
        }

    @Test
    fun backFromTheBuilderOpenedOnNewReturnsToNew() =
        channelsTest(trio) { rig ->
            rig.open(this)
            rig.vm.select()
            rig.vm.builderClosed()
            assertNull(rig.ui.builder)
            assertEquals(ChannelsFocus.New, rig.ui.focus)
        }

    @Test
    fun saveAndWatchClosesTheScreenWithTheNewChannelToTune() =
        channelsTest(trio) { rig ->
            rig.open(this)
            rig.vm.select()
            val made = ChannelId("custom:new")
            assertEquals(made, rig.vm.builderSaved(made, andWatch = true))
            assertNull(rig.ui.builder)
        }

    @Test
    fun saveWithoutWatchingReturnsToTheListOnThatChannelOnceItArrives() =
        channelsTest(trio) { rig ->
            rig.open(this)
            rig.vm.select()
            val made = ChannelId("custom:new")
            assertNull(rig.vm.builderSaved(made, andWatch = false))
            assertNull(rig.ui.builder)
            rig.repo.list.value = trio + summary(made, 11, kind = ChannelKind.Custom)
            runCurrent()
            assertEquals(ChannelsFocus.Row(made), rig.ui.focus)
        }

    @Test
    fun saveWithoutWatchingFocusesARowThatIsAlreadyThere() =
        channelsTest(trio) { rig ->
            rig.open(this, current = C)
            rig.vm.select()
            rig.vm.panelMove(PanelMove.Down)
            rig.vm.panelMove(PanelMove.Down)
            rig.vm.panelSelect()
            rig.vm.builderSaved(B, andWatch = false)
            assertEquals(ChannelsFocus.Row(B), rig.ui.focus)
        }

    @Test
    fun newChannelWhileTheAccessLoadsOpensTheBuilderOnceItIsEditable() =
        channelsTest(trio) { rig ->
            rig.editor.setAccess(LineupAccess.Loading)
            rig.open(this)
            rig.vm.select()
            assertNull(rig.ui.builder)
            assertNull(rig.ui.message)
            rig.editor.setAccess(LineupAccess.Editable)
            runCurrent()
            assertEquals(BuilderMode.New, rig.ui.builder)
        }

    @Test
    fun aPressWaitingOnTheAccessShowsTheGettingReadyNoteUntilItResolves() =
        channelsTest(trio) { rig ->
            rig.editor.setAccess(LineupAccess.Loading)
            rig.open(this)
            assertFalse(rig.ui.gettingReady)
            rig.vm.select()
            assertTrue(rig.ui.gettingReady)
            rig.editor.setAccess(LineupAccess.Editable)
            runCurrent()
            assertFalse(rig.ui.gettingReady)
            assertEquals(BuilderMode.New, rig.ui.builder)
        }

    @Test
    fun aWaitStillLoadingAfterTenSecondsIsDroppedWithTheNotReadyMessage() =
        channelsTest(trio) { rig ->
            rig.editor.setAccess(LineupAccess.Loading)
            rig.open(this)
            rig.vm.select()
            advanceTimeBy(9_999)
            runCurrent()
            assertTrue(rig.ui.gettingReady)
            assertNull(rig.ui.message)
            advanceTimeBy(2)
            runCurrent()
            assertFalse(rig.ui.gettingReady)
            assertEquals(ChannelsMessage.NotReady, rig.ui.message)
            assertNull(rig.ui.builder)
            // The access arriving later does not open a builder nobody is waiting for.
            rig.editor.setAccess(LineupAccess.Editable)
            runCurrent()
            assertNull(rig.ui.builder)
            // A new press works.
            rig.vm.select()
            assertEquals(BuilderMode.New, rig.ui.builder)
        }

    @Test
    fun aWaitThatResolvedInTimeIsNotDroppedByAnOlderTimeout() =
        channelsTest(trio) { rig ->
            rig.editor.setAccess(LineupAccess.Loading)
            rig.open(this)
            rig.vm.select()
            advanceTimeBy(5_000)
            rig.editor.setAccess(LineupAccess.Editable)
            runCurrent()
            assertEquals(BuilderMode.New, rig.ui.builder)
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(BuilderMode.New, rig.ui.builder)
            assertNull(rig.ui.message)
        }

    @Test
    fun newChannelWhileTheAccessLoadsSaysSoOnlyWhenItResolvesToReadOnly() =
        channelsTest(trio) { rig ->
            rig.editor.setAccess(LineupAccess.Loading)
            rig.open(this)
            rig.vm.select()
            rig.editor.setAccess(LineupAccess.ReadOnly)
            runCurrent()
            assertNull(rig.ui.builder)
            assertEquals(ChannelsMessage.NotEditable, rig.ui.message)
        }

    @Test
    fun aMoveOrBackCancelsTheBuilderWaitingOnTheAccess() =
        channelsTest(trio) { rig ->
            for (cancel in listOf({ rig.vm.move(false) }, { rig.vm.dismiss() })) {
                rig.editor.setAccess(LineupAccess.Loading)
                rig.open(this)
                rig.vm.select()
                cancel()
                rig.editor.setAccess(LineupAccess.Editable)
                runCurrent()
                assertNull(rig.ui.builder)
            }
        }

    @Test
    fun aSavedRowThatNeverArrivesDoesNotStealTheFocusLater() =
        channelsTest(trio) { rig ->
            rig.open(this)
            rig.vm.select()
            val made = ChannelId("custom:new")
            rig.vm.builderSaved(made, andWatch = false)
            rig.vm.move(false)
            assertEquals(ChannelsFocus.Row(A), rig.ui.focus)
            rig.repo.list.value = trio + summary(made, 11, kind = ChannelKind.Custom)
            runCurrent()
            assertEquals(ChannelsFocus.Row(A), rig.ui.focus)
        }
}
