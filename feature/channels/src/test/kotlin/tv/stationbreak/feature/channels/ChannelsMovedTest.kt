// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.MovedChannel

/** Channels moved to their band's floor (#1097) are told once when Channels opens, then cleared. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsMovedTest {
    private val one = MovedChannel("Test One", 50, 600)
    private val two = MovedChannel("Test Two", 98, 310)

    @Test
    fun twoMovedChannelsAreTwoLines() =
        channelsTest(listOf(summary(A, 5))) { rig ->
            rig.editor.moved = listOf(one, two)
            rig.open(this)

            assertEquals(listOf(one, two), rig.ui.moved)
        }

    @Test
    fun theyAreShownOnceAndTheNextVisitHasNone() =
        channelsTest(listOf(summary(A, 5))) { rig ->
            rig.editor.moved = listOf(one)
            rig.open(this)
            assertEquals(listOf(one), rig.ui.moved)
            assertEquals(1, rig.editor.takes)

            rig.vm.close()
            rig.open(this)

            assertEquals(emptyList<MovedChannel>(), rig.ui.moved)
            assertEquals(2, rig.editor.takes)
        }

    @Test
    fun nothingPendingShowsNothing() =
        channelsTest(listOf(summary(A, 5))) { rig ->
            rig.open(this)

            assertEquals(emptyList<MovedChannel>(), rig.ui.moved)
        }
}
