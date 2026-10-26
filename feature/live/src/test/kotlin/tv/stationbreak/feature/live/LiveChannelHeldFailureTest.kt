// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.playback.PlayNote
import tv.stationbreak.core.playback.PlaybackEnd

/** Why a play that had reached playing failed, and the stalls before it (#392): numbers and codes only. */
class LiveChannelHeldFailureTest {
    private fun Rig.ordinaryDay() {
        schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
    }

    private val failure =
        PlaybackEnd.Failed(
            errorCode = "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            httpStatus = 503,
            afterMs = 12_000,
            positionMs = 11_500,
            bufferedMs = 0,
            stalls = 2,
        )

    @Test
    fun aFailureLineHasTheReasonAndTheNumbers() {
        assertEquals(
            "held failed reason=ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT afterMs=12000 posMs=11500 bufferedMs=0 " +
                "http=503 stalls=2",
            TuneReport.HeldFailed(failure).line(),
        )
    }

    @Test
    fun aStallTimeoutLineHasNoHttpStatus() {
        val timeout =
            PlaybackEnd.Failed("StallTimeout", afterMs = 32_000, positionMs = 12_000, bufferedMs = 0, stalls = 1)
        assertEquals(
            "held failed reason=StallTimeout afterMs=32000 posMs=12000 bufferedMs=0 http=-1 stalls=1",
            TuneReport.HeldFailed(timeout).line(),
        )
    }

    @Test
    fun theStallLinesAreTheStallAndItsResume() {
        assertEquals(
            "stall posMs=11500 bufferedMs=40 afterMs=11000",
            TuneReport.Stalling(PlayNote.Stall(11_500, 40, 11_000)).line(),
        )
        assertEquals("stall resumed ms=3200", TuneReport.Stalling(PlayNote.Resumed(3_200)).line())
    }

    @Test
    fun aFailedPlayIsReportedWhenTheShowIsRetried() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            r.tune.script(
                itemOf(1),
                {
                    TuneOutcome.Playing(
                        flow {
                            delay(12_000)
                            emit(failure)
                        },
                    )
                },
            )
            r.start()
            r.at(13_000)

            val held = r.reports.filterIsInstance<TuneReport.HeldFailed>().single()
            assertEquals(failure, held.end)
        }

    @Test
    fun stallsAreReportedAsTheyComeAndOnePlayKeepsPlaying() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            val notes =
                flow {
                    delay(4_000)
                    emit(PlayNote.Stall(4_100, 20, 4_000))
                    delay(1_500)
                    emit(PlayNote.Resumed(1_500))
                }
            r.tune.script(itemOf(1), { TuneOutcome.Playing(flow { delay(600_000) }, notes = notes) })
            r.start()
            r.at(10_000)

            assertEquals(
                listOf(
                    "stall posMs=4100 bufferedMs=20 afterMs=4000",
                    "stall resumed ms=1500",
                ),
                r.reports.filterIsInstance<TuneReport.Stalling>().map { it.line() },
            )
        }
}
