// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelChange
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.feature.channels.ui.ChannelsActions
import tv.stationbreak.feature.channels.ui.Pad

/** "About this channel" (#631): the sheet over the list, its actions and what it reads. */
class ChannelsAboutTest {
    private val trio = listOf(summary(A, 5), summary(B, 7), summary(C, 9, kind = ChannelKind.Custom))

    private fun row(
        group: ChannelGroup,
        kind: ChannelKind = ChannelKind.Starter,
    ) = ChannelRow(A, 5, "Test", group, kind, kid = false, optIn = false, reason = null, hasOverride = false)

    @Test
    fun everyChannelCanBeAskedAboutWhateverItsKindOrTheAccess() {
        for (kind in ChannelKind.entries) {
            for (access in LineupAccess.entries) {
                val choices = actionsOf(row(ChannelGroup.On, kind), access, reasonsRead = true)
                assertEquals("$kind $access", Choice.About, choices.last())
            }
        }
    }

    @Test
    fun theSheetOffersTheActionsPanelsChoicesWithoutAbout() =
        channelsTest(trio) { rig ->
            rig.open(this, current = A)
            rig.vm.select()
            val inPanel = choicesOf(rig.ui)
            assertEquals(Choice.About, inPanel.last())

            rig.vm.panelMove(PanelMove.Down)
            repeat(inPanel.lastIndex - 1) { rig.vm.panelMove(PanelMove.Down) }
            assertNull(rig.vm.panelSelect())

            assertEquals(A, rig.ui.about)
            assertNull(rig.ui.panel)
            assertEquals(inPanel.dropLast(1), choicesOf(rig.ui))
            assertEquals(0, rig.ui.choice)
        }

    private suspend fun kotlinx.coroutines.test.TestScope.sheetOn(
        rig: ChannelsRig,
        id: tv.stationbreak.core.model.ChannelId = A,
    ) {
        rig.open(this, current = id)
        rig.vm.select()
        repeat(choicesOf(rig.ui).lastIndex) { rig.vm.panelMove(PanelMove.Down) }
        rig.vm.panelSelect()
    }

    @Test
    fun theSheetShowsTheIdentityAtOnceAndTheRulesOnceTheyAreRead() =
        channelsTest(trio) { rig ->
            sheetOn(rig)
            assertEquals(A, rig.ui.about)
            assertNull("the rules are still being read", rig.ui.properties)

            runCurrent()

            assertEquals(
                A,
                rig.ui.properties
                    ?.summary
                    ?.id,
            )
            assertEquals(listOf(A), rig.properties.reads.map { it.first })
        }

    @Test
    fun backClosesTheSheetThenTheScreen() =
        channelsTest(trio) { rig ->
            var done = false
            val keys = ChannelsActions(rig.vm) { done = true }
            sheetOn(rig)
            runCurrent()

            keys.pad(Pad.Back)

            assertNull(rig.ui.about)
            assertNull(rig.ui.properties)
            assertEquals("focus is back on the row", ChannelsFocus.Row(A), rig.ui.focus)
            assertTrue(!done)
            keys.pad(Pad.Back)
            assertTrue(done)
        }

    @Test
    fun overTheGuideTheKeysDoNothingUntilTheSheetIsUpAndBackThenEndsTheScreenAtOnce() =
        channelsTest(trio) { rig ->
            var done = 0
            val keys = ChannelsActions(rig.vm, sheetOnly = true) { done++ }
            rig.open(this, current = A)

            // The list is not drawn, so a key must not act on its row.
            keys.pad(Pad.Select)
            keys.pad(Pad.Down)
            assertNull(rig.ui.panel)
            assertEquals(0, done)

            rig.vm.openAbout(B)
            keys.pad(Pad.Back)
            assertEquals("Back ends the screen, there is no list to go back to", 1, done)
        }

    @Test
    fun anActionOnTheSheetKeepsItOpenAndReadsTheChannelAgain() =
        channelsTest(trio) { rig ->
            sheetOn(rig)
            runCurrent()
            assertEquals(1, rig.properties.reads.size)

            // Watch, Switch off, Copy to customise, Change number: Switch off is the second.
            rig.vm.panelMove(PanelMove.Down)
            assertNull(rig.vm.panelSelect())
            runCurrent()
            rig.repo.list.value =
                rig.repo.list.value
                    .map { if (it.id == A) it.copy(state = ChannelStatus.UserHidden) else it }
            runCurrent()

            assertEquals(listOf<ChannelChange>(ChannelChange.SwitchOff(A)), rig.editor.changes.map { it.second })
            assertEquals("the sheet stays", A, rig.ui.about)
            assertEquals(
                ChannelGroup.Off,
                rig.ui.rows
                    .first { it.id == A }
                    .group,
            )
            assertEquals("and reads the channel again", 2, rig.properties.reads.size)
        }

    @Test
    fun aNumberQuestionOverTheSheetReturnsToTheSheet() =
        channelsTest(trio) { rig ->
            sheetOn(rig)
            runCurrent()
            val at = choicesOf(rig.ui).indexOf(Choice.ChangeNumber)
            repeat(at) { rig.vm.panelMove(PanelMove.Down) }
            rig.vm.panelSelect()
            assertTrue(rig.ui.panel is ChannelsPanel.Number)
            assertEquals("the sheet is still there beneath", A, rig.ui.about)

            rig.vm.dismiss()

            assertNull(rig.ui.panel)
            assertEquals(A, rig.ui.about)
        }

    @Test
    fun theSheetClosesWhenItsChannelLeavesTheLineup() =
        channelsTest(trio) { rig ->
            sheetOn(rig)
            runCurrent()

            rig.repo.list.value =
                rig.repo.list.value
                    .filter { it.id != A }
            runCurrent()

            assertNull(rig.ui.about)
            assertNull(rig.ui.properties)
        }

    @Test
    fun aStalePropertiesReadDoesNotShowOnAnotherChannel() =
        channelsTest(trio) { rig ->
            sheetOn(rig)
            rig.vm.dismiss()
            runCurrent()
            assertNull(rig.ui.properties)

            sheetOn(rig, id = B)
            runCurrent()

            assertEquals(
                B,
                rig.ui.properties
                    ?.summary
                    ?.id,
            )
            assertNotNull(rig.ui.about)
        }
}
