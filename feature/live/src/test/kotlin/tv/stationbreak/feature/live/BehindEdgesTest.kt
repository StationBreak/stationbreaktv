// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.standIn
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.playback.PlaybackEnd
import tv.stationbreak.feature.live.report.RecordingServer
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The edges of a behind play (spec 20, ADR-0021 4 and 6): a failed play retries once at the place, a stand-in can be
 * paused and stepped, and a flip, Menu or guide tune to another channel drops the behind state.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The test clock's currentTime, for the server's call times.
class BehindEdgesTest {
    /** One second in: the first picture is up (no key acts while the snow shows). */
    private val upMs = 1_000L

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

        /** A tap of a flip key: the press and its release at once. */
        fun flip(direction: FlipDirection) {
            keys.trySend(LiveCommand.Flip(direction))
            press(LiveCommand.FlipReleased)
        }

        /** Every play's control of [item], in tune order. */
        fun controls(item: ItemId) =
            r.tune.controls
                .filter { it.first == item }
                .map { it.second }

        val transport: TransportView? get() = r.live.transport.value

        /** Ends the run, then checks every handle made was tuned or discarded, exactly once. */
        fun finish() {
            job.cancel()
            r.scope.testScheduler.runCurrent()
            r.assertEveryPreResolveEndsOnce()
        }
    }

    /** CH7 with [blocks] (item 1 from minute 0 to 30, item 2 to 60 and item 3 to 90), joined at [startMin]. */
    private suspend fun TestScope.keys(
        startMin: Long = 10,
        server: MediaServer? = null,
        blocks: List<AiringBlock> = listOf(program(0, 30, 1), program(30, 60, 2), program(60, 90, 3)),
        setup: Rig.() -> Unit = {},
    ): Keys {
        val r = Rig(this, startMin = startMin, server = server)
        r.schedule.channel(CH7, 7, blocks)
        r.setup()
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        val job = r.start(commands = keys.receiveAsFlow())
        return Keys(r, keys, job)
    }

    /** Channels 5, 7 and 9, each with one long program (item = its number), the sitting on [startOn] at minute 10. */
    private suspend fun TestScope.channels(startOn: tv.stationbreak.core.model.ChannelId = CH7): Keys {
        val r = Rig(this, startMin = 10)
        r.schedule.channel(CH5, 5, listOf(program(0, 600, 5)))
        r.schedule.channel(CH7, 7, listOf(program(0, 600, 7)))
        r.schedule.channel(CH9, 9, listOf(program(0, 600, 9)))
        r.lastChannel.save(r.account, startOn)
        // These tests are about the behind state, not the neighbours' look-ahead.
        r.tune.preResolveResult = { false }
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        val job = r.start(commands = keys.receiveAsFlow())
        r.at(upMs)
        return Keys(r, keys, job)
    }

    private fun assertNear(
        expectedMs: Long,
        actualMs: Long,
        toleranceMs: Long = 1_000,
    ) = assertTrue("expected $expectedMs, was $actualMs", abs(expectedMs - actualMs) <= toleranceMs)

    /** The clock position on a CH5/7/9 program (each starts at minute 0) at test time [atMs]. */
    private fun clockPosition(atMs: Long) = 10 * MIN + atMs

    // A failed play while behind: one retry at the place (ADR-0021 4 and 6).

    @Test
    fun aFailureWhileBehindRetriesOnceAtThePlace() =
        runTest {
            val failed = PlaybackEnd.Failed("Boom", positionMs = 12 * MIN)
            val k = keys { tune.script(itemOf(1), tune.playsThenEnds(5.minutes, failed)) }
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(MIN + upMs, LiveCommand.PlayPause)
            k.r.at(5 * MIN + 100)
            assertTrue(k.r.reports.any { it is TuneReport.HeldFailed && it.end == failed })
            // The retry: item 1 at the place the player failed at, under the snow, reported as a retry.
            assertEquals(2, k.r.tunes.size)
            assertEquals(Call.Tune(itemOf(1), 12.minutes, 5 * MIN), k.r.tunes.last())
            assertTrue(
                k.r.seen
                    .dropWhile { it !is LiveState.Watching }
                    .any { it is LiveState.Tuning },
            )
            assertEquals(
                TuneKind.Retry,
                k.r.reports
                    .filterIsInstance<TuneReport.Tune>()
                    .last()
                    .kind,
            )
            // It plays and stays behind: three minutes, as the place was.
            k.r.at(10 * MIN)
            assertTrue(k.r.state is LiveState.Watching)
            val view = checkNotNull(k.transport)
            assertFalse(view.paused)
            assertNear(3 * MIN, view.behindMs(k.r.nowMs()))
            assertEquals(2, k.r.tunes.size)
            // The retried play takes the next press on its own control; the failed one is done with.
            k.press(LiveCommand.PlayPause)
            val (first, retried) = k.controls(itemOf(1))
            assertEquals(listOf("pause", "resume"), first.calls)
            assertEquals(listOf("pause"), retried.calls)
            assertTrue(checkNotNull(k.transport).paused)
            k.finish()
        }

    @Test
    fun aFailureWhilePausedRetriesAtThePlaceStillPaused() =
        runTest {
            val failed = PlaybackEnd.Failed("Boom", positionMs = 10 * MIN + upMs)
            val k = keys { tune.script(itemOf(1), tune.playsThenEnds(3.minutes, failed)) }
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.r.at(3 * MIN + 1_000)
            assertEquals(Call.Tune(itemOf(1), (10 * MIN + upMs).milliseconds, 3 * MIN), k.r.tunes.last())
            val retried = k.controls(itemOf(1)).last()
            assertEquals(listOf("pause"), retried.calls)
            assertTrue(checkNotNull(k.transport).paused)
            // Play carries on from the place.
            k.press(LiveCommand.PlayPause)
            assertEquals(listOf("pause", "resume"), retried.calls)
            assertNear(3 * MIN, checkNotNull(k.transport).behindMs(k.r.nowMs()))
            k.finish()
        }

    @Test
    fun aRetryThatAlsoFailsCatchesUpWithNoStandIn() =
        runTest {
            val failed = PlaybackEnd.Failed("Boom", positionMs = 11 * MIN)
            val k =
                keys {
                    schedule.standInScript = { _, _ -> listOf(standIn(0, 30, 8, 0)) }
                    tune.script(itemOf(1), tune.playsThenEnds(23.minutes, failed), FakeTune.dead())
                }
            // Paused 21 minutes: item 1's slot (to minute 30 of the clock) ends meanwhile.
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(22 * MIN, LiveCommand.PlayPause)
            k.r.at(23 * MIN + 1_000)
            // The retry is dead: that is the item's end. What is on now (item 2, three minutes in) plays.
            assertEquals(
                listOf(Call.Tune(itemOf(1), 11.minutes, 23 * MIN), Call.Tune(itemOf(2), 3.minutes, 23 * MIN)),
                k.r.tunes.drop(1),
            )
            assertEquals(0, k.r.schedule.standInCalls.size)
            assertTrue(k.r.seen.none { it is LiveState.TechnicalDifficulties })
            assertNull(k.transport)
            k.finish()
        }

    @Test
    fun aSeekTimeoutIsAMidPlayFailure() =
        runTest {
            // The player's position for a timed-out seek is where it was before the seek.
            val failed = PlaybackEnd.Failed("SeekTimeout", positionMs = 11 * MIN)
            val k = keys { tune.script(itemOf(1), tune.playsThenEnds(140.seconds, failed)) }
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(MIN + upMs, LiveCommand.PlayPause)
            k.r.at(2 * MIN)
            k.controls(itemOf(1)).single().dropsSeekDone = true
            // Back from media 11:00: the seek to 10:50 goes out and never lands.
            k.press(LiveCommand.Seek(SeekDirection.Back))
            k.press(LiveCommand.SeekReleased)
            k.r.at(140_000 + 100)
            // One retry, at the place the viewer stepped to; no slate.
            assertEquals(listOf(Call.Tune(itemOf(1), 650.seconds, 140_000)), k.r.tunes.drop(1))
            // (The screen starts on the ident before the first tune; none since the picture came up.)
            val since = k.r.seen.dropWhile { it !is LiveState.Watching }
            assertTrue(since.none { it is LiveState.TechnicalDifficulties || it is LiveState.Ident })
            k.r.at(141_000)
            assertTrue(k.r.state is LiveState.Watching)
            k.finish()
        }

    @Test
    fun aSeekTimeoutRetryCountsTheRestOfTheItemOnceOnly() =
        runTest {
            // Item 1 runs 10 minutes and is marked played at 9 minutes watched (the union of media played).
            val server = RecordingServer({ currentTime })
            val facts = ReportFacts(MediaSourceId("src"), PlaySessionId("ps"), PlayMethod.DirectPlay, 10.minutes)
            val failed = PlaybackEnd.Failed("SeekTimeout", positionMs = 60_000)
            val k =
                keys(startMin = 0, server = server, blocks = listOf(program(0, 10, 1), program(10, 60, 2))) {
                    tune.reportFacts = facts
                    tune.script(itemOf(1), tune.playsThenEnds(70.seconds, failed))
                }
            k.r.at(60_000)
            k.controls(itemOf(1)).single().dropsSeekDone = true
            // Back three steps from media 60 s: the seek to 30 s never lands, and the play times out at 70 s.
            repeat(3) { k.press(LiveCommand.Seek(SeekDirection.Back)) }
            k.press(LiveCommand.SeekReleased)
            k.r.at(71_000)
            assertEquals(Call.Tune(itemOf(1), 30.seconds, 70_000), k.r.tunes.last())
            k.r.at(700_000)
            val played = server.calls.filter { it.kind == "played" && it.item == itemOf(1).value }.map { it.atMs }
            // Watched [0, 60 s) and, from the retry at 70.5 s, [30 s, ...): 9 minutes is reached at media 540 s, wall
            // 580.5 s. The rewatched 30 s counts once; counting it twice would reach it 30 s sooner.
            assertEquals(1, played.size)
            assertTrue("played at ${played.single()}", played.single() >= 580_500)
            k.finish()
        }

    // Stand-ins can be paused and stepped; catch-up is at the next piece or block.

    @Test
    fun aStandInCanBePausedAndSought() =
        runTest {
            val k =
                keys(blocks = listOf(program(0, 60, 1), program(60, 120, 2))) {
                    schedule.standInScript = { _, _ -> listOf(standIn(0, 25, 7, 0), standIn(25, 60, 8, 0)) }
                    tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
                }
            // The slate's 3 s, then stand-in 7 at 10:03.
            k.r.at(4_000)
            assertEquals(Call.Tune(itemOf(7), 10.minutes + 3.seconds, 3_000), k.r.tunes.last())
            k.pressAt(5_000, LiveCommand.PlayPause)
            val seven = k.controls(itemOf(7)).single()
            assertEquals(listOf("pause"), seven.calls)
            assertTrue(checkNotNull(k.transport).paused)
            // Back 10 s from 10:05, still paused; then Play at 66 s: 71 s behind.
            k.pressAt(65_000, LiveCommand.Seek(SeekDirection.Back))
            k.press(LiveCommand.SeekReleased)
            k.r.at(65_500)
            assertEquals(listOf("pause", "seek:595000"), seven.calls)
            assertTrue(checkNotNull(k.transport).paused)
            k.pressAt(66_000, LiveCommand.PlayPause)
            // Stand-in 7 ends at its media 25:00, 15:05 later: the piece on now (8, 1:11 in) follows, not a failure.
            k.r.at(971_000 - 1_000)
            assertEquals(listOf(itemOf(1), itemOf(1), itemOf(7)), k.r.tunes.map { it.item })
            k.r.at(971_000 + 1_000)
            val eight = k.r.tunes.last()
            assertEquals(itemOf(8), eight.item)
            assertNear(71_000, eight.start.inWholeMilliseconds)
            assertEquals(1, k.r.schedule.standInCalls.size)
            assertNull(k.transport)
            // The dead block ends at minute 60 of the clock: the next block is a prepared cut, as on the live path.
            k.r.at(50 * MIN + 1_000)
            val cut =
                k.r.tune.calls
                    .last { it is Call.TuneHandle } as Call.TuneHandle
            assertEquals(itemOf(2), cut.item)
            k.finish()
        }

    @Test
    fun aStandInThatFailsWhileBehindIsRetriedAtThePlace() =
        runTest {
            val failed = PlaybackEnd.Failed("Boom", positionMs = 11 * MIN)
            val k =
                keys(blocks = listOf(program(0, 60, 1), program(60, 120, 2))) {
                    schedule.standInScript = { _, _ -> listOf(standIn(0, 60, 7, 0)) }
                    tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
                    tune.script(itemOf(7), tune.playsThenEnds(2.minutes, failed))
                }
            k.pressAt(5_000, LiveCommand.PlayPause)
            k.pressAt(65_000, LiveCommand.PlayPause)
            k.r.at(123_000 + 1_000)
            // Stand-in 7 failed at 2:03: tuned again once at its place, still behind, with no new pick.
            assertEquals(Call.Tune(itemOf(7), 11.minutes, 123_000), k.r.tunes.last())
            assertEquals(1, k.r.schedule.standInCalls.size)
            assertEquals(
                TuneKind.Retry,
                k.r.reports
                    .filterIsInstance<TuneReport.Tune>()
                    .last()
                    .kind,
            )
            assertFalse(checkNotNull(k.transport).paused)
            k.finish()
        }

    // A flip, Menu or guide tune to another channel ends the sitting, and the behind state with it.

    @Test
    fun aFlipDropsTheBehindStateAndComingBackJoinsLive() =
        runTest {
            val k = channels()
            k.press(LiveCommand.PlayPause)
            assertTrue(checkNotNull(k.transport).paused)
            k.r.at(2_000)
            k.flip(FlipDirection.Up)
            k.r.at(60_000)
            assertEquals(
                itemOf(9),
                k.r.tunes
                    .last()
                    .item,
            )
            k.flip(FlipDirection.Down)
            k.r.at(65_000)
            val back = k.r.tunes.last()
            assertEquals(itemOf(7), back.item)
            assertEquals(clockPosition(back.at), back.start.inWholeMilliseconds)
            assertNull(k.transport)
            assertEquals(emptyList<String>(), k.controls(itemOf(7)).last().calls)
            k.finish()
        }

    @Test
    fun menuDropsTheBehindState() =
        runTest {
            val k = channels(startOn = CH5)
            k.flip(FlipDirection.Up)
            k.r.at(5_000)
            assertEquals(
                itemOf(7),
                k.r.tunes
                    .last()
                    .item,
            )
            k.press(LiveCommand.PlayPause)
            assertTrue(checkNotNull(k.transport).paused)
            k.r.at(30_000)
            k.press(LiveCommand.Previous)
            k.r.at(35_000)
            assertEquals(
                itemOf(5),
                k.r.tunes
                    .last()
                    .item,
            )
            k.press(LiveCommand.Previous)
            k.r.at(40_000)
            val back = k.r.tunes.last()
            assertEquals(itemOf(7), back.item)
            assertEquals(clockPosition(back.at), back.start.inWholeMilliseconds)
            assertEquals(emptyList<String>(), k.controls(itemOf(7)).last().calls)
            assertNull(k.transport)
            k.finish()
        }

    @Test
    fun aGuideTuneToThePlayingChannelKeepsTheBehindState() =
        runTest {
            val k = channels()
            k.press(LiveCommand.PlayPause)
            val tunes = k.r.tunes.size
            k.press(LiveCommand.Tune(CH7))
            k.r.at(10_000)
            assertEquals(tunes, k.r.tunes.size)
            assertTrue(checkNotNull(k.transport).paused)
            assertEquals(listOf("pause"), k.controls(itemOf(7)).single().calls)
            k.finish()
        }

    @Test
    fun aGuideTuneToAnotherChannelDropsIt() =
        runTest {
            val k = channels()
            k.press(LiveCommand.PlayPause)
            k.r.at(20_000)
            k.press(LiveCommand.Tune(CH5))
            k.r.at(25_000)
            val tuned = k.r.tunes.last()
            assertEquals(itemOf(5), tuned.item)
            assertEquals(clockPosition(tuned.at), tuned.start.inWholeMilliseconds)
            assertFalse("resume" in k.controls(itemOf(7)).single().calls)
            assertNull(k.transport)
            k.finish()
        }

    @Test
    fun aStepPressedJustBeforeAFlipNeverSeeksTheNewChannel() =
        runTest {
            val k = channels()
            k.r.at(30_000)
            k.press(LiveCommand.Seek(SeekDirection.Back))
            k.r.at(30_050)
            k.flip(FlipDirection.Up)
            k.press(LiveCommand.SeekReleased)
            k.r.at(40_000)
            assertEquals(
                itemOf(9),
                k.r.tunes
                    .last()
                    .item,
            )
            val nine = k.controls(itemOf(9)).single()
            assertTrue(nine.calls.toString(), nine.calls.none { it.startsWith("seek") })
            assertNull(k.transport)
            k.finish()
        }
}
