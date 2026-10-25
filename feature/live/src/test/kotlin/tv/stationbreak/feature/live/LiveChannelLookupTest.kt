// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Flipping while the schedule is slow or failing (#392): lookups, the lineup read, and Menu. */
class LiveChannelLookupTest {
    private fun Rig.flipReports() = reports.filterIsInstance<TuneReport.Flip>()

    @Test
    fun everyCommittedPressWritesALineWhileTheLookupIsBlocked() =
        runTest {
            val f = flipping()
            f.r.schedule.onAirDelayMs = 5_000
            f.up()
            // The settle has run out and the commit is inside its lookup.
            advanceTimeBy(1.seconds)
            f.up()
            advanceTimeBy(1.seconds)
            f.down()
            advanceTimeBy(20.seconds)
            runCurrent()
            assertEquals(listOf("superseded", "superseded", "playing"), f.r.flipReports().map { it.outcome })
            assertEquals(
                itemOf(9),
                f.r.tunes
                    .last()
                    .item,
            )
        }

    @Test
    fun aRunEndingInsideACommitsLookupLogsItAbandoned() =
        runTest {
            val f = flipping()
            f.r.schedule.onAirDelayMs = 5_000
            f.up()
            advanceTimeBy(1.seconds)
            f.job.cancel()
            runCurrent()
            advanceTimeBy(10.seconds)
            runCurrent()
            assertEquals(listOf("abandoned"), f.r.flipReports().map { it.outcome })
        }

    @Test
    fun theNumberKeepsMovingWhileACommitsLookupWillNotStop() =
        runTest {
            val f = flipping()
            f.r.schedule.onAirDelayMs = 5_000
            f.r.schedule.onAirUncancellable = true
            f.up()
            advanceTimeBy(1.seconds)
            f.up()
            advanceTimeBy(1.seconds)
            f.up()
            // Three presses from channel 5: 9, 2, then 5 again; the third must show though the first lookup runs on.
            assertEquals(ChannelTag(5, "Test Channel 5"), (f.r.state as LiveState.Choosing).target)
            advanceTimeBy(20.seconds)
            runCurrent()
            // The first two commits are superseded; the third is a full wrap back to channel 5 and tunes nothing.
            assertEquals(listOf("superseded", "superseded"), f.r.flipReports().map { it.outcome })
        }

    @Test
    fun menuDoesNotHoldBackThePressesBehindItsLookup() =
        runTest {
            val f = flipping()
            f.up()
            advanceTimeBy(2.seconds)
            runCurrent()
            f.r.schedule.onAirDelayMs = 5_000
            f.r.schedule.onAirUncancellable = true
            f.send(LiveCommand.Previous)
            advanceTimeBy(1.seconds)
            f.up()
            assertTrue("the number moves", f.r.state is LiveState.Choosing)
            // The lookup of the cancelled Menu ends under the newer press: it must not cancel that press's commit.
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(
                itemOf(2),
                f.r.tunes
                    .last()
                    .item,
            )
            // The Menu was a commit, cancelled in its lookup by the press: superseded, then the press's own.
            assertEquals(listOf("playing", "superseded", "playing"), f.r.flipReports().map { it.outcome })
            assertTrue("the overlay is dropped", f.r.state !is LiveState.Choosing)
        }

    @Test
    fun aMenuWhoseLookupFailsAfterAPressCancelledItIsSupersededNotFailed() =
        runTest {
            val f = flipping()
            f.up()
            advanceTimeBy(2.seconds)
            runCurrent()
            f.r.schedule.onAirDelayMs = 5_000
            f.r.schedule.onAirUncancellable = true
            f.r.schedule.onAirThrowsAfterWaitOn = setOf(f.r.schedule.onAirCalls.size + 1)
            f.send(LiveCommand.Previous)
            advanceTimeBy(1.seconds)
            f.up()
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(
                itemOf(2),
                f.r.tunes
                    .last()
                    .item,
            )
            assertEquals(listOf("playing", "superseded", "playing"), f.r.flipReports().map { it.outcome })
            assertTrue("no failure is reported", f.r.reports.none { it is TuneReport.Failed })
            assertTrue("the overlay is dropped", f.r.state !is LiveState.Choosing)
        }

    @Test
    fun aMenuWithNoPreviousChannelLeavesAQueuedUpToLand() =
        runTest {
            val f = flipping { schedule.airingsFailOn = setOf(2) }
            f.r.schedule.airingsDelayMs = 5_000
            f.up()
            advanceTimeBy(1.seconds)
            f.send(LiveCommand.Previous)
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(
                itemOf(9),
                f.r.tunes
                    .last()
                    .item,
            )
        }

    @Test
    fun aMenuPressedAfterAQueuedUpIsTheNewerIntent() =
        runTest {
            // Reads 2 and 5 fail (the background refreshes); 1 and 3 are the start, 4 the first flip's.
            val f = flipping { schedule.airingsFailOn = setOf(2, 5) }
            f.up()
            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(
                itemOf(9),
                f.r.tunes
                    .last()
                    .item,
            )
            // No lineup is held now; the read for the next press is slow.
            f.r.schedule.airingsDelayMs = 5_000
            f.up()
            advanceTimeBy(1.seconds)
            f.send(LiveCommand.Previous)
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(
                itemOf(5),
                f.r.tunes
                    .last()
                    .item,
            )
            assertTrue("the older Up was not applied", f.r.tunes.none { it.item == itemOf(2) })
            assertEquals(listOf("playing", "playing"), f.r.flipReports().map { it.outcome })
        }

    @Test
    fun aMenuWhoseLookupThrowsWritesAFailedFlipLine() =
        runTest {
            val f = flipping()
            f.up()
            advanceTimeBy(2.seconds)
            runCurrent()
            f.r.schedule.onAirObserver = { error("boom") }
            f.send(LiveCommand.Previous)
            runCurrent()
            assertEquals(listOf("playing", "failed"), f.r.flipReports().map { it.outcome })
        }

    @Test
    fun pressesMadeWhileTheLineupIsBeingReadAreAppliedInOrderOnce() =
        runTest {
            // The background refresh after the first tune (read 2) fails: the presses find no lineup to step through.
            val f = flipping { schedule.airingsFailOn = setOf(2) }
            f.r.schedule.airingsDelayMs = 5_000
            val reads = f.r.schedule.airingsCalls
            f.up()
            advanceTimeBy(500.milliseconds)
            f.up()
            advanceTimeBy(500.milliseconds)
            f.down()
            advanceTimeBy(20.seconds)
            runCurrent()
            // Up, up, down from channel 5 is 9, 2, 9: one commit, one line, and one read for all three.
            assertEquals(
                itemOf(9),
                f.r.tunes
                    .last()
                    .item,
            )
            assertEquals(listOf("playing"), f.r.flipReports().map { it.outcome })
            assertTrue(f.r.schedule.airingsCalls - reads <= 2)
        }

    @Test
    fun aCommitWhoseLookupThrowsWritesAFailedFlipLine() =
        runTest {
            val f = flipping()
            f.r.schedule.onAirObserver = { error("boom") }
            f.up()
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(listOf("failed"), f.r.flipReports().map { it.outcome })
            assertTrue(f.r.reports.any { it is TuneReport.Failed })
        }
}
