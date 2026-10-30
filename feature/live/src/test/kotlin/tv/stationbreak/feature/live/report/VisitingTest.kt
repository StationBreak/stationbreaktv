// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.feature.live.Visiting
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** [Visiting] against a real reporter: what a failed play does to the watched time, seen through mark-played. */
class VisitingTest {
    /**
     * A 10 min item (marked played at 9 min watched) is played from 0; at 30 s the play fails, `positionMs` is [failedAt]
     * (or unknown), and it plays on from 30 s at once. Returns when "played" was sent, or null.
     */
    private fun playedAt(failedAt: Long?): Long? {
        var played: Long? = null
        runTest {
            val rig = Rig(this)
            val visiting = Visiting(rig.reporter)
            rig.reporter.sittingStarted(1)
            visiting.playing(0) { rig.reporter.visit(1, key("a"), target("a", 10.minutes), 0) }
            rig.at(30_000)
            if (failedAt == null) visiting.failedPlay() else visiting.failedPlay(failedAt)
            visiting.playing(30_000) { rig.reporter.visit(1, key("a"), target("a", 10.minutes), 30_000) }
            rig.at(700_000)
            played = rig.calls.firstOrNull { it.kind == "played" }?.atMs
        }
        return played
    }

    @Test
    fun aFailedPlayClosesItsIntervalAtTheExactPosition() {
        // Exact 20 s watched before the failure, so 9 min is reached at 550 s: the tick at 570 s marks it played.
        assertEquals(570.seconds.inWholeMilliseconds, playedAt(20_000))
    }

    @Test
    fun aFailedPlayWithoutAPositionClosesItAtTheDerivedOne() {
        // 30 s watched before the failure, so 9 min is reached at 540 s: the tick at 540 s marks it played.
        assertEquals(540.seconds.inWholeMilliseconds, playedAt(null))
    }
}
