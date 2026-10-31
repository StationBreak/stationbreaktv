// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.standIn
import tv.stationbreak.core.playback.PlaybackEnd
import tv.stationbreak.feature.live.ui.keepsScreenOn
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A play that fails while the viewer is paused (ADR-0021 failure table, "Fails while paused"): nothing is tuned, no
 * stand-in, no live, and the screen stays released. The block's one retry runs when the viewer presses Play; with none
 * left, Play takes the usual dead-item or catch-up routing then. Nothing ever plays without a press.
 */
class FailWhilePausedTest {
    private class Keys(
        val r: Rig,
        val keys: Channel<LiveCommand>,
        val job: Job,
    ) {
        fun pressAt(
            ms: Long,
            command: LiveCommand,
        ) {
            r.at(ms)
            keys.trySend(command)
            r.scope.testScheduler.runCurrent()
        }

        val transport: TransportView? get() = r.live.transport.value

        fun tuneCount() = r.tune.calls.count { it is Call.Tune || it is Call.TuneHandle }

        fun finish() {
            job.cancel()
            r.scope.testScheduler.runCurrent()
            r.assertEveryPreResolveEndsOnce()
        }
    }

    /** CH7, item 1 from minute 0 to 60, joined at minute 10; item 1's tunes answer as [scripted] says. */
    private suspend fun TestScope.keys(scripted: Rig.() -> List<suspend () -> TuneOutcome>): Keys {
        val r = Rig(this, startMin = 10)
        r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
        r.schedule.standInScript = { _, _ -> listOf(standIn(0, 60, 8, 0)) }
        r.tune.script(itemOf(1), *r.scripted().toTypedArray())
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        val job = r.start(commands = keys.receiveAsFlow())
        return Keys(r, keys, job)
    }

    /** Where the play says it failed: media 11:00, where the viewer paused. */
    private val broke = PlaybackEnd.Failed("Boom", positionMs = 11 * MIN)

    /** Fails 2 minutes after the tune (the viewer paused a minute before). */
    private fun Rig.failsAtTwoMinutes() = tune.playsThenEnds(2.minutes, broke)

    private fun Keys.assertHeldPaused() {
        val view = checkNotNull(transport)
        assertTrue(view.paused)
        assertFalse("the screen is released", keepsScreenOn(view))
        assertTrue(r.schedule.standInCalls.isEmpty())
        assertTrue(r.seen.none { it is LiveState.TechnicalDifficulties })
        assertEquals(Place(CH7, program(0, 60, 1), 11 * MIN), r.live.kept()?.place)
    }

    @Test
    fun aPausedPlayThatFailsTunesNothingAndStaysPaused() =
        runTest {
            val k = keys { listOf(failsAtTwoMinutes()) }
            k.pressAt(MIN, LiveCommand.PlayPause)
            // The play fails at minute 2; ten minutes on, nothing has been tuned again and nothing plays.
            k.r.atMin(12)
            assertEquals(1, k.tuneCount())
            k.assertHeldPaused()
            k.finish()
        }

    @Test
    fun playAfterAFailureWhilePausedTakesTheRetry() =
        runTest {
            val k = keys { listOf(failsAtTwoMinutes()) }
            k.pressAt(MIN, LiveCommand.PlayPause)
            k.pressAt(12 * MIN, LiveCommand.PlayPause)
            assertEquals(Call.Tune(itemOf(1), 11.minutes, 12 * MIN), k.r.tunes.last())
            k.r.at(12 * MIN + 1_000)
            // It plays (the viewer asked): no pause on the retry, and the transport is behind, playing.
            assertEquals(
                emptyList<String>(),
                k.r.tune.lastControl!!
                    .calls,
            )
            assertFalse(checkNotNull(k.transport).paused)
            k.finish()
        }

    @Test
    fun playWithNoRetryLeftTakesTheDeadItemRoutingThen() =
        runTest {
            // The first tune is dead and takes the block's retry; the retry plays, is paused, and fails.
            val k = keys { listOf(FakeTune.dead(), failsAtTwoMinutes()) }
            k.pressAt(MIN, LiveCommand.PlayPause)
            k.r.atMin(12)
            assertEquals(2, k.tuneCount())
            k.assertHeldPaused()
            // Play with no retry left: the item is dead for its block, as on the live path, now that the viewer asked.
            k.pressAt(12 * MIN, LiveCommand.PlayPause)
            k.r.at(12 * MIN + 5_000)
            assertTrue(k.r.seen.any { it is LiveState.TechnicalDifficulties })
            assertEquals(1, k.r.schedule.standInCalls.size)
            k.finish()
        }

    @Test
    fun aStepStillSettlingWhenThePlayFailsIsWherePlayLands() =
        runTest {
            val k = keys { listOf(failsAtTwoMinutes()) }
            k.pressAt(MIN, LiveCommand.PlayPause)
            // Back 10 s from 11:00 with the key still down: the target, 10:50, is still settling when the play fails.
            k.pressAt(2 * MIN - 300, LiveCommand.Seek(SeekDirection.Back))
            k.r.at(12 * MIN)
            // The strip shows where the viewer stepped to, with no target left pending.
            val view = checkNotNull(k.transport)
            assertTrue(view.paused)
            assertEquals(10 * MIN + 50_000, view.mediaMs)
            assertNull(view.targetMs)
            // Play: the retry lands there, with no second seek to the old target.
            k.pressAt(12 * MIN, LiveCommand.PlayPause)
            assertEquals(Call.Tune(itemOf(1), 650.seconds, 12 * MIN), k.r.tunes.last())
            k.r.at(13 * MIN)
            assertTrue(
                k.r.tune.lastControl!!
                    .calls
                    .none { it.startsWith("seek") },
            )
            k.finish()
        }
}
