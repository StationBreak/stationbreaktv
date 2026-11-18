// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program

/** The first visit (#94): the first sitting with channels tunes the default and asks for the guide, once. */
class FirstVisitTest {
    private fun Rig.oneChannel() {
        schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
    }

    @Test
    fun noLastChannelWithChannels_tunesTheDefaultAndAsksForTheGuideOnce() =
        runTest {
            val r = Rig(this)
            r.oneChannel()
            r.start()
            r.at(5 * MIN)
            assertEquals(itemOf(1), r.tunes.single().item)
            assertEquals(1, r.firstVisits)
            // A later sitting of the same run saved the channel: it is not a first visit again.
            r.at(70 * MIN)
            assertEquals(1, r.firstVisits)
        }

    @Test
    fun aSavedLastChannel_opensAsToday() =
        runTest {
            val r = Rig(this)
            r.oneChannel()
            r.lastChannel.save(r.account, CH7)
            r.start()
            r.at(5 * MIN)
            assertEquals(1, r.tunes.size)
            assertEquals(0, r.firstVisits)
        }

    @Test
    fun afterProcessDeath_theSavedChannelKeepsTheNoteFromReturning() =
        runTest {
            val r = Rig(this)
            r.oneChannel()
            r.start()
            r.at(5 * MIN)
            assertEquals(1, r.firstVisits)
            // A fresh machine over the same store, as after a restart.
            val again = Rig(this)
            again.oneChannel()
            again.lastChannel.save(again.account, r.lastChannel.load(r.account)!!)
            again.start()
            again.at(5 * MIN)
            assertEquals(0, again.firstVisits)
        }

    @Test
    fun noChannels_doesNotUseUpTheFirstVisit() =
        runTest {
            val r = Rig(this)
            r.start()
            r.at(5 * MIN)
            assertEquals(LiveState.NoChannels, r.state)
            assertEquals(0, r.firstVisits)

            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1)))
            r.channels.publish(CH7 to 7)
            r.at(10 * MIN)
            assertEquals(itemOf(1), r.tunes.single().item)
            assertEquals(1, r.firstVisits)
        }
}
