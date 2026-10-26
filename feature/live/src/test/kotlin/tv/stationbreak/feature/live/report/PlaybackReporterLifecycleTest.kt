// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PlaybackReporterLifecycleTest {
    @Test
    fun `a leave then a visit to another airing sends Stopped for the first then Playing for the second`() =
        runTest {
            val rig = Rig(this)
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(31_000)
            rig.reporter.leave()
            rig.at(32_000)
            rig.visit(1, "b", block = 1)
            rig.at(32_000)
            assertEquals(listOf("userdata", "start", "stopped", "userdata", "start"), rig.kinds())
            assertEquals("a", rig.calls[2].item)
            assertEquals("b", rig.calls[4].item)
        }

    @Test
    fun `a leave sends Stopped at once`() =
        runTest {
            val rig = Rig(this)
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(31_000)
            rig.reporter.leave()
            rig.at(31_000)
            assertEquals(Call("stopped", 31_000, "a", 0, failed = true), rig.calls.last())
        }

    @Test
    fun `closeAndJoin sends the final Stopped even when the scope is cancelled right after`() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val rig = Rig(this, scope = scope)
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(31_000)
            launch {
                rig.reporter.closeAndJoin()
                scope.cancel()
            }
            runCurrent()
            assertEquals("stopped", rig.kinds().last())
        }

    @Test
    fun `closeAndJoin cuts a hung call at its limit`() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val rig = Rig(this, scope = scope)
            rig.server.stopHangs = true
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(31_000)
            val job =
                launch {
                    rig.reporter.closeAndJoin(limit = 1.seconds)
                    scope.cancel()
                }
            rig.at(31_999)
            assertFalse(job.isCompleted)
            rig.at(32_000)
            assertTrue(job.isCompleted)
        }

    @Test
    fun `a call that throws is logged and the next tick still reports`() =
        runTest {
            val rig = Rig(this)
            rig.server.userDataThrows = true
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(30_000)
            rig.server.userDataThrows = false
            rig.at(60_000)
            assertTrue(rig.lines.contains("report error kind=exception"))
            assertEquals(Call("start", 60_000, "a", 0), rig.calls.single { it.kind == "start" })
        }

    @Test
    fun `dwell is what is left of startAfter since the sitting began`() {
        val ledger = WatchLedger(ReportingConfig())
        val t0 = Instant.fromEpochMilliseconds(0)
        ledger.sittingStarted(1, t0)
        assertEquals(30.seconds, ledger.dwell(1, t0))
        assertEquals(10.seconds, ledger.dwell(1, t0 + 20.seconds))
        assertEquals(0.seconds, ledger.dwell(1, t0 + 30.seconds))
        assertEquals(0.seconds, ledger.dwell(1, t0 + 90.seconds))
        assertEquals(30.seconds, ledger.dwell(2, t0 + 90.seconds))
    }

    @Test
    fun `entering a later block of a channel drops its earlier blocks`() {
        val ledger = WatchLedger(ReportingConfig())
        val t0 = Instant.fromEpochMilliseconds(0)
        ledger.enter(AiringKey(ChannelId("c1"), 0, ItemId("a")), t0)
        ledger.enter(AiringKey(ChannelId("c2"), 0, ItemId("a")), t0)
        assertEquals(2, ledger.size)
        ledger.enter(AiringKey(ChannelId("c1"), 1, ItemId("b")), t0)
        assertEquals(2, ledger.size)
        assertEquals(0.seconds, ledger.watched(AiringKey(ChannelId("c1"), 0, ItemId("a")), t0))
    }

    @Test
    fun `the ledger never holds more than its cap`() {
        val ledger = WatchLedger(ReportingConfig())
        val t0 = Instant.fromEpochMilliseconds(0)
        repeat(WatchLedger.MAX_ENTRIES + 50) { ledger.enter(AiringKey(ChannelId("c$it"), 0, ItemId("a")), t0) }
        assertEquals(WatchLedger.MAX_ENTRIES, ledger.size)
    }
}
