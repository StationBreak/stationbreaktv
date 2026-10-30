// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.feature.live.report.RecordingServer
import kotlin.time.Duration.Companion.minutes

/**
 * Every seek a behind play sends is reported done exactly where the player says, and only its own `SeekDone` ends the
 * way back to live (spec 20, ADR-0021 8 and 9). Watched time is checked through whether the 10-minute item is marked
 * played (90%, 540 s): a ledger left in `seeking` counts nothing more, so it is not.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The test clock's currentTime, for the server's call times.
class BehindSeekLandingTest {
    private class Keys(
        val r: Rig,
        val keys: Channel<LiveCommand>,
        val server: RecordingServer,
    ) {
        fun press(command: LiveCommand) {
            keys.trySend(command)
            r.scope.testScheduler.runCurrent()
        }

        val control get() = checkNotNull(r.tune.controlOf(itemOf(1)))

        val behind get() = checkNotNull(r.live.currentSitting()).behind

        fun markedPlayed() = server.calls.any { it.kind == "played" && it.item == itemOf(1).value }

        fun lines() = r.reports.map { it.line() }
    }

    /** Item 1 from minute 0 to 10 (runtime 10 minutes), joined at its start and reported; item 2 after it. */
    private suspend fun TestScope.keys(): Keys {
        val server = RecordingServer({ currentTime })
        val r = Rig(this, startMin = 0, server = server)
        r.tune.reportFacts = ReportFacts(MediaSourceId("src"), PlaySessionId("ps"), PlayMethod.DirectPlay, 10.minutes)
        r.schedule.channel(CH7, 7, listOf(program(0, 10, 1), program(10, 60, 2)))
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        r.start(commands = keys.receiveAsFlow())
        return Keys(r, keys, server)
    }

    private val back = LiveCommand.Seek(SeekDirection.Back)

    @Test
    fun theWayBackToLiveLandsOnTheLiveSeekNotAnEarlierOne() =
        runTest {
            val k = keys()
            k.r.at(60_000)
            k.control.seekDelayMs = 2_000
            k.press(back)
            k.press(LiveCommand.SeekReleased)
            // The back seek goes out at 60.4 s and is done at 62.4 s.
            k.r.at(61_000)
            // The live seek's position read takes until 62.5 s: the back seek's SeekDone comes meanwhile.
            k.control.nextPositionDelayMs = 1_500
            k.press(LiveCommand.GoLive)
            k.r.at(64_000)
            assertNotNull("still waiting for the live seek (done at 64.5 s)", k.behind)
            k.r.at(64_600)
            assertNull(k.behind)
            k.r.at(700_000)
            // The live landing was reported: the rest of the item counted.
            assertTrue(k.markedPlayed())
        }

    @Test
    fun aPauseClosesASeekThatNeverLanded() =
        runTest {
            val k = keys()
            k.r.at(60_000)
            k.control.dropsSeekDone = true
            k.press(back)
            k.press(LiveCommand.SeekReleased)
            k.r.at(62_000)
            k.press(LiveCommand.PlayPause)
            k.r.at(63_000)
            k.press(LiveCommand.PlayPause)
            k.r.at(700_000)
            assertEquals(2, k.r.tuneCount())
            // The pause reported the seek done where the player was, so the rest of the item counted.
            assertTrue(k.markedPlayed())
        }

    @Test
    fun aLiveSeekThatNeverLandsGoesLiveAfterTheWait() =
        runTest {
            val k = keys()
            k.r.at(60_000)
            k.press(LiveCommand.PlayPause)
            k.r.at(61_000)
            k.control.dropsSeekDone = true
            k.press(LiveCommand.GoLive)
            k.r.at(61_000 + 19_900)
            assertNotNull(k.behind)
            assertFalse(k.lines().any { "transport-seek-unmatched" in it })
            k.r.at(61_000 + 20_100)
            assertNull(k.behind)
            assertTrue(k.lines().toString(), k.lines().any { "reason=transport-seek-unmatched" in it })
            k.r.at(700_000)
            // The seek was closed at the known place as the behind play ended: the rest of the item counted.
            assertTrue(k.markedPlayed())
        }

    private fun Rig.tuneCount() = tune.calls.count { it is Call.Tune || it is Call.TuneHandle }
}
