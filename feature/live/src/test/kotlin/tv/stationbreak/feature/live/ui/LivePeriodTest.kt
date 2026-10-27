// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.playback.PlayerSource
import tv.stationbreak.feature.live.ReportFacts
import tv.stationbreak.feature.live.Rig
import tv.stationbreak.feature.live.report.RecordingServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Leaving live TV (#431): the player is stopped and released, and the Stopped report sent, the moment the screen
 * leaves, not after the suspending teardown; nothing starts the player again; coming back starts cleanly.
 */
class LivePeriodTest {
    private val facts = ReportFacts(MediaSourceId("src"), PlaySessionId("ps"), PlayMethod.DirectPlay, 50.minutes)

    private fun TestScope.reportingRig(server: RecordingServer): Rig {
        val r = Rig(this, startMin = 10, server = server)
        r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
        r.tune.reportFacts = facts
        // A slow tuner stop holds the suspending teardown: the leave must not wait for it.
        r.tune.stopDelayMs = 10_000
        return r
    }

    private val source =
        PlayerSource("http://example.invalid/a.mp4", false, Duration.ZERO, emptyList(), null, null, true)

    private fun RecordingServer.count(kind: String) = calls.count { it.kind == kind }

    @Test
    fun leavingReleasesThePlayerAndSendsOneStoppedAtOnce() =
        runTest {
            val server = RecordingServer({ currentTime })
            val r = reportingRig(server)
            val job = r.start()
            r.at(45_000)
            assertEquals(1, server.count("start"))
            val inner = RecordingPlayer()
            val period = LivePeriod(inner, job).also { it.machine = r.live }

            period.leave()

            // On the caller's thread, before any coroutine ran: the player is stopped, detached and released.
            assertEquals(listOf("stop", "attach(null)", "release"), inner.log)
            assertTrue("the sitting is cancelled", job.isCancelled)
            // The Stopped goes out on the next dispatch, while the tuner's slow stop still holds the teardown.
            r.scope.runCurrent()
            assertEquals(1, server.count("stopped"))
            assertEquals(45_000L, server.calls.single { it.kind == "stopped" }.atMs)

            // The teardown that follows neither reaches the released player nor reports again.
            period.player.stop()
            period.player.attach(null)
            period.player.play(source)
            period.player.release()
            period.leave()
            r.at(120_000)
            assertEquals(listOf("stop", "attach(null)", "release"), inner.log)
            assertEquals(1, server.count("stopped"))
        }

    @Test
    fun comingBackAfterALeaveReportsOneNewStart() =
        runTest {
            val server = RecordingServer({ currentTime })
            val r = reportingRig(server)
            val job = r.start()
            r.at(45_000)
            LivePeriod(RecordingPlayer(), job).also { it.machine = r.live }.leave()
            r.at(60_000)
            assertTrue(job.isCompleted)

            r.launchRun()
            r.at(60_000 + 29_000)
            assertEquals("no start inside the new dwell", 1, server.count("start"))
            r.at(60_000 + 31_000)
            assertEquals(2, server.count("start"))
            assertEquals(1, server.count("stopped"))
            assertEquals("stopped", server.calls.filter { it.kind != "userdata" }[1].kind)
        }
}
