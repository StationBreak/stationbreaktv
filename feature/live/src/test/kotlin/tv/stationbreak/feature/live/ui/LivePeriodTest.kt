// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.playback.PlayerSource
import tv.stationbreak.feature.live.Kept
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.Place
import tv.stationbreak.feature.live.ReportFacts
import tv.stationbreak.feature.live.Rig
import tv.stationbreak.feature.live.report.RecordingServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
            val period = LivePeriod(inner, job, null) {}.also { it.machine = r.live }

            assertTrue("the first leave ends the period", period.leave())

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
            assertFalse("a second leave does nothing", period.leave())
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
            LivePeriod(RecordingPlayer(), job, null) {}.also { it.machine = r.live }.leave()
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

    @Test
    fun theGuardedPlayerForwardsTransportUntilLeft() =
        runTest {
            val inner = RecordingPlayer()
            val period = LivePeriod(inner, Job(), null) {}

            period.player.pause(3)
            assertEquals(listOf("pause:3"), inner.log)
            period.player.resume(3)
            period.player.seekTo(3, 5.seconds)
            assertEquals(listOf("pause:3", "resume:3", "seek:3:5000"), inner.log)
            assertEquals(1.seconds, period.player.position(3))
            assertTrue(period.player.seekable(3))

            period.leave()
            val afterLeave = inner.log.toList()
            period.player.pause(3)
            period.player.resume(3)
            period.player.seekTo(3, 5.seconds)
            assertEquals(afterLeave, inner.log)
            assertNull(period.player.position(3))
            assertFalse(period.player.seekable(3))
        }

    /** CH7 joined at minute 10 and paused [pauseAtMs] in ([before] runs just before the pause); the rig and run. */
    private suspend fun TestScope.pausedRig(
        pauseAtMs: Long,
        before: (Rig) -> Unit = {},
    ): Pair<Rig, Job> {
        val r = Rig(this, startMin = 10)
        r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        val job = r.start(commands = keys.receiveAsFlow())
        r.at(pauseAtMs)
        before(r)
        keys.trySend(LiveCommand.PlayPause)
        runCurrent()
        return r to job
    }

    @Test
    fun leaveReadsTheKeptPlaceBeforeStoppingThePlayer() =
        runTest {
            val (r, job) = pausedRig(45_000)
            r.at(5 * 60_000)
            val inner = RecordingPlayer()
            var kept: Kept? = null
            val period =
                LivePeriod(inner, job, null) {
                    inner.log += "kept"
                    kept = it
                }.also { it.machine = r.live }

            assertTrue(period.leave())

            assertEquals(listOf("kept", "stop", "attach(null)", "release"), inner.log)
            // The paused player's own place: 10:45 of item 1, read before the teardown, not null.
            val place = checkNotNull(kept).place
            assertEquals(CH7, place.channel)
            assertEquals(program(0, 60, 1), place.block)
            assertEquals(checkNotNull(r.tune.controlOf(itemOf(1))).positionMs(), place.mediaMs)
            assertEquals(10 * 60_000L + 45_000L, place.mediaMs)
            assertFalse("a second leave keeps nothing again", period.leave())
            assertEquals(1, inner.log.count { it == "kept" })
        }

    @Test
    fun leaveWithAnUnknownPositionKeepsTheLastKnownPlace() =
        runTest {
            // The player cannot say where it is: the pause carries no position, so the place is the last one known.
            val (r, job) = pausedRig(45_000) { checkNotNull(it.tune.controlOf(itemOf(1))).positionUnknown = true }
            r.at(5 * 60_000)
            var kept: Kept? = null
            LivePeriod(RecordingPlayer(), job, null) { kept = it }.also { it.machine = r.live }.leave()
            assertEquals(10 * 60_000L + 45_000L, checkNotNull(kept).place.mediaMs)
        }

    @Test
    fun aLeaveBeforeTheMachineKeepsThePlaceItWasGiven() =
        runTest {
            // A screen stop before this period's machine exists: the place the period was given is kept, not lost.
            val given = Kept(Place(CH7, program(0, 60, 1), 10 * 60_000L), null)
            val inner = RecordingPlayer()
            var kept: Kept? = null
            val period = LivePeriod(inner, Job(), given) { kept = it }
            assertTrue(period.leave())
            assertEquals(given.copy(untended = true), kept)
            assertEquals(listOf("stop", "attach(null)", "release"), inner.log)
        }
}
