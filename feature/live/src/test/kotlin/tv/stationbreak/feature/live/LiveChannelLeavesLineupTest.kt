// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.ChannelId

/**
 * #562: when the playing channel leaves the lineup, live moves to `neighbourOf`'s answer (the next higher number, else
 * the next lower, never a wrap), once the show has ended; the card says why unless the viewer did it.
 */
class LiveChannelLeavesLineupTest {
    // The rig starts 10 minutes in, so the first show (minutes 0 to 60) ends at test minute 50.
    private val boundary = 50 * MIN

    private fun Rig.lineup(vararg numbers: Pair<ChannelId, Int>) {
        // Each channel plays item N (N its number), then item N + 100 from minute 60, where the neighbour is joined.
        numbers.forEach { (id, n) -> schedule.channel(id, n, listOf(program(0, 60, n), program(60, 120, n + 100))) }
    }

    // A tune is a fresh one, or the look-ahead's handle taken at the boundary.
    private fun Rig.allTuned() =
        tune.calls.mapNotNull {
            when (it) {
                is Call.Tune -> it.item
                is Call.TuneHandle -> it.item
                else -> null
            }
        }

    private fun Rig.lastTuned() = allTuned().last()

    private fun Rig.notes() = seen.filterIsInstance<LiveState.Ident>().mapNotNull { it.note }

    private fun Rig.banner() = (state as? LiveState.Watching)?.banner ?: (state as LiveState.Tuning).banner

    private suspend fun Rig.playing(id: ChannelId) {
        lastChannel.save(account, id)
        start()
        at(MIN)
    }

    // A refresh shelves the channel mid-show: the schedule and the lineup list both stop having it.
    private fun Rig.shelve(id: ChannelId) {
        at(20 * MIN)
        schedule.channels.remove(id)
        channels.publish(*schedule.channels.map { it.key to it.value.number }.toTypedArray())
    }

    // The channel has nothing for today: it stays on the lineup list, but the schedule hides it.
    private fun Rig.emptyToday(id: ChannelId) {
        at(20 * MIN)
        schedule.channels.getValue(id).hidden = HiddenReason.EmptyToday
    }

    private fun Rig.afterTheBoundary() = at(boundary + 5_000)

    @Test
    fun aShelvedShowKeepsPlayingToItsEndThenMovesToTheNextHigherWithANote() =
        runTest {
            val r = Rig(this)
            r.lineup(CH5 to 5, CH7 to 7, CH9 to 9)
            r.playing(CH7)
            r.shelve(CH7)

            // Never cut off: still the picture, no stop, no new tune, right up to the end of the show.
            r.at(boundary - MIN)
            assertTrue(r.state is LiveState.Watching)
            assertEquals(listOf(itemOf(7)), r.allTuned())
            assertEquals(0, r.tune.calls.count { it is Call.Stop })

            r.at(boundary + 1_000)
            assertEquals(LiveState.Ident(null, null, CardNote.OffTheAir("Test Channel 7", 9)), r.state)

            r.afterTheBoundary()
            assertEquals(itemOf(109), r.lastTuned())
            assertEquals(9, r.banner().number)
            assertEquals(CH9, r.lastChannel.load(r.account))
        }

    @Test
    fun aChannelEmptyForTodayMovesToTheNeighbourAtTheBoundaryToo() =
        runTest {
            val r = Rig(this)
            r.lineup(CH5 to 5, CH7 to 7, CH9 to 9)
            r.playing(CH7)
            r.emptyToday(CH7)

            r.at(boundary - MIN)
            assertEquals(listOf(itemOf(7)), r.allTuned())
            r.at(boundary + 1_000)
            assertEquals(CardNote.OffTheAir("Test Channel 7", 9), (r.state as LiveState.Ident).note)
            r.afterTheBoundary()
            assertEquals(itemOf(109), r.lastTuned())
        }

    @Test
    fun theTopChannelGoingFallsBackToTheNextLowerNotTheLowest() =
        runTest {
            val r = Rig(this)
            r.lineup(CH5 to 5, CH7 to 7, CH9 to 9)
            r.playing(CH9)
            r.shelve(CH9)

            r.at(boundary + 1_000)
            assertEquals(CardNote.OffTheAir("Test Channel 9", 7), (r.state as LiveState.Ident).note)
            r.afterTheBoundary()
            assertEquals(itemOf(107), r.lastTuned())
        }

    @Test
    fun theLowestChannelGoingMovesToTheNextHigherNotTheTop() =
        runTest {
            val r = Rig(this)
            r.lineup(CH5 to 5, CH7 to 7, CH9 to 9)
            r.playing(CH5)
            r.shelve(CH5)

            r.at(boundary + 1_000)
            assertEquals(CardNote.OffTheAir("Test Channel 5", 7), (r.state as LiveState.Ident).note)
            r.afterTheBoundary()
            assertEquals(itemOf(107), r.lastTuned())
        }

    @Test
    fun aGapInTheNumbersMovesToTheNextHigherNumber() =
        runTest {
            val r = Rig(this)
            val ch12 = ChannelId("ch-12")
            r.lineup(CH5 to 5, CH7 to 7, ch12 to 12)
            r.playing(CH7)
            r.shelve(CH7)

            r.at(boundary + 1_000)
            assertEquals(CardNote.OffTheAir("Test Channel 7", 12), (r.state as LiveState.Ident).note)
        }

    @Test
    fun theLastChannelGoingKeepsTheEmptyLineupBehaviour() =
        runTest {
            val r = Rig(this)
            r.lineup(CH7 to 7)
            r.playing(CH7)
            r.shelve(CH7)

            r.afterTheBoundary()
            assertEquals(LiveState.NoChannels, r.state)
            assertTrue(r.notes().isEmpty())
            assertEquals(1, r.tunes.size)
        }

    @Test
    fun aChannelStillInTheLineupWhoseShowEndedIsNotTreatedAsGone() =
        runTest {
            val r = Rig(this)
            r.lineup(CH5 to 5, CH7 to 7, CH9 to 9)
            r.playing(CH7)

            r.afterTheBoundary()
            assertTrue(r.notes().isEmpty())
            assertEquals(CH7, r.lastChannel.load(r.account))
        }

    @Test
    fun aChannelTheViewerSwitchedOffIsLeftForItsNeighbourWithNoNote() =
        runTest {
            val r = Rig(this)
            // Channel 7 is off (hidden here) when live is shown again; its number is still the lineup's.
            r.lineup(CH5 to 5, CH7 to 7, CH9 to 9)
            r.schedule.channels
                .getValue(CH7)
                .hidden = HiddenReason.EmptyToday
            r.lastChannel.save(r.account, CH7)
            r.start()

            assertEquals(itemOf(9), r.tunes.first().item)
            assertTrue(r.notes().isEmpty())
            assertEquals(CH9, r.lastChannel.load(r.account))
        }

    @Test
    fun aSwitchedOffTopChannelLandsOnTheNextLowerOnReturn() =
        runTest {
            val r = Rig(this)
            r.lineup(CH5 to 5, CH7 to 7, CH9 to 9)
            r.schedule.channels
                .getValue(CH9)
                .hidden = HiddenReason.EmptyToday
            r.lastChannel.save(r.account, CH9)
            r.start()

            assertEquals(itemOf(7), r.tunes.first().item)
            assertTrue(r.notes().isEmpty())
        }
}
