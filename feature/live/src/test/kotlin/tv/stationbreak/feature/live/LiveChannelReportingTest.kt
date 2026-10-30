// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
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
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.standIn
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.playback.PlayControl
import tv.stationbreak.core.playback.PlaybackEnd
import tv.stationbreak.feature.live.report.RecordingServer
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import tv.stationbreak.feature.live.report.Call as ReportCall

/** The live channel's playback reporting (spec 16, ADR-0018): when it reports, and that it never slows the screen. */
class LiveChannelReportingTest {
    private val facts = ReportFacts(MediaSourceId("src"), PlaySessionId("ps"), PlayMethod.DirectPlay, 50.minutes)

    private fun TestScope.server() = RecordingServer({ currentTime })

    private fun Rig.reporting() {
        tune.reportFacts = facts
    }

    private fun Rig.ordinaryDay() {
        schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
    }

    private fun List<ReportCall>.kinds() = map { it.kind }

    private fun Rig.reportLines() = reports.filterIsInstance<TuneReport.Reported>().map { it.line() }

    @Test
    fun aProgramThatPlaysIsReportedAfterTheDwellAndLogsOnlyLabels() =
        runTest {
            val server = server()
            val r = Rig(this, startMin = 10, server = server)
            r.ordinaryDay()
            r.reporting()
            r.start()
            r.at(29_000)
            assertTrue(server.calls.isEmpty())
            r.at(31_000)
            assertEquals(listOf("userdata", "start"), server.calls.kinds())
            assertTrue(r.reportLines().isNotEmpty())
            assertTrue(r.reportLines().all { it.startsWith("report ") })
            assertFalse(r.reportLines().any { it.contains(itemOf(1).value) || it.contains("src") })
        }

    @Test
    fun theRunPassesItsPauseReportMaxToTheReporter() =
        runTest {
            val server = server()
            val r = Rig(this, startMin = 10, timings = LiveTimings(pauseReportMax = 1.minutes), server = server)
            r.ordinaryDay()
            r.reporting()
            r.start()
            r.at(31_000)
            assertEquals(1, server.calls.count { it.kind == "start" })
            checkNotNull(r.live.reporting).paused()
            r.at(400_000)
            // Paused at 31 s: check-ins at 60 s and 90 s are under a minute into the pause, then the silence.
            assertEquals(listOf(60_000L, 90_000L), server.calls.filter { it.paused }.map { it.atMs })
            assertEquals(2, server.calls.count { it.kind == "progress" })
        }

    @Test
    fun aRetryOfTheSameBlockAfterAFailedPlayIsOneVisit() =
        runTest {
            val server = server()
            val r = Rig(this, startMin = 10, server = server)
            r.ordinaryDay()
            r.reporting()
            val failure = PlaybackEnd.Failed("Err", afterMs = 40_000, positionMs = 39_000, bufferedMs = 0, stalls = 0)
            r.tune.script(
                itemOf(1),
                {
                    TuneOutcome.Playing(
                        flow {
                            delay(40_000)
                            emit(failure)
                        },
                        PlayControl.None,
                    )
                },
                // The retry takes longer than the leave grace: the visit must not depend on the grace.
                FakeTune.playsAfter(5.seconds),
            )
            r.start()
            r.at(41_000)
            assertEquals(1, server.calls.count { it.kind == "start" })
            // The failure is at 40 s; the retry plays at about 45 s. Nothing is stopped in between or after.
            r.at(48_000)
            assertEquals(2, r.tunes.size)
            assertEquals(0, server.calls.count { it.kind == "stopped" })
            r.at(100_000)
            assertEquals(1, server.calls.count { it.kind == "start" })
            assertEquals(0, server.calls.count { it.kind == "stopped" })
            assertTrue(server.calls.any { it.kind == "progress" })
        }

    @Test
    fun slatesAndIdentsNeverReport() =
        runTest {
            val server = server()
            val r = Rig(this, startMin = 10, server = server)
            r.schedule.channel(CH7, 7, listOf(slate(0, 60)))
            r.reporting()
            r.start()
            r.at(120_000)
            assertTrue(r.state is LiveState.Ident)
            assertTrue(server.calls.isEmpty())
        }

    @Test
    fun aStandInInPlaceOfADeadItemNeverReports() =
        runTest {
            val server = server()
            val r = Rig(this, startMin = 10, server = server)
            r.schedule.channel(CH7, 7, listOf(program(0, 600, 9, info = null)))
            r.schedule.standInScript = { _, _ -> listOf(standIn(0, 600, 7, 0)) }
            r.reporting()
            r.start()
            r.at(120_000)
            assertTrue(r.tunes.any { it.item == itemOf(7) })
            assertTrue(server.calls.isEmpty())
        }

    @Test
    fun leavingLiveTvEndsAtOnceAndStillSendsTheFinalStoppedWithTheSafePosition() =
        runTest {
            val server = server()
            val r = Rig(this, startMin = 10, server = server)
            r.ordinaryDay()
            r.reporting()
            val job = r.start()
            r.at(45_000)
            job.cancel()
            r.scope.runCurrent()
            // The teardown did not wait for the server.
            assertTrue(job.isCompleted)
            r.at(46_000)
            val stopped = server.calls.last()
            assertEquals("stopped", stopped.kind)
            assertTrue(stopped.failed)
            assertEquals(0L, stopped.posMs)
        }

    @Test
    fun aHungFinalStoppedNeverHoldsTheTeardownAndIsAbandoned() =
        runTest {
            val server = server()
            server.stopHangs = true
            val r = Rig(this, startMin = 10, server = server)
            r.ordinaryDay()
            r.reporting()
            val job = r.start()
            r.at(45_000)
            job.cancel()
            r.scope.runCurrent()
            assertTrue("run() ends at once", job.isCompleted)
            assertEquals(1, server.calls.count { it.kind == "stopped" })
            // Abandoned: nothing more is sent however long we wait.
            val before = server.calls.size
            r.at(45_000 + 60_000)
            assertEquals(before, server.calls.size)
        }

    @Test
    fun aHungStoppedDoesNotMoveTheFlipOrTheTuneTimings() {
        val clean = flipTimings(hang = false)
        val hung = flipTimings(hang = true)
        assertEquals(clean.line, hung.line)
        assertEquals(clean.tunes, hung.tunes)
        assertTrue("the hung scenario did send a Stopped", hung.stoppedSent)
    }

    @Test
    fun aHungStoppedDoesNotMoveTheCutTimingsAtABoundary() {
        val clean = boundaryTimings(hang = false)
        val hung = boundaryTimings(hang = true)
        assertEquals(clean.line, hung.line)
        assertEquals(clean.tunes, hung.tunes)
        assertTrue("the cut line was written", clean.line.contains("cut "))
        assertTrue("the hung scenario did send a Stopped", hung.stoppedSent)
    }

    private class Timings(
        val line: String,
        val tunes: List<Call>,
        val stoppedSent: Boolean,
    )

    /** A show that has been reported for 45 s ends at a boundary; the next one is cut in. */
    private fun boundaryTimings(hang: Boolean): Timings {
        var out: Timings? = null
        runTest {
            val server = server()
            server.stopHangs = hang
            val r = Rig(this, startMin = 10, server = server)
            r.schedule.channel(CH7, 7, listOf(program(0, 11, 1), program(11, 60, 2)))
            r.reporting()
            r.start()
            r.at(90_000)
            val lines = r.reports.filter { it !is TuneReport.Reported }.map { it.line() }
            out = Timings(lines.joinToString("\n"), r.tune.calls.toList(), server.calls.any { it.kind == "stopped" })
        }
        return checkNotNull(out)
    }

    private fun flipTimings(hang: Boolean): Timings {
        var out: Timings? = null
        runTest {
            val server = server()
            server.stopHangs = hang
            val f = flipping(server = server) { reporting() }
            // Past the dwell: channel 5 is reported as playing.
            advanceTimeBy(40.seconds)
            runCurrent()
            f.up()
            advanceTimeBy(2.seconds)
            runCurrent()
            val flip =
                f.r.reports
                    .filterIsInstance<TuneReport.Flip>()
                    .single()
            // The flip's own line and every port call (with its virtual time) must not depend on the server.
            out =
                Timings(
                    flip.line(),
                    f.r.tune.calls
                        .toList(),
                    server.calls.any { it.kind == "stopped" },
                )
        }
        return checkNotNull(out)
    }
}
