// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.model.UserItemState
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PlaybackReporterTest {
    @Test
    fun `a fresh tune sends nothing before 30 s then userdata and Playing at the safe position and check-ins`() =
        runTest {
            val rig = Rig(this)
            rig.server.set(UserItemState(7.seconds, false))
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(29_999)
            assertEquals(emptyList<Call>(), rig.calls)
            rig.at(30_000)
            assertEquals(listOf("userdata", "start"), rig.kinds())
            assertEquals(Call("start", 30_000, "a", 7_000), rig.calls.last())
            rig.at(60_000)
            rig.at(90_000)
            assertEquals(
                listOf(Call("progress", 60_000, "a", 7_000), Call("progress", 90_000, "a", 7_000)),
                rig.calls.filter { it.kind == "progress" },
            )
            rig.at(91_000)
            rig.reporter.leave()
            rig.at(93_000)
            assertEquals(Call("stopped", 91_000, "a", 7_000, failed = true), rig.calls.last())
        }

    @Test
    fun `an unwatched item reports position 0`() =
        runTest {
            val rig = Rig(this)
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(30_000)
            assertEquals(0L, rig.calls.single { it.kind == "start" }.posMs)
        }

    @Test
    fun `a played item reports position 0 even with a resume point`() =
        runTest {
            val rig = Rig(this)
            rig.server.set(UserItemState(9.seconds, true))
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(30_000)
            assertEquals(0L, rig.calls.single { it.kind == "start" }.posMs)
        }

    @Test
    fun `a settled sitting reports the next airing at once`() =
        runTest {
            val rig = Rig(this)
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(31_000)
            rig.reporter.leave()
            rig.visit(1, "b", block = 1)
            rig.at(31_000)
            assertEquals(listOf("userdata", "start", "stopped", "userdata", "start"), rig.kinds())
            assertEquals(Call("start", 31_000, "b", 0), rig.calls.last())
        }

    @Test
    fun `a retry of the same airing in the same sitting is one visit and one Playing`() =
        runTest {
            val rig = Rig(this)
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(10_000)
            rig.visit(1, "a")
            rig.at(40_000)
            rig.visit(1, "a")
            rig.at(70_000)
            assertEquals(1, rig.kinds().count { it == "start" })
            assertEquals(1, rig.kinds().count { it == "userdata" })
            assertEquals(1, rig.kinds().count { it == "progress" })
        }

    @Test
    fun `flipping away inside 30 s reports nothing and flipping back adds the watched time up`() =
        runTest {
            val rig = Rig(this, ReportingConfig(startAfter = 30.seconds))
            val short = 100.seconds
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a", runtime = short)
            rig.at(25_000)
            rig.reporter.leave()
            rig.at(60_000)
            assertEquals(emptyList<Call>(), rig.calls)

            rig.reporter.sittingStarted(2)
            // live: the channel has moved on while the viewer was away, so this stretch of media is new
            rig.visit(2, "a", runtime = short, fromMs = 60_000)
            rig.at(60_000 + 64_999)
            // 25 s + 65 s = 90 s needs the full runtime x 0.9; one millisecond short of it, nothing is marked
            assertTrue(rig.calls.none { it.kind == "played" })
            rig.at(60_000 + 65_000)
            // ticks run at 30 s and 60 s of the second visit; the mark is judged at the next tick or the leave
            rig.reporter.leave()
            rig.at(60_000 + 67_000)
            assertEquals(1, rig.calls.count { it.kind == "played" })
        }

    @Test
    fun `a stall is not watched time and an open stall at leave is subtracted`() =
        runTest {
            val rig = Rig(this)
            val runtime = 100.seconds
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a", runtime = runtime)
            rig.at(20_000)
            rig.reporter.stall()
            rig.at(60_000) // 40 s stalled
            rig.reporter.resumed()
            rig.at(130_000) // watched 20 + 70 = 90 s - exactly at the threshold
            rig.reporter.leave()
            rig.at(132_000)
            assertEquals(1, rig.calls.count { it.kind == "played" })
        }

    @Test
    fun `an open stall at leave subtracts`() =
        runTest {
            val rig = Rig(this)
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a", runtime = 100.seconds)
            rig.at(50_000)
            rig.reporter.stall()
            rig.at(200_000)
            rig.reporter.leave()
            rig.at(202_000)
            assertTrue(rig.calls.none { it.kind == "played" })
        }

    @Test
    fun `at 90 percent watched the item is marked played once and later reports use position 0`() =
        runTest {
            val rig = Rig(this)
            rig.server.set(UserItemState(11.seconds, false))
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a", runtime = 100.seconds)
            rig.at(30_000)
            rig.at(60_000)
            rig.at(90_000) // the 90 s tick: exactly 0.9 x 100 s
            rig.at(120_000)
            rig.at(121_000)
            rig.reporter.leave()
            rig.at(123_000)
            assertEquals(1, rig.calls.count { it.kind == "played" })
            val reported = rig.calls.filter { it.kind == "start" || it.kind == "progress" }
            assertEquals(listOf(11_000L, 11_000L, 0L), reported.map { it.posMs }.take(3))
            assertEquals(0L, rig.calls.last { it.kind == "progress" }.posMs)
            assertEquals(Call("stopped", 121_000, "a", 0, failed = true), rig.calls.last())
        }

    @Test
    fun `marking is gated on watched time not on a user data read`() =
        runTest {
            val rig = Rig(this)
            repeat(3) { rig.server.userDataQueue += ServerResult.Failure(MediaServerError.Unknown()) }
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a", runtime = 60.seconds)
            rig.at(60_000)
            rig.reporter.leave()
            rig.at(62_000)
            assertEquals(1, rig.calls.count { it.kind == "played" })
            assertTrue(rig.calls.none { it.kind == "start" })
        }

    @Test
    fun `an unknown runtime never marks played`() =
        runTest {
            val rig = Rig(this)
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a", runtime = null)
            rig.at(10 * 60_000)
            rig.reporter.leave()
            rig.at(10 * 60_000 + 2_000)
            assertTrue(rig.calls.none { it.kind == "played" })
        }

    @Test
    fun `user data failing twice then succeeding starts reporting on the third tick`() =
        runTest {
            val rig = Rig(this)
            repeat(2) { rig.server.userDataQueue += ServerResult.Failure(MediaServerError.Timeout()) }
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(30_000)
            rig.at(60_000)
            assertTrue(rig.calls.none { it.kind == "start" })
            rig.at(90_000)
            assertEquals(Call("start", 90_000, "a", 0), rig.calls.single { it.kind == "start" })
        }

    @Test
    fun `user data is tried at most three times per visit`() =
        runTest {
            val rig = Rig(this)
            repeat(10) { rig.server.userDataQueue += ServerResult.Failure(MediaServerError.Timeout()) }
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(300_000)
            assertEquals(3, rig.kinds().count { it == "userdata" })
            assertTrue(rig.calls.none { it.kind == "start" })
        }

    @Test
    fun `a 401 stops all reporting for the run`() =
        runTest {
            val rig = Rig(this)
            rig.server.userDataQueue += ServerResult.Failure(MediaServerError.SessionExpired())
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(30_000)
            rig.reporter.leave()
            rig.reporter.sittingStarted(2)
            rig.visit(2, "b")
            rig.at(200_000)
            assertEquals(listOf("userdata"), rig.kinds())
        }

    @Test
    fun `a Stopped that never returns times out at 5 s and the next Playing still goes out after it`() =
        runTest {
            val rig = Rig(this)
            rig.server.stopHangs = true
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(31_000)
            rig.reporter.leave()
            rig.visit(1, "b", block = 1)
            rig.at(35_999)
            assertEquals(listOf("userdata", "start", "stopped"), rig.kinds())
            rig.at(36_000)
            assertEquals(listOf("userdata", "start", "stopped", "userdata", "start"), rig.kinds())
            assertEquals(Call("start", 36_000, "b", 0), rig.calls.last())
            assertTrue(rig.lines.any { it == "report error kind=timeout" })
        }

    @Test
    fun `leave stall and resume queued behind a hung call keep their own timestamps`() =
        runTest {
            val rig = Rig(this)
            rig.server.progressHangs = true
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a", runtime = 65_500.milliseconds)
            rig.at(40_000) // start went out at 30 s
            rig.reporter.stall()
            rig.at(50_000)
            rig.reporter.resumed()
            rig.at(61_000) // the 60 s progress hangs until 65 s
            rig.reporter.stall()
            rig.at(62_000)
            rig.reporter.resumed()
            rig.at(70_000)
            rig.reporter.leave()
            rig.at(80_000)
            // watched = 70 - 10 - 1 = 59 s >= 0.9 x 65.5 s: judged at the events own times
            assertEquals(1, rig.calls.count { it.kind == "played" })
            assertEquals(Call("stopped", 70_000, "a", 0, failed = true), rig.calls.last())
        }

    @Test
    fun `a 401 of the HttpUnauthorized kind halts reporting too`() =
        runTest {
            val rig = Rig(this)
            rig.server.userDataQueue += ServerResult.Failure(MediaServerError.HttpUnauthorized())
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(300_000)
            assertEquals(listOf("userdata"), rig.kinds())
        }

    @Test
    fun `a 401 after Playing was sent halts check-ins and the final Stopped`() =
        runTest {
            val rig = Rig(this)
            rig.server.progressResult = ServerResult.Failure(MediaServerError.SessionExpired())
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(60_000)
            rig.reporter.leave()
            rig.reporter.sittingStarted(2)
            rig.visit(2, "b")
            rig.at(300_000)
            assertEquals(listOf("userdata", "start", "progress"), rig.kinds())
        }

    @Test
    fun `log lines carry only numbers and labels`() =
        runTest {
            val rig = Rig(this)
            rig.server.set(UserItemState(5.seconds, false))
            rig.server.userDataQueue += ServerResult.Failure(MediaServerError.Timeout())
            rig.reporter.sittingStarted(1)
            rig.visit(1, "secret-item-id", runtime = 60.seconds)
            rig.at(60_000)
            rig.at(90_000)
            rig.at(120_000)
            rig.reporter.leave()
            rig.at(123_000)
            val shape = Regex("report (start|progress|stop|played|error)( [a-z]+=[a-z0-9]+)*")
            assertTrue(rig.lines.isNotEmpty())
            rig.lines.forEach { assertTrue(it, shape.matches(it)) }
            val kinds = rig.lines.map { it.substringAfter("report ").substringBefore(' ') }.toSet()
            assertEquals(setOf("error", "start", "progress", "played", "stop"), kinds)
        }

    @Test
    fun `close sends the final Stopped and ignores later events`() =
        runTest {
            val rig = Rig(this)
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(30_000)
            rig.reporter.close()
            rig.at(30_000)
            assertEquals(listOf("userdata", "start", "stopped"), rig.kinds())
            rig.visit(1, "b", block = 1)
            rig.at(200_000)
            assertEquals(3, rig.calls.size)
        }
}
