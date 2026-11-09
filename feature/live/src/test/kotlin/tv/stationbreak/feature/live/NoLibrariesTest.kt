// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.model.ItemId

/** Every library this TV chose is gone (spec 26): live says so on its slate and plays nothing. */
class NoLibrariesTest {
    private val gone = ChoiceView(setOf(ItemId("lib-a")), emptySet(), gone = true)
    private val kept = gone.copy(gone = false)

    private fun Rig.oneChannel() {
        schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
    }

    @Test
    fun goneSelection_liveShowsNoLibraries_playsNothing() =
        runTest {
            val r = Rig(this)
            r.oneChannel()
            r.choices.selection.value = gone
            r.start()
            r.at(5 * MIN)

            assertEquals(LiveState.NoLibraries, r.state)
            assertTrue("nothing is tuned: ${r.tunes}", r.tunes.isEmpty())

            // A library chosen again: the channel is tuned as at any start.
            r.choices.selection.value = kept
            r.at(6 * MIN)
            assertEquals(itemOf(1), r.tunes.single().item)
        }

    @Test
    fun selectionGoneWhileWatching_stopsThePictureAndShowsNoLibraries() =
        runTest {
            val r = Rig(this)
            r.oneChannel()
            r.choices.selection.value = kept
            r.start()
            r.at(1 * MIN)
            assertEquals(1, r.tunes.size)

            r.choices.selection.value = gone
            r.at(2 * MIN)

            assertEquals(LiveState.NoLibraries, r.state)
            assertTrue("the picture was stopped", r.tune.calls.last() is Call.Stop)
            assertEquals("nothing else was tuned", 1, r.tunes.size)
        }
}
