// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.feature.live.LiveTimings
import tv.stationbreak.feature.live.ReportFacts
import tv.stationbreak.feature.live.Rig
import tv.stationbreak.feature.live.report.RecordingServer
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The ViewModel's way out of a sitting (#431), as the screen drives it: a sitting reported as playing, then [leave].
 * Exactly one Stopped goes out, the sitting ends without cancelling whoever ran it, and a sitting after it reports
 * afresh.
 */
class LiveViewModelLeaveTest {
    private val facts = ReportFacts(MediaSourceId("src"), PlaySessionId("ps"), PlayMethod.DirectPlay, 50.minutes)

    private class Setup(
        val r: Rig,
        val server: RecordingServer,
        val vm: LiveViewModel,
    )

    private suspend fun TestScope.setup(): Setup {
        val server = RecordingServer({ currentTime })
        val r = Rig(this, startMin = 10, server = server)
        r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
        r.tune.reportFacts = facts
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

    private suspend fun Setup.sit(player: RecordingPlayer) = vm.sit({ player }, null, { _, _ -> r.tune }, { 0L }) {}

    private fun RecordingServer.count(kind: String) = calls.count { it.kind == kind }

    @Test
    fun leaveSendsOneStoppedAndEndsTheSittingWithoutCancellingItsCaller() =
        runTest {
            val s = setup()
            val player = RecordingPlayer()
            var returned = false
            val caller =
                backgroundScope.launch {
                    s.sit(player)
                    returned = true
                }
            advanceTimeBy(45.seconds)
            runCurrent()
            assertEquals(1, s.server.count("start"))

            s.vm.leave()
            runCurrent()

            assertEquals(1, s.server.count("stopped"))
            // Attached to no surface (a JVM test has none), then stopped, detached and released once.
            assertEquals(listOf("attach(null)", "stop", "attach(null)", "release"), player.log)
            assertTrue("sit returns after the leave", returned)
            assertFalse("the caller's job is not cancelled", caller.isCancelled)
            advanceTimeBy(60.seconds)
            runCurrent()
            assertEquals(1, s.server.count("stopped"))
        }

    @Test
    fun aSittingAfterALeaveReportsOneNewStart() =
        runTest {
            val s = setup()
            backgroundScope.launch { s.sit(RecordingPlayer()) }
            advanceTimeBy(45.seconds)
            runCurrent()
            s.vm.leave()
            runCurrent()

            backgroundScope.launch { s.sit(RecordingPlayer()) }
            advanceTimeBy(29.seconds)
            runCurrent()
            assertEquals(1, s.server.count("start"))
            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(2, s.server.count("start"))
            assertEquals(1, s.server.count("stopped"))
        }
}
