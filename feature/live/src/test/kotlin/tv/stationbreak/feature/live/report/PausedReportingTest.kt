// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.UserItemState
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Spec 20, ADR-0021 decision 7: paused check-ins for pauseReportMax, then silence, then a fresh Playing. */
class PausedReportingTest {
    /** A reported visit (resume point 7 s, Playing at 30 s) with the viewer at 45 s, ready to pause. */
    private fun Rig.playing() {
        server.set(UserItemState(7.seconds, false))
        reporter.sittingStarted(1)
        visit(1, "a")
        at(45_000)
    }

    @Test
    fun pausedCheckInsCarryIsPausedAtTheSafePosition() =
        runTest {
            val rig = Rig(this)
            rig.playing()
            rig.reporter.paused()
            rig.at(60_000)
            assertEquals(Call("progress", 60_000, "a", 7_000, paused = true), rig.calls.last())
            assertEquals(Call("start", 30_000, "a", 7_000), rig.calls.single { it.kind == "start" })
        }

    @Test
    fun pausedCheckInsStopAtPauseReportMax() =
        runTest {
            val rig = Rig(this)
            rig.playing()
            rig.reporter.paused()
            rig.at(345_000)
            assertEquals(10, rig.calls.count { it.kind == "progress" && it.paused })
            rig.at(345_000 + 600_000)
            assertEquals(10, rig.calls.count { it.kind == "progress" })
            assertEquals(1, rig.lines.count { it == "report silent afterMs=315000" })
            assertEquals(1, rig.lines.count { it.startsWith("report silent") })
        }

    @Test
    fun playAfterTheSilenceSendsAFreshPlayingAtOnce() =
        runTest {
            val rig = Rig(this)
            rig.playing()
            rig.reporter.paused()
            rig.at(465_000)
            val before = rig.calls.size
            rig.reporter.unpaused()
            rig.at(465_000)
            assertEquals(Call("start", 465_000, "a", 7_000), rig.calls[before])
            assertEquals(before + 1, rig.calls.size)
            rig.at(510_000)
            val after = rig.calls.drop(before + 1)
            assertEquals(listOf(480_000L, 510_000L), after.map { it.atMs })
            assertTrue(after.all { it.kind == "progress" && !it.paused && it.posMs == 7_000L })
        }

    @Test
    fun playWithinTheWindowSendsNoSecondPlaying() =
        runTest {
            val rig = Rig(this)
            rig.playing()
            rig.reporter.paused()
            rig.at(165_000)
            rig.reporter.unpaused()
            rig.at(195_000)
            assertEquals(1, rig.calls.count { it.kind == "start" })
            assertEquals(Call("progress", 180_000, "a", 7_000, paused = false), rig.calls.last { it.atMs <= 180_000 })
            assertFalse(rig.calls.last().paused)
        }

    @Test
    fun aPauseBeforeTheFirstPlayingSendsNothingUntilPlay() =
        runTest {
            val rig = Rig(this)
            rig.server.set(UserItemState(7.seconds, false))
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(10_000)
            rig.reporter.paused()
            rig.at(600_000)
            assertEquals(emptyList<Call>(), rig.calls)
            rig.reporter.unpaused()
            rig.at(629_999)
            assertEquals(emptyList<Call>(), rig.calls)
            rig.at(630_000)
            assertEquals(listOf("userdata", "start"), rig.kinds())
            assertEquals(Call("start", 630_000, "a", 7_000), rig.calls.last())
        }

    @Test
    fun leaveAfterTheSilenceStillSendsAFailedStop() =
        runTest {
            val rig = Rig(this)
            rig.playing()
            rig.reporter.paused()
            rig.at(465_000)
            rig.reporter.leave()
            rig.at(470_000)
            assertEquals(Call("stopped", 465_000, "a", 7_000, failed = true), rig.calls.last())
            assertTrue(rig.calls.none { it.kind == "progress" && it.atMs >= 360_000 })
        }

    @Test
    fun aLeaveAfterAFailedFreshPlayingStillSendsAFailedStop() =
        runTest {
            val rig = Rig(this)
            rig.playing()
            rig.reporter.paused()
            rig.at(465_000)
            rig.server.startResult = ServerResult.Failure(MediaServerError.Timeout())
            rig.reporter.unpaused()
            rig.at(465_000)
            assertEquals(Call("start", 465_000, "a", 7_000), rig.calls.last())
            rig.reporter.leave()
            rig.at(470_000)
            assertEquals(Call("stopped", 465_000, "a", 7_000, failed = true), rig.calls.last())
        }

    @Test
    fun aPauseAfterAFailedFreshPlayingSendsNothingUntilPlay() =
        runTest {
            val rig = Rig(this)
            rig.playing()
            rig.reporter.paused()
            rig.at(465_000)
            rig.server.startResult = ServerResult.Failure(MediaServerError.Timeout())
            rig.reporter.unpaused()
            rig.at(465_000)
            rig.reporter.paused()
            val before = rig.calls.size
            rig.at(900_000)
            assertEquals(before, rig.calls.size)
            rig.server.startResult = ServerResult.Success(Unit)
            rig.reporter.unpaused()
            rig.at(900_000)
            // The second pause outlasted pauseReportMax too, so the Play sends the fresh Playing at once.
            assertEquals(Call("start", 900_000, "a", 7_000), rig.calls.last())
        }

    @Test
    fun aPausedCheckInLogsOnlyLabelsAndNumbers() =
        runTest {
            val rig = Rig(this)
            rig.playing()
            rig.reporter.paused()
            rig.at(60_000)
            assertTrue(rig.lines.contains("report progress paused=1 pos=7000"))
        }

    @Test
    fun aSessionErrorStillHaltsWhilePaused() =
        runTest {
            val rig = Rig(this)
            rig.playing()
            rig.reporter.paused()
            rig.server.progressResult = ServerResult.Failure(MediaServerError.SessionExpired())
            rig.at(60_000)
            rig.at(300_000)
            assertEquals(1, rig.calls.count { it.kind == "progress" })
        }

    @Test
    fun aSeekOrAPauseWithNoVisitIsIgnored() =
        runTest {
            val rig = Rig(this)
            rig.reporter.paused()
            rig.reporter.seeking()
            rig.reporter.seeked(1_000)
            rig.reporter.unpaused()
            rig.at(100_000)
            assertEquals(emptyList<Call>(), rig.calls)
        }

    @Test
    fun aSnapshotTakenBeforeThePauseIsHandledNeverCountsPastThePlace() =
        runTest {
            // The clock runs ahead of the worker: time passes while the pause still waits in the queue.
            var lagMs = 0L
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime + lagMs)
                }
            val server = RecordingServer({ testScheduler.currentTime })
            val reporter = PlaybackReporter(backgroundScope, server, SESSION, clock) {}
            reporter.sittingStarted(1)
            reporter.visit(1, key("a"), target("a"), 0)
            advanceTimeBy(600_000)
            runCurrent()
            // Paused at media 10:00; the worker has not handled it when the screen stops 5 minutes later.
            reporter.paused(600_000)
            lagMs = 300_000

            val place = 600_000L
            val snapshot = checkNotNull(reporter.snapshot(atMostMs = place))
            val watched = snapshot.spans.sumOf { it.endMs - it.startMs }
            assertTrue("watched $watched, place $place", watched <= place)
            assertTrue(snapshot.spans.all { it.endMs <= place })
        }
}
