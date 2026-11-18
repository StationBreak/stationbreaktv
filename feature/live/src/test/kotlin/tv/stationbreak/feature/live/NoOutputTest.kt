// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.feature.live.report.RecordingServer
import kotlin.time.Duration.Companion.minutes

/**
 * The TV's output is gone (#423): the stick has no HDMI sink, so every tune would time out, retry and report a play
 * nobody sees. Live holds the StationBreak card, tunes nothing, reports nothing, and tunes once when the output
 * returns.
 */
class NoOutputTest {
    private val facts = ReportFacts(MediaSourceId("src"), PlaySessionId("ps"), PlayMethod.DirectPlay, 50.minutes)

    private fun TestScope.server() = RecordingServer({ currentTime })

    private fun Rig.oneChannel() {
        schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
    }

    @Test
    fun noOutputAtStart_holdsTheCard_playsNothing_thenTunesOnceWhenItReturns() =
        runTest {
            val r = Rig(this)
            r.oneChannel()
            r.output.value = false
            r.start()
            r.at(5 * MIN)

            assertEquals(LiveState.Ident(null), r.state)
            assertTrue("nothing is tuned: ${r.tunes}", r.tunes.isEmpty())

            r.output.value = true
            r.at(6 * MIN)
            assertEquals(itemOf(1), r.tunes.single().item)
        }

    @Test
    fun theOutputGoneWhileWatching_stopsThePicture_andDoesNotTuneAgain() =
        runTest {
            val r = Rig(this)
            r.oneChannel()
            r.start()
            r.at(1 * MIN)
            assertEquals(1, r.tunes.size)

            r.output.value = false
            r.at(2 * MIN)
            // Minutes of a TV that is off: no tune, no retry, no stand-in.
            r.at(20 * MIN)

            assertEquals(LiveState.Ident(null), r.state)
            assertTrue("the picture was stopped", r.tune.calls.last() is Call.Stop)
            assertEquals("nothing else was tuned", 1, r.tunes.size)
        }

    @Test
    fun theOutputBack_tunesOnce_onTheSameChannelAtTheClock() =
        runTest {
            val r = Rig(this)
            r.oneChannel()
            r.start()
            r.at(1 * MIN)
            r.output.value = false
            r.at(10 * MIN)

            r.output.value = true
            r.at(11 * MIN)

            assertEquals(2, r.tunes.size)
            assertEquals(itemOf(1), r.tunes.last().item)
        }

    @Test
    fun noPlayIsReported_whileTheOutputIsGone_andItsViewingTimeIsNotCounted() =
        runTest {
            val server = server()
            val r = Rig(this, startMin = 10, server = server)
            r.oneChannel()
            r.tune.reportFacts = facts
            r.start()
            r.at(31_000)
            assertEquals(1, server.calls.count { it.kind == "start" })
            val before = server.calls.size

            r.output.value = false
            r.at(5 * MIN)
            r.at(20 * MIN)

            // The play was stopped when the output went, and nothing else was sent for the quiet minutes.
            val after = server.calls.drop(before)
            assertEquals(listOf("stopped"), after.map { it.kind })
            assertEquals(1, server.calls.count { it.kind == "start" })
        }
}
