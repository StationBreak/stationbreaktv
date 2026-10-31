// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.standIn
import tv.stationbreak.core.playback.SeekCheck
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The kept place (spec 20, ADR-0021 5, amendments to ADR-0017 2 and 6): only a paused place is kept, and a run given
 * one tunes it and pauses on the first frame. It never plays on by itself.
 */
class KeptPlaceTest {
    private val one = program(0, 60, 1)
    private val two = program(60, 120, 2)
    private val three = program(120, 180, 3)

    private class Keys(
        val r: Rig,
        val keys: Channel<LiveCommand>,
        val job: Job,
    ) {
        fun press(command: LiveCommand) {
            keys.trySend(command)
            r.scope.testScheduler.runCurrent()
        }

        fun pressAt(
            ms: Long,
            command: LiveCommand,
        ) {
            r.at(ms)
            press(command)
        }

        val transport: TransportView? get() = r.live.transport.value

        /** Ends the run, then checks every handle made was tuned or discarded, exactly once. */
        fun finish() {
            job.cancel()
            r.scope.testScheduler.runCurrent()
            r.assertEveryPreResolveEndsOnce()
        }
    }

    /** CH7 with [blocks], the clock at [startMin], run with [resume]; [setup] edits the rig before the start. */
    private suspend fun TestScope.keys(
        startMin: Long,
        resume: Kept? = null,
        blocks: List<AiringBlock> = listOf(one, two, three),
        setup: Rig.() -> Unit = {},
    ): Keys {
        val r = Rig(this, startMin = startMin)
        r.schedule.channel(CH7, 7, blocks)
        r.setup()
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        val job = r.start(commands = keys.receiveAsFlow(), resume = resume)
        return Keys(r, keys, job)
    }

    private fun kept(
        block: AiringBlock,
        mediaMs: Long,
    ) = Kept(Place(CH7, block, mediaMs), null)

    private fun assertNear(
        expectedMs: Long,
        actualMs: Long,
        toleranceMs: Long = 1_000,
    ) = assertTrue("expected $expectedMs, was $actualMs", abs(expectedMs - actualMs) <= toleranceMs)

    @Test
    fun theActionsBannerOffersLiveOnlyWhilePausedOrBehind() =
        runTest {
            val k = keys(startMin = 30, resume = kept(one, 20 * MIN))
            k.r.at(1_000)
            k.press(LiveCommand.ShowBanner)
            val paused = checkNotNull((k.r.state as LiveState.Watching).banner)
            assertTrue(paused.actions)
            assertTrue("paused is behind live", paused.behind)
            k.finish()

            val live = keys(startMin = 30)
            live.r.at(5_000)
            live.press(LiveCommand.ShowBanner)
            assertFalse(checkNotNull((live.r.state as LiveState.Watching).banner).behind)
            live.finish()
        }

    @Test
    fun aRunGivenAPausedPlaceTunesItAndPausesAtOnce() =
        runTest {
            val place = 20 * MIN
            val k = keys(startMin = 30, resume = kept(one, place))

            assertEquals(listOf(Call.Tune(itemOf(1), 20.minutes, 0)), k.r.tunes)
            val control = checkNotNull(k.r.tune.controlOf(itemOf(1)))
            // Paused as soon as the tune answered: the snow is still up (minSnow has not run out).
            assertEquals(listOf("pause"), control.calls)
            assertTrue(k.r.state is LiveState.Tuning)

            k.r.at(1_000)
            assertTrue(k.r.state is LiveState.Watching)
            assertTrue(checkNotNull(k.transport).paused)
            assertTrue(
                "landed ${control.positionMs()}, asked for $place",
                abs(control.positionMs() - place) < SeekCheck.TOLERANCE.inWholeMilliseconds,
            )

            // Nothing plays on by itself: still paused, no resume, no other tune, ten minutes on.
            k.r.atMin(10)
            assertEquals(listOf("pause"), control.calls)
            assertTrue(checkNotNull(k.transport).paused)
            assertEquals(1, k.r.tunes.size)
            k.finish()
        }

    @Test
    fun aPlaceAheadOfLiveReturnsPausedAtLive() =
        runTest {
            // Paused at 15:00 in a sitting that ran ahead; the clock's live position is 10:00.
            val k = keys(startMin = 10, resume = kept(one, 15 * MIN))
            assertEquals(listOf(Call.Tune(itemOf(1), 10.minutes, 0)), k.r.tunes)
            k.r.at(1_000)
            assertEquals(listOf("pause"), checkNotNull(k.r.tune.controlOf(itemOf(1))).calls)
            assertTrue(checkNotNull(k.transport).paused)
            k.finish()
        }

    @Test
    fun aReturnAfterTheBlockEndedResumesThenCatchesUp() =
        runTest {
            // Item 1's block ended at minute 60; the clock is at 70. The viewer is behind, at 50:00 of item 1.
            val k = keys(startMin = 70, resume = kept(one, 50 * MIN))
            assertEquals(listOf(Call.Tune(itemOf(1), 50.minutes, 0)), k.r.tunes)
            k.pressAt(1_000, LiveCommand.PlayPause)
            assertEquals(listOf("pause", "resume"), checkNotNull(k.r.tune.controlOf(itemOf(1))).calls)
            assertFalse(checkNotNull(k.transport).paused)

            // Ten minutes of media are left to the cut: until then, item 1 plays on.
            k.r.at(1_000 + 10 * MIN - 1_000)
            assertEquals(
                1,
                k.r.tune.calls
                    .count { it is Call.Tune || it is Call.TuneHandle },
            )
            k.r.at(1_000 + 10 * MIN + 1_000)
            // What is on now (item 2, from minute 60) at its position then, from the handle resolved ahead.
            val next =
                assertIs<Call.TuneHandle>(
                    k.r.tune.calls
                        .last { it is Call.Tune || it is Call.TuneHandle },
                )
            assertEquals(itemOf(2), next.item)
            assertNear(20 * MIN + 1_000, next.start.inWholeMilliseconds)
            assertNull(k.transport)
            k.finish()
        }

    @Test
    fun aReturnStartsNoBoundaryLookAheadButLooksAtTheNeighbours() =
        runTest {
            val k =
                keys(startMin = 50, resume = kept(one, 40 * MIN)) {
                    schedule.channel(CH5, 5, listOf(program(0, 600, 5)))
                    schedule.channel(CH9, 9, listOf(program(0, 600, 9)))
                }
            k.r.at(1_000)
            val resolved = {
                k.r.tune.calls
                    .filterIsInstance<Call.PreResolve>()
                    .map { it.item }
            }
            assertEquals(setOf(itemOf(5), itemOf(9)), resolved().toSet())
            // Past the boundary look-ahead's lead before item 1's block ends at minute 60: item 2 is not resolved.
            k.r.at(9 * MIN + 30_000)
            assertFalse(itemOf(2) in resolved())
            assertTrue(checkNotNull(k.transport).paused)
            k.finish()
        }

    @Test
    fun aPlaceOnAChannelNoLongerOnAirJoinsLive() =
        runTest {
            val gone = Kept(Place(CH9, program(0, 30, 9), 20 * MIN), null)
            val k = keys(startMin = 40, resume = gone)
            k.r.at(1_000)
            assertEquals(listOf(Call.Tune(itemOf(1), 40.minutes, 0)), k.r.tunes)
            assertEquals(emptyList<String>(), checkNotNull(k.r.tune.controlOf(itemOf(1))).calls)
            assertNull(k.transport)
            k.finish()
        }

    @Test
    fun keptIsNullWhileLive() =
        runTest {
            val k = keys(startMin = 10)
            k.r.at(1_000)
            assertTrue(k.r.state is LiveState.Watching)
            assertNull(k.r.live.kept())
            k.finish()
        }

    @Test
    fun keptIsNullWhilePlayingBehind() =
        runTest {
            val k = keys(startMin = 10)
            k.pressAt(MIN, LiveCommand.PlayPause)
            assertNotNull(k.r.live.kept())
            k.pressAt(2 * MIN, LiveCommand.PlayPause)
            assertFalse(checkNotNull(k.transport).paused)
            assertNull(k.r.live.kept())
            k.finish()
        }

    @Test
    fun keptIsNullOnAStandIn() =
        runTest {
            val k =
                keys(startMin = 10, blocks = listOf(one, two)) {
                    schedule.standInScript = { _, _ -> listOf(standIn(0, 60, 7, 0)) }
                    tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
                }
            // The slate's 3 s, then stand-in 7.
            k.r.at(4_000)
            assertEquals(
                itemOf(7),
                k.r.tunes
                    .last()
                    .item,
            )
            k.pressAt(5_000, LiveCommand.PlayPause)
            assertTrue(checkNotNull(k.transport).paused)
            assertNull(k.r.live.kept())
            k.finish()
        }

    @Test
    fun keptReadsThePausedPosition() =
        runTest {
            val k = keys(startMin = 10)
            k.pressAt(MIN, LiveCommand.PlayPause)
            // Paused at 11:00 of item 1; the minutes after go by on a frozen picture.
            k.r.at(30 * MIN)
            assertEquals(Kept(Place(CH7, one, 11 * MIN), null), k.r.live.kept())
            k.finish()
        }

    @Test
    fun keptDuringTheReturnsTuneIsTheGivenPlace() =
        runTest {
            val given = kept(one, 20 * MIN)
            val k = keys(startMin = 30, resume = given) { tune.script(itemOf(1), FakeTune.playsAfter(5.seconds)) }
            // The tune has not answered: no behind play yet, but the place given is still the one kept.
            assertEquals(given.copy(untended = true), k.r.live.kept())
            k.r.at(6_000)
            assertEquals(given.copy(untended = true), k.r.live.kept())
            // Played: the untended pause is over, and nothing is kept while playing.
            k.press(LiveCommand.PlayPause)
            assertNull(k.r.live.kept())
            k.press(LiveCommand.PlayPause)
            assertFalse(checkNotNull(k.r.live.kept()).untended)
            k.finish()
        }

    @Test
    fun aReturnToABlockStillOnAirReadsTheScheduleOnlyForNow() =
        runTest {
            val k = keys(startMin = 30, resume = kept(one, 20 * MIN))
            assertEquals(listOf(Call.Tune(itemOf(1), 20.minutes, 0)), k.r.tunes)
            // What is on now already shows the kept block: no second read at the block's start.
            assertTrue(
                k.r.schedule.onAirCalls
                    .none { it.second == one.startMs },
            )
            k.finish()
        }

    @Test
    fun aReturnToAnEndedBlockChecksItIsStillScheduled() =
        runTest {
            val k = keys(startMin = 70, resume = kept(one, 50 * MIN))
            assertEquals(listOf(Call.Tune(itemOf(1), 50.minutes, 0)), k.r.tunes)
            assertEquals(
                1,
                k.r.schedule.onAirCalls
                    .count { it.second == one.startMs },
            )
            k.finish()
        }
}
