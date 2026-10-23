// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** An unexpected exception from the schedule or library never ends the run: the card, a pause, a fresh sitting. */
class LiveChannelFailureTest {
    private fun Rig.ordinaryDay() {
        schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
    }

    private val secret = "boom: item 0123456789abcdef at https://jellyfin.example/secret"

    @Test
    fun aScheduleThatThrowsWhileChoosingIsRetriedAfterTheBackoff() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            var thrown = false
            r.schedule.onAirObserver = {
                if (!thrown) {
                    thrown = true
                    error(secret)
                }
            }
            val job = r.start()

            assertTrue(job.isActive)
            assertEquals(LiveState.Ident(null, null), r.state)
            assertTrue(r.tunes.isEmpty())

            r.at(5_000 - 1)
            assertTrue(r.tunes.isEmpty())
            r.at(5_000)
            assertEquals(itemOf(1), r.tunes.single().item)
            assertEquals(10.minutes + LiveTimings().crashBackoff, r.tunes.single().start)
            assertTrue(job.isActive)
        }

    @Test
    fun aScheduleThatThrowsAtABoundaryShowsTheChannelCardThenReanchors() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            var calls = 0
            r.schedule.onAirObserver = { if (++calls == 2) error(secret) }
            val job = r.start()
            r.atMin(50) // the boundary: the schedule is asked again, and throws

            assertTrue(job.isActive)
            assertEquals(LiveState.Ident(null, ChannelTag(7, "Test Channel 7")), r.state)
            assertTrue(r.tune.calls.last() is Call.Stop)

            r.at(50 * MIN + 5_000)
            // Re-anchored to the clock: the second show, 5 s in.
            assertEquals(itemOf(2), r.tunes.last().item)
            assertEquals(5.seconds, r.tunes.last().start)
            assertTrue(job.isActive)
        }

    @Test
    fun theFailureIsReportedByClassNameOnly() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            var thrown = false
            r.schedule.onAirObserver = {
                if (!thrown) {
                    thrown = true
                    error(secret)
                }
            }
            r.start()

            val failed = r.reports.filterIsInstance<TuneReport.Failed>().single()
            assertEquals("sitting failed error=IllegalStateException", failed.line())
            assertFalse(failed.line().contains("secret"))
        }

    @Test
    fun cancellingTheRunStillEndsItWhileItWaitsOutTheBackoff() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.ordinaryDay()
            r.schedule.onAirObserver = { error(secret) }
            val job = r.start()
            r.at(2_000)
            assertTrue(job.isActive)

            job.cancel()
            r.at(3_000)
            assertFalse(job.isActive)
            assertTrue(r.tune.calls.any { it is Call.Stop })
        }
}
