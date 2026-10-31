// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.feature.live.Call
import tv.stationbreak.feature.live.FakeTune
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.LiveTimings
import tv.stationbreak.feature.live.ReportFacts
import tv.stationbreak.feature.live.Rig
import tv.stationbreak.feature.live.report.RecordingServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The kept place across a screen stop (spec 20, ADR-0021 5): a paused place survives the stop in the ViewModel for
 * `pauseHoldMax`, is used by the next sitting once, and past the hold the viewer joins live, playing.
 */
class LiveViewModelKeptPlaceTest {
    private class Setup(
        val r: Rig,
        val server: RecordingServer,
        val vm: LiveViewModel,
    ) {
        /** Added to the monotonic clock (test time) the hold is measured on: a jump of the wall clock leaves it be. */
        var monoOffsetMs = 0L
    }

    /** CH7 from [blocks], joined at [startMin], with reporting (the item's runtime is [runtime]). */
    private suspend fun TestScope.setup(
        startMin: Long = 10,
        blocks: List<AiringBlock> = listOf(program(0, 180, 1), program(180, 240, 2)),
        runtime: Duration = 180.minutes,
    ): Setup {
        val server = RecordingServer({ currentTime })
        val r = Rig(this, startMin = startMin, server = server)
        r.schedule.channel(CH7, 7, blocks)
        r.tune.reportFacts = ReportFacts(MediaSourceId("src"), PlaySessionId("ps"), PlayMethod.DirectPlay, runtime)
        r.sessions.save(r.session)
        r.library.useAccount(r.account)
        r.channels.publish(CH7 to 7)
        val vm =
            LiveViewModel(
                r.schedule,
                r.lastChannel,
                r.sessions,
                r.library,
                r.channels,
                r.clock,
                ControllerTunerFactory(PlaybackResolver(server) { error("not resolved here") }, server),
                VideoPlayerFactory { _, _ -> error("the test makes its own players") },
                server,
                r.details,
                LiveTimings(),
            )
        return Setup(r, server, vm)
    }

    private fun TestScope.sit(s: Setup): Job {
        val job =
            backgroundScope.launch {
                s.vm.sit({ RecordingPlayer() }, null, { _, _ -> s.r.tune }, { currentTime + s.monoOffsetMs }) {}
            }
        runCurrent()
        return job
    }

    private fun TestScope.press(
        s: Setup,
        command: LiveCommand,
    ) {
        s.vm.send(command)
        runCurrent()
    }

    /** Sits, pauses at [pauseAtMs] (test time), and leaves the screen [stopAfterMs] later. */
    private fun TestScope.pauseAndStop(
        s: Setup,
        pauseAtMs: Long,
        stopAfterMs: Long = 0,
    ) {
        sit(s)
        s.r.at(pauseAtMs)
        press(s, LiveCommand.PlayPause)
        s.r.at(pauseAtMs + stopAfterMs)
        s.vm.leave()
        runCurrent()
    }

    @Test
    fun aPlaceKeptWithinTheHoldIsPassedToTheNextSitting() =
        runTest {
            val s = setup()
            // Paused at 11:00 of item 1, a minute in.
            pauseAndStop(s, MIN)
            val back = MIN + 59 * MIN
            s.r.at(back)
            sit(s)
            assertEquals(Call.Tune(itemOf(1), 11.minutes, back), s.r.tunes.last())
            assertEquals(
                listOf("pause"),
                s.r.tune.lastControl!!
                    .calls,
            )
            s.r.at(back + 1_000)
            runCurrent()
            assertTrue(
                s.vm.transport.value!!
                    .paused,
            )
        }

    @Test
    fun aReturnAfterTheHoldJoinsLivePlaying() =
        runTest {
            val s = setup()
            pauseAndStop(s, MIN)
            val back = MIN + 61 * MIN
            s.r.at(back)
            sit(s)
            // Minute 10 + 62 of the clock: live, from the clock's position, playing.
            assertEquals(Call.Tune(itemOf(1), 72.minutes, back), s.r.tunes.last())
            s.r.at(back + 10 * MIN)
            assertFalse(
                "pause" in
                    s.r.tune.lastControl!!
                        .calls,
            )
            assertEquals(
                emptyList<String>(),
                s.r.tune.lastControl!!
                    .calls,
            )
            runCurrent()
            assertNull(s.vm.transport.value)
        }

    @Test
    fun aKeptPlaceIsUsedOnce() =
        runTest {
            val s = setup()
            pauseAndStop(s, MIN)
            s.r.at(5 * MIN)
            val second = sit(s)
            assertEquals(Call.Tune(itemOf(1), 11.minutes, 5 * MIN), s.r.tunes.last())
            // This sitting ends without a screen stop (its caller is cancelled): nothing new is kept.
            second.cancel()
            runCurrent()
            s.r.at(6 * MIN)
            sit(s)
            assertEquals(Call.Tune(itemOf(1), 16.minutes, 6 * MIN), s.r.tunes.last())
            assertEquals(
                emptyList<String>(),
                s.r.tune.lastControl!!
                    .calls,
            )
        }

    @Test
    fun watchedTimeSurvivesAKeptPlace() =
        runTest {
            // A 30 minute item with a 30 minute runtime, joined at its start: played at 27 minutes (90 %).
            val s = setup(startMin = 0, blocks = listOf(program(0, 30, 1), program(30, 90, 2)), runtime = 30.minutes)
            // Watch 20 minutes, pause, and stop the screen.
            pauseAndStop(s, 20 * MIN)
            assertEquals(0, s.server.calls.count { it.kind == "played" })
            // Back five minutes later, within the hold, at the paused place; Play, then 8 minutes more: 28 in all.
            s.r.at(25 * MIN)
            sit(s)
            assertEquals(Call.Tune(itemOf(1), 20.minutes, 25 * MIN), s.r.tunes.last())
            s.r.at(25 * MIN + 1_000)
            press(s, LiveCommand.PlayPause)
            s.r.at(25 * MIN + 1_000 + 8 * MIN)
            assertEquals(1, s.server.calls.count { it.kind == "played" && it.item == itemOf(1).value })
        }

    @Test
    fun aStopDuringTheReturnsTuneKeepsThePlace() =
        runTest {
            val s = setup(startMin = 0, blocks = listOf(program(0, 30, 1), program(30, 90, 2)), runtime = 30.minutes)
            // The first tune plays at once; the return's own tune takes 5 s to answer.
            s.r.tune.script(itemOf(1), FakeTune.playing(), FakeTune.playsAfter(5.seconds))
            pauseAndStop(s, 20 * MIN)
            s.r.at(25 * MIN)
            sit(s)
            assertEquals(Call.Tune(itemOf(1), 20.minutes, 25 * MIN), s.r.tunes.last())
            // The screen stops again before the tune answers.
            s.r.at(25 * MIN + 1_000)
            s.vm.leave()
            runCurrent()

            s.r.at(26 * MIN)
            sit(s)
            assertEquals(Call.Tune(itemOf(1), 20.minutes, 26 * MIN), s.r.tunes.last())
            assertEquals(
                listOf("pause"),
                s.r.tune.lastControl!!
                    .calls,
            )
            // The watched time is intact: 20 minutes before, 8 after, so the item is marked played (27 of 30).
            s.r.at(26 * MIN + 1_000)
            press(s, LiveCommand.PlayPause)
            s.r.at(26 * MIN + 1_000 + 8 * MIN)
            assertEquals(1, s.server.calls.count { it.kind == "played" })
        }

    @Test
    fun aReturnLeftWithoutPlayingKeepsTheFirstHold() =
        runTest {
            val s = setup()
            pauseAndStop(s, MIN)
            s.r.at(31 * MIN)
            sit(s)
            assertEquals(Call.Tune(itemOf(1), 11.minutes, 31 * MIN), s.r.tunes.last())
            s.r.at(32 * MIN)
            s.vm.leave()
            runCurrent()
            // One untended pause since minute 1: an hour after it, the viewer joins live, playing.
            val back = MIN + 61 * MIN
            s.r.at(back)
            sit(s)
            assertEquals(Call.Tune(itemOf(1), 72.minutes, back), s.r.tunes.last())
            assertEquals(
                emptyList<String>(),
                s.r.tune.lastControl!!
                    .calls,
            )
        }

    @Test
    fun aPlayAfterTheReturnStartsAFreshHold() =
        runTest {
            val s = setup()
            pauseAndStop(s, MIN)
            s.r.at(31 * MIN)
            sit(s)
            s.r.at(31 * MIN + 1_000)
            press(s, LiveCommand.PlayPause)
            s.r.at(32 * MIN)
            press(s, LiveCommand.PlayPause)
            s.vm.leave()
            runCurrent()
            // Played, then paused at 11:59 at minute 32: a new pause, held an hour from then.
            val back = MIN + 61 * MIN
            s.r.at(back)
            sit(s)
            val tune = s.r.tunes.last()
            assertEquals(11 * MIN + 59_000, tune.start.inWholeMilliseconds)
            assertEquals(
                listOf("pause"),
                s.r.tune.lastControl!!
                    .calls,
            )
        }

    @Test
    fun theHoldIsMeasuredOnTheMonotonicClock() =
        runTest {
            val s = setup(blocks = listOf(program(0, 600, 1)))
            pauseAndStop(s, MIN)
            // Half an hour on the monotonic clock, but the wall clock was set two hours ahead meanwhile.
            s.r.at(31 * MIN)
            s.r.jumpMs = 120 * MIN
            sit(s)
            assertEquals(Call.Tune(itemOf(1), 11.minutes, 31 * MIN), s.r.tunes.last())
            assertEquals(
                listOf("pause"),
                s.r.tune.lastControl!!
                    .calls,
            )
        }

    @Test
    fun aMonotonicClockThatRanBackwardsEndsTheHold() =
        runTest {
            val s = setup()
            pauseAndStop(s, MIN)
            s.r.at(5 * MIN)
            s.monoOffsetMs = -10 * MIN
            sit(s)
            assertEquals(Call.Tune(itemOf(1), 15.minutes, 5 * MIN), s.r.tunes.last())
            assertEquals(
                emptyList<String>(),
                s.r.tune.lastControl!!
                    .calls,
            )
            s.r.at(5 * MIN + 1_000)
            runCurrent()
            assertNull(s.vm.transport.value)
        }

    @Test
    fun aScheduleRegeneratedDuringTheHoldDropsThePlace() =
        runTest {
            val s = setup()
            pauseAndStop(s, MIN)
            // The lineup was built again: another item now holds the same start.
            s.r.schedule.channel(CH7, 7, listOf(program(0, 180, 3), program(180, 240, 2)))
            s.r.at(30 * MIN)
            sit(s)
            assertEquals(Call.Tune(itemOf(3), 40.minutes, 30 * MIN), s.r.tunes.last())
            assertEquals(
                emptyList<String>(),
                s.r.tune.lastControl!!
                    .calls,
            )
        }
}
