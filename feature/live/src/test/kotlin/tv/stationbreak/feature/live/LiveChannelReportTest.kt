// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the machine reports about each tune and each boundary cut, on a fake clock. */
class LiveChannelReportTest {
    private fun Rig.ordinaryDay() {
        schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2), slate(120, 180), program(180, 240, 3)))
    }

    @Test
    fun aClockJoinReportsHowLateThePictureIsAgainstTheSchedule() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            val timings = TuneTimings(firstFrameMs = 1_800, playingMs = 2_000, positionErrorMs = 300, fallback = "seekMiss")
            r.tune.script(itemOf(1), FakeTune.playsAfter(2.seconds, timings))
            r.start()
            r.at(2_000)

            // Tuned at 10:00 into the show; the picture came 2 s later, so it is 2 s behind the schedule.
            val expected = TuneReport.Tune(TuneKind.Fresh, -1, 1_800, 2_000, 300, "seekMiss", lagMs = 2_000)
            assertEquals(listOf<TuneReport>(expected), r.reports)
        }

    @Test
    fun aMediaOffsetDoesNotCountAsLag() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1, mediaStartMs = 90 * MIN)))
            r.tune.script(itemOf(1), FakeTune.playsAfter(500.milliseconds))
            r.start()
            r.at(500)

            assertEquals(500L, (r.reports.single() as TuneReport.Tune).lagMs)
        }

    @Test
    fun aBoundaryCutReportsItsGapAndItsLag() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            val timings = TuneTimings(firstFrameMs = 250, playingMs = 280)
            r.tune.script(itemOf(2), FakeTune.playsAfter(300.milliseconds, timings))
            r.start()
            r.at(50 * MIN + 300)

            val tail = r.reports.drop(1)
            assertEquals(
                listOf<TuneReport>(
                    TuneReport.Tune(TuneKind.Prepared, 0, 250, 280, null, null, lagMs = 300),
                    TuneReport.Cut(gapMs = 300, resolveMs = 0, toFirstFrameMs = 250),
                ),
                tail,
            )
        }

    @Test
    fun aCutWithNoUsableHandleIsAFreshTuneAndItsResolveIsNotSeparate() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            r.tune.preResolveResult = { false }
            r.start()
            r.at(50 * MIN)

            val cut = r.reports.last() as TuneReport.Cut
            assertEquals(TuneReport.NOT_SEPARATE, cut.resolveMs)
            assertEquals(TuneKind.Fresh, (r.reports[r.reports.size - 2] as TuneReport.Tune).kind)
        }

    @Test
    fun theSecondTryOfADeadItemIsARetry() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.playing())
            r.start()

            assertEquals(listOf(TuneKind.Retry), r.reports.map { (it as TuneReport.Tune).kind })
        }

    @Test
    fun aStandInIsReportedAsOne() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            r.schedule.standInScript = { _, _ -> listOf(standIn(0, 60, 7, 0)) }
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()
            r.at(3_000)

            assertEquals(listOf(TuneKind.StandIn), r.reports.map { (it as TuneReport.Tune).kind })
            assertTrue((r.reports.single() as TuneReport.Tune).lagMs == 0L)
        }

    @Test
    fun aFirstTuneIsNotACut() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            r.start()

            assertTrue(r.reports.none { it is TuneReport.Cut })
            assertEquals(10.minutes, r.tunes.single().start)
        }

    @Test
    fun theLogLinesAreNumbersAndFixedNamesOnly() {
        val tune = TuneReport.Tune(TuneKind.Fresh, -1, 1_800, 2_000, null, null, 40)
        val line = "tune kind=fresh resolveMs=-1 firstFrameMs=1800 playingMs=2000 posErrMs=-1 fallback=none lagMs=40"
        assertEquals(line, tune.line())
        val cut = TuneReport.Cut(gapMs = 300, resolveMs = 0, toFirstFrameMs = null)
        assertEquals("cut gapMs=300 resolveMs=0 toFirstFrameMs=-1", cut.line())
    }
}
