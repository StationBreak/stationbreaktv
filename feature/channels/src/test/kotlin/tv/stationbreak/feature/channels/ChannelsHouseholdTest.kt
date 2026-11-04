// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelChange
import tv.stationbreak.core.domain.lineup.LineupAccess

/** The "Use my collections" switch at the top of the Channels list (spec 23). */
class ChannelsHouseholdTest {
    private val trio = listOf(summary(A, 5), summary(B, 7), summary(C, 9))

    @Test
    fun theToggleShowsTheStoredValueAndSendsTheChange() =
        channelsTest(trio) { rig ->
            rig.open(this)
            assertTrue("on by default", rig.ui.useMyCollections)
            rig.repo.household.value = false
            runCurrent()
            assertEquals(false, rig.ui.useMyCollections)

            // It is the first stop: up from New reaches it, and Select flips what is shown.
            assertEquals(ChannelsFocus.New, rig.ui.focus)
            rig.vm.move(true)
            assertEquals(ChannelsFocus.Household, rig.ui.focus)
            rig.vm.move(true)
            assertEquals(ChannelsFocus.Household, rig.ui.focus)
            rig.vm.select()
            runCurrent()
            assertEquals(
                listOf<ChannelChange>(ChannelChange.UseMyCollections(true)),
                rig.editor.changes.map { it.second },
            )
            rig.repo.household.value = true
            runCurrent()
            rig.vm.select()
            runCurrent()
            assertEquals(
                ChannelChange.UseMyCollections(false),
                rig.editor.changes
                    .last()
                    .second,
            )
            assertNull("no channel panel opens for it", rig.ui.panel)
            assertEquals(ChannelsFocus.Household, rig.ui.focus)
        }

    @Test
    fun theToggleOnAReadOnlyLineupSaysSo() =
        channelsTest(trio) { rig ->
            rig.editor.setAccess(LineupAccess.ReadOnly)
            rig.open(this)
            rig.vm.move(true)
            rig.vm.select()
            runCurrent()
            assertTrue(rig.editor.changes.isEmpty())
            assertEquals(ChannelsMessage.NotEditable, rig.ui.message)
        }
}
