// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.builder.BuilderEvent
import tv.stationbreak.feature.channels.ui.ChannelsActions

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
}
