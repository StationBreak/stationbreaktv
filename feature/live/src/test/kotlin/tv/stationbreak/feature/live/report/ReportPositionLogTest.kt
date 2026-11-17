// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.UserItemState
import kotlin.time.Duration.Companion.seconds

/**
 * Issue 816: a live visit reports the safe position (the item's resume point, else 0; ADR-0018), never the viewer's
 * real position. The log lines must say so, or `pos=0` reads as a broken report.
 */
class ReportPositionLogTest {
    @Test
    fun anUnwatchedItemReportsAndLogsSafePositionZero() =
        runTest {
            val rig = Rig(this)
            rig.server.set(UserItemState(0.seconds, false))
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(90_000)
            assertTrue(rig.calls.filter { it.kind == "start" || it.kind == "progress" }.all { it.posMs == 0L })
            assertTrue(rig.lines.contains("report start safePos=0"))
            assertTrue(rig.lines.contains("report progress safePos=0"))
            assertTrue(rig.lines.none { it.contains(" pos=") })
        }

    @Test
    fun aResumePointIsTheSafePositionInEveryLine() =
        runTest {
            val rig = Rig(this)
            rig.server.set(UserItemState(7.seconds, false))
            rig.reporter.sittingStarted(1)
            rig.visit(1, "a")
            rig.at(60_000)
            assertEquals(1, rig.lines.count { it == "report start safePos=7000" })
            assertTrue(rig.lines.contains("report progress safePos=7000"))
        }
}
