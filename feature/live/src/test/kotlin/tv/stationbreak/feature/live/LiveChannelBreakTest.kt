// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.id
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.info
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.stationBreak
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A station break (rules-format v5): the ident with "Up next" for the break's length, then the next programme, which
 * was resolved during the card as the next programme after a programme is.
 */
class LiveChannelBreakTest {
    private fun Rig.preResolves() = tune.calls.filterIsInstance<Call.PreResolve>()

    private fun Rig.handleTunes() = tune.calls.filterIsInstance<Call.TuneHandle>()

    /** Channel 7: movie 1 to minute 60, a three-minute break, movie 2 to 120. */
    private fun Rig.withABreak() {
        schedule.channel(CH7, 7, listOf(program(0, 60, 1), stationBreak(60, 63), program(63, 120, 2)))
    }

    @Test
    fun aBreakHoldsTheIdentWithUpNextAndPlaysOnAtItsEnd() =
        runTest {
            val r = Rig(this)
            r.withABreak()
            r.start() // movie 1, 10 min in
            r.atMin(50) // 60 on the clock: the break
            val ident = assertIs<LiveState.Ident>(r.state)
            val up = checkNotNull(ident.upNext)
            assertEquals(BannerLabel.UpNext, up.label)
            assertEquals("Test Movie 2", up.title)
            assertEquals(T0 + 63 * MIN, up.slotStartMs)
            val (_, callsThen) = r.states.last { it.first is LiveState.Ident }
            assertIs<Call.Stop>(r.tune.calls[callsThen - 1])

            r.at(53 * MIN - 1)
            assertIs<LiveState.Ident>(r.state)
            r.atMin(53) // 63 on the clock: the break ends
            assertEquals(Call.TuneHandle(itemOf(2), Duration.ZERO, 53 * MIN), r.handleTunes().single())
            r.at(53 * MIN + 4_000)
            assertIs<LiveState.Watching>(r.state)
        }

    @Test
    fun theProgrammeAfterABreakIsWarmedDuringTheCard() =
        runTest {
            val r = Rig(this)
            r.withABreak()
            r.start()
            // Movie 1's look-ahead sees the break, not a programme: nothing is resolved before the card.
            r.at(50 * MIN - 1)
            assertTrue(r.preResolves().none { it.item == itemOf(2) })
            // On the card, the programme after the break is resolved the lead before the break ends.
            r.atMin(50)
            assertIs<LiveState.Ident>(r.state)
            r.at(53 * MIN - 45_000)
            assertIs<LiveState.Ident>(r.state)
            assertEquals(
                listOf(Call.PreResolve(itemOf(2), Duration.ZERO, 53 * MIN - 45_000)),
                r.preResolves().filter { it.item == itemOf(2) },
            )
            assertTrue(r.tunes.none { it.item == itemOf(2) })
        }

    @Test
    fun aFiveSecondBreakEndsCleanlyIntoTheNextProgramme() =
        runTest {
            val r = Rig(this)
            val breakStart = T0 + 60 * MIN
            val breakEnd = breakStart + 5_000
            r.schedule.channel(
                CH7,
                7,
                listOf(
                    program(0, 60, 1),
                    AiringBlock(breakStart, breakEnd, Airing.Break),
                    AiringBlock(breakEnd, T0 + 120 * MIN, Airing.Program(id(2), 0, info(2))),
                ),
            )
            r.start()
            r.atMin(50)
            assertEquals(T0 + 60 * MIN + 5_000, assertIs<LiveState.Ident>(r.state).upNext?.slotStartMs)
            r.at(50 * MIN + 5_000)
            assertEquals(Call.TuneHandle(itemOf(2), Duration.ZERO, 50 * MIN + 5_000), r.handleTunes().single())
            assertTrue("no plain tune", r.tunes.none { it.item == itemOf(2) })
            r.at(50 * MIN + 9_000)
            assertIs<LiveState.Watching>(r.state)
        }

    @Test
    fun aFlipOntoABreakShowsTheIdentAndReportsABreak() =
        runTest {
            val f = flipping { schedule.channels.getValue(CH9).blocks = listOf(stationBreak(0, 600)) }
            f.up()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertIs<LiveState.Ident>(f.r.state)
            assertEquals(
                "break",
                f.r.reports
                    .filterIsInstance<TuneReport.Flip>()
                    .single()
                    .outcome,
            )
        }

    @Test
    fun aTwoSecondBreakAfterAProgrammeCutsToAnAlreadyResolvedProgramme() =
        runTest {
            val r = Rig(this)
            val breakStart = T0 + 60 * MIN
            val breakEnd = breakStart + 2_000
            r.schedule.channel(
                CH7,
                7,
                listOf(
                    program(0, 60, 1),
                    AiringBlock(breakStart, breakEnd, Airing.Break),
                    AiringBlock(breakEnd, T0 + 120 * MIN, Airing.Program(id(2), 0, info(2))),
                ),
            )
            r.start()
            // Movie 1 looks past the break: the programme after it is resolved the lead before it starts.
            val resolveAt = 50 * MIN + 2_000 - 45_000
            r.at(resolveAt - 1)
            assertTrue(r.preResolves().none { it.item == itemOf(2) })
            r.at(resolveAt)
            assertEquals(
                listOf(Call.PreResolve(itemOf(2), Duration.ZERO, resolveAt)),
                r.preResolves().filter { it.item == itemOf(2) },
            )
            assertIs<LiveState.Watching>(r.state)
            r.atMin(50)
            assertIs<LiveState.Ident>(r.state)
            r.at(50 * MIN + 2_000)
            assertEquals(Call.TuneHandle(itemOf(2), Duration.ZERO, 50 * MIN + 2_000), r.handleTunes().single())
            // Held through the card: one resolve, never discarded, no plain tune.
            assertEquals(1, r.preResolves().count { it.item == itemOf(2) })
            assertTrue(r.tune.calls.none { it is Call.Discard && it.item == itemOf(2) })
            assertTrue(r.tunes.none { it.item == itemOf(2) })
        }

    @Test
    fun aNeighbourOnABreakHasTheProgrammeAfterItResolvedWarmedAndKeptThroughAFlip() =
        runTest {
            val r = Rig(this)
            // Seven plays; five (the neighbour Down, the one warmed before any flip) is on a break to minute 12.
            r.schedule.channel(CH5, 5, listOf(stationBreak(0, 12), program(12, 600, 15)))
            r.schedule.channel(CH7, 7, listOf(program(0, 600, 7)))
            r.schedule.channel(CH9, 9, listOf(program(0, 600, 9)))
            r.lastChannel.save(r.account, CH7)
            val keys = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = keys.receiveAsFlow())
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue("the programme after five's break", r.preResolves().any { it.item == itemOf(15) })
            assertTrue("and its standby", r.tune.calls.any { it is Call.Warm && it.item == itemOf(15) })

            // Flip down onto five during its break: the card, and the handle is kept for the cut at minute 12.
            keys.trySend(LiveCommand.Flip(FlipDirection.Down))
            keys.trySend(LiveCommand.FlipReleased)
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertIs<LiveState.Ident>(r.state)
            r.atMin(2) // 12 on the clock
            assertTrue(r.tune.calls.any { it is Call.TuneHandle && it.item == itemOf(15) })
            assertEquals(1, r.preResolves().count { it.item == itemOf(15) })
            assertTrue(r.tune.calls.none { it is Call.Discard && it.item == itemOf(15) })
            assertTrue(r.tunes.none { it.item == itemOf(15) })
        }
}
