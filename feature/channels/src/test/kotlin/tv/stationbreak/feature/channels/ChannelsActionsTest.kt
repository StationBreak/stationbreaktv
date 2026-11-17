// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.builder.BuilderEvent
import tv.stationbreak.feature.channels.ui.ChannelsActions
import tv.stationbreak.feature.channels.ui.Pad

/** How the screen's key handler finishes after the builder ends (spec 22). */
class ChannelsActionsTest {
    private val trio = listOf(summary(A, 5), summary(B, 7), summary(C, 9))
    private val made = ChannelId("custom:new")

    @Test
    fun saveAndWatchFinishesTheScreenOnceWithTheNewChannel() =
        channelsTest(trio) { rig ->
            val done = mutableListOf<ChannelId?>()
            val actions = ChannelsActions(rig.vm) { done += it }
            rig.open(this)
            rig.vm.select()
            actions.builderEnded(BuilderEvent.Saved(made, andWatch = true))
            actions.builderEnded(BuilderEvent.Saved(made, andWatch = true))
            assertEquals(listOf<ChannelId?>(made), done)
        }

    @Test
    fun saveWithoutWatchingShowsTheListAgainAndDoesNotFinish() =
        channelsTest(trio) { rig ->
            val done = mutableListOf<ChannelId?>()
            val actions = ChannelsActions(rig.vm) { done += it }
            rig.open(this)
            rig.vm.select()
            actions.builderEnded(BuilderEvent.Saved(made, andWatch = false))
            assertTrue(done.isEmpty())
            assertEquals(null, rig.ui.builder)
        }

    @Test
    fun closedShowsTheListAgainAndDoesNotFinish() =
        channelsTest(trio) { rig ->
            val done = mutableListOf<ChannelId?>()
            val actions = ChannelsActions(rig.vm) { done += it }
            rig.open(this)
            rig.vm.select()
            actions.builderEnded(BuilderEvent.Closed)
            assertTrue(done.isEmpty())
            assertEquals(null, rig.ui.builder)
        }

    // The sheet's choice cannot move past its first or last action, so those keys scroll its info column instead.
    @Test
    fun upFromTheFirstActionAndDownFromTheLastScrollTheSheet() =
        channelsTest(trio) { rig ->
            val scrolls = mutableListOf<Boolean>()
            val actions = ChannelsActions(rig.vm, scrollSheet = { scrolls += it }) { }
            rig.open(this, current = A)
            rig.vm.select()
            repeat(choicesOf(rig.ui).lastIndex) { rig.vm.panelMove(PanelMove.Down) }
            rig.vm.panelSelect()
            val last = choicesOf(rig.ui).lastIndex
            assertEquals(A, rig.ui.about)

            actions.pad(Pad.Up)
            assertEquals(listOf(false), scrolls)
            actions.pad(Pad.Down)
            assertEquals("the choice moves while there is a next one", listOf(false), scrolls)
            assertEquals(1, rig.ui.choice)
            repeat(last - 1) { actions.pad(Pad.Down) }
            assertEquals(last, rig.ui.choice)
            actions.pad(Pad.Down)
            assertEquals(listOf(false, true), scrolls)
            assertEquals(last, rig.ui.choice)
        }
}
