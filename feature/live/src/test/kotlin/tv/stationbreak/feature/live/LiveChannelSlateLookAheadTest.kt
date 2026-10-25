// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.playback.PlaybackEnd
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Look-ahead on a channel that shows a card instead of a picture (#392): the neighbours are resolved while it waits,
 * so the next flip off it is a neighbour tune, not a fresh one.
 */
class LiveChannelSlateLookAheadTest {
    private val ch2 = ChannelId("ch-2")

    private class Seated(
        val r: Rig,
        val job: Job,
        val keys: Channel<LiveCommand>,
    )

    /** Channels 2, 5 and 9; [onFive] is what channel 5 airs (the sitting is on it). */
    private suspend fun TestScope.sitting(
        onFive: List<AiringBlock>,
        timings: LiveTimings = LiveTimings(),
        nine: List<AiringBlock> = listOf(program(0, 600, 9)),
        setup: Rig.() -> Unit = {},
    ): Seated {
        val r = Rig(this, timings = timings)
        r.setup()
        r.schedule.channel(ch2, 2, listOf(program(0, 600, 2)))
        r.schedule.channel(CH5, 5, onFive)
        r.schedule.channel(CH9, 9, nine)
        r.lastChannel.save(r.account, CH5)
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        val job = r.start(commands = keys.receiveAsFlow())
        runCurrent()
        return Seated(r, job, keys)
    }

    private fun Seated.up() {
        keys.trySend(LiveCommand.Flip(FlipDirection.Up))
    }

    private fun Rig.preResolves() = tune.calls.filterIsInstance<Call.PreResolve>()

    private fun Rig.discards() = tune.calls.filterIsInstance<Call.Discard>()

    private fun Rig.tuneKinds() = reports.filterIsInstance<TuneReport.Tune>().map { it.kind }

    @Test
    fun aSlateBlockResolvesBothNeighboursAtOnce() =
        runTest {
            val s = sitting(listOf(slate(0, 600)))
            assertTrue(s.r.state is LiveState.Ident)
            assertEquals(
                setOf(itemOf(9), itemOf(2)),
                s.r
                    .preResolves()
                    .map { it.item }
                    .toSet(),
            )
        }

    @Test
    fun aFlipOffASlateTakesTheNeighboursHandle() =
        runTest {
            val s = sitting(listOf(slate(0, 600)))
            s.up()
            runCurrent()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertTrue(
                s.r.tune.calls
                    .any { it is Call.TuneHandle && it.item == itemOf(9) },
            )
            assertTrue(s.r.tunes.none { it.item == itemOf(9) })
            assertEquals(listOf(TuneKind.Neighbour), s.r.tuneKinds())
        }

    @Test
    fun aDeadItemsSlateResolvesTheNeighboursWhileItWaits() =
        runTest {
            val s = sitting(listOf(program(0, 600, 5, info = null)))
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(s.r.state is LiveState.TechnicalDifficulties)
            assertEquals(
                setOf(itemOf(9), itemOf(2)),
                s.r
                    .preResolves()
                    .map { it.item }
                    .toSet(),
            )
            s.up()
            runCurrent()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertEquals(listOf(TuneKind.Neighbour), s.r.tuneKinds())
        }

    @Test
    fun theLostChannelsCardResolvesAgainWhenANeighboursBlockEnds() =
        runTest {
            // Channel 5 stops airing at minute 11; the card then holds for five minutes. Nine's first block ends at 12.
            val s =
                sitting(
                    listOf(program(0, 11, 5)),
                    LiveTimings(slateHold = 5.minutes),
                    nine = listOf(program(0, 12, 9), program(12, 600, 19)),
                )
            advanceTimeBy(1.minutes + 1.seconds)
            runCurrent()
            assertEquals(LiveState.Ident(null), s.r.state)
            advanceTimeBy(2.minutes)
            runCurrent()
            assertTrue("the old handle was let go", s.r.discards().any { it.item == itemOf(9) })
            assertTrue("the new block was resolved on the card", s.r.preResolves().any { it.item == itemOf(19) })
            s.up()
            runCurrent()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertTrue(
                s.r.tune.calls
                    .any { it is Call.TuneHandle && it.item == itemOf(19) },
            )
        }

    @Test
    fun theLineupIsReadOnceWhenTheSlatesLookAheadMeetsTheRefreshInFlight() =
        runTest {
            val s = sitting(listOf(slate(0, 600))) { schedule.airingsDelayMs = 500 }
            advanceTimeBy(2.seconds)
            runCurrent()
            // One read to choose the channel, one refresh; the look-ahead waited for the refresh, it did not read.
            assertEquals(2, s.r.schedule.airingsCalls)
            assertEquals(
                setOf(itemOf(9), itemOf(2)),
                s.r
                    .preResolves()
                    .map { it.item }
                    .toSet(),
            )
        }

    @Test
    fun aShowThatEndsEarlyKeepsItsLookAheadWithoutResolvingAgain() =
        runTest {
            val s =
                sitting(listOf(program(0, 600, 5))) {
                    tune.script(itemOf(5), tune.playsThenEnds(2.seconds, PlaybackEnd.Ended))
                }
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(s.r.state is LiveState.Watching)
            val resolves = s.r.preResolves().size
            val neighbourReads =
                s.r.schedule.onAirCalls
                    .count { it.first != CH5 }
            advanceTimeBy(2.seconds)
            runCurrent()
            assertTrue(s.r.state is LiveState.Ident)
            assertEquals(resolves, s.r.preResolves().size)
            assertEquals(
                neighbourReads,
                s.r.schedule.onAirCalls
                    .count { it.first != CH5 },
            )
            assertTrue(s.r.discards().isEmpty())
        }

    @Test
    fun theSlatesLookAheadEndsWhenTheRunEnds() =
        runTest {
            val s = sitting(listOf(slate(0, 600)), nine = listOf(program(0, 12, 9), program(12, 600, 19)))
            s.job.cancel()
            runCurrent()
            assertEquals(
                setOf(itemOf(9), itemOf(2)),
                s.r
                    .discards()
                    .map { it.item }
                    .toSet(),
            )
            val resolved = s.r.preResolves().size
            // Past the end of nine's first block: nothing looks again.
            advanceTimeBy(5.minutes)
            runCurrent()
            assertEquals(resolved, s.r.preResolves().size)
        }

    @Test
    fun flippingAwayEndsTheSlatesLookAheadAndLetsTheOtherHandleGo() =
        runTest {
            val s = sitting(listOf(slate(0, 600)))
            s.up()
            runCurrent()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            // Nine plays from its handle; the handle for two (the slate's other neighbour) was let go.
            assertTrue(s.r.discards().any { it.item == itemOf(2) })
            assertTrue(s.r.discards().none { it.item == itemOf(9) })
        }
}
