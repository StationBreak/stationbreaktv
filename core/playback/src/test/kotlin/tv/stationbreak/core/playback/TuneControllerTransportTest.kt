// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.playback.PlaybackTestData.insigniaLike
import tv.stationbreak.core.playback.PlaybackTestData.source
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The play's transport, and a watcher that knows a pause or a seek is not a stall. */
@OptIn(ExperimentalCoroutinesApi::class)
class TuneControllerTransportTest {
    private val server = FakeMediaServer()
    private val player = FakeVideoPlayer()
    private val item = ItemId("item1")
    private val scheduler = TestCoroutineScheduler()
    private val watchScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val controller =
        TuneController(PlaybackResolver(server) { insigniaLike() }, server, player, watchScope = watchScope) { 1_000L }

    private fun runTest(body: suspend TestScope.() -> Unit) =
        kotlinx.coroutines.test.runTest(StandardTestDispatcher(scheduler), testBody = body)

    private fun answer() {
        server.playbackInfoResult =
            ServerResult.Success(PlaybackInfo(item, PlaySessionId("ps1"), listOf(source()), RecordingUrls()))
    }

    private val session = PlaybackTestData.session

    private suspend fun played() = controller.tune(session, item, Duration.ZERO, null) as TuneResult.Played

    private val firstFrame = Step { PlayerEvent.FirstFrame(1_250, it) }
    private val playingStep = Step { PlayerEvent.Playing(1_300, Duration.ZERO, it) }

    private fun playing() = listOf(firstFrame, playingStep)

    private fun at(
        ms: Long,
        event: (Long) -> PlayerEvent,
    ) = Step(afterMs = ms, event = event)

    /** What a play ended with and noted, collected as it happens. */
    private class Seen(
        val ends: MutableList<PlaybackEnd>,
        val notes: MutableList<PlayNote>,
    )

    /** Plays [steps] after the start, and collects the play's end and notes from then on. */
    private suspend fun TestScope.start(vararg steps: Step): Pair<TuneResult.Played, Seen> {
        answer()
        player.script = listOf(playing() + steps)
        player.scope = this
        val play = played()
        val seen = Seen(mutableListOf(), mutableListOf())
        launch { play.end.toList(seen.ends) }
        launch { play.notes.toList(seen.notes) }
        return play to seen
    }

    /** Lets [ms] of virtual time pass from the play. */
    private fun TestScope.after(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    private fun endWatchers() = watchScope.coroutineContext[Job]!!.cancelChildren()

    private fun failedWith(code: String) = { end: PlaybackEnd -> (end as PlaybackEnd.Failed).errorCode == code }

    @Test
    fun playedCarriesAControlForItsGeneration() =
        runTest {
            answer()
            player.script = listOf(playing())
            val play = played()
            assertEquals(1L, play.control.generation)
            play.control.pause()
            assertEquals("pause:1", player.calls.last())
        }

    @Test
    fun anOldControlActsOnItsOwnGenerationOnly() =
        runTest {
            answer()
            player.script = listOf(playing(), playing())
            val first = played()
            played()
            first.control.seekTo(5.seconds)
            assertEquals("seek:1:5000", player.calls.last())
        }

    @Test
    fun aPauseNeverTimesOutAsAStall() =
        runTest {
            val (_, seen) =
                start(
                    at(500) { PlayerEvent.Stalled(it, 1_800, positionMs = 400, bufferedMs = 0) },
                    at(1_000) { PlayerEvent.Paused(it, 2_300, 400) },
                )
            after(30.minutes.inWholeMilliseconds)
            assertTrue(seen.ends.isEmpty())
            assertEquals(listOf(PlayNote.Stall(400, 0, 500), PlayNote.Resumed(500)), seen.notes)
            endWatchers()
        }

    @Test
    fun aSlowFirstSegmentAfterAPauseIsNotAFailureOrANote() =
        runTest {
            val (_, seen) =
                start(
                    at(1_000) { PlayerEvent.Paused(it, 2_300, 400) },
                    at(600_000) { PlayerEvent.Resumed(it, 601_300) },
                    at(600_100) { PlayerEvent.Stalled(it, 601_400, positionMs = 400, bufferedMs = 0) },
                    at(615_100) { PlayerEvent.Playing(616_400, 400.milliseconds, it) },
                )
            after(30.minutes.inWholeMilliseconds)
            assertTrue(seen.ends.isEmpty())
            assertTrue(seen.notes.none { it is PlayNote.Stall })
            endWatchers()
        }

    @Test
    fun aResumeThatNeverPlaysEndsWithStallTimeout() =
        runTest {
            val (_, seen) =
                start(
                    at(1_000) { PlayerEvent.Paused(it, 2_300, 400) },
                    at(2_000) { PlayerEvent.Resumed(it, 3_300) },
                    at(2_100) { PlayerEvent.Stalled(it, 3_400, positionMs = 400, bufferedMs = 0) },
                )
            after(2_100 + PLAYING_TIMEOUT_MS - 1)
            assertTrue(seen.ends.isEmpty())
            after(1)
            assertEquals(1, seen.ends.size)
            assertTrue(failedWith("StallTimeout")(seen.ends.single()))
            assertTrue(seen.notes.none { it is PlayNote.Stall })
        }

    @Test
    fun aSeekThatNeverCompletesEndsWithSeekTimeout() =
        runTest {
            val (_, seen) = start(at(1_000) { PlayerEvent.Seeking(it, 2_300, 400, 60_000) })
            after(1_000 + PLAYING_TIMEOUT_MS - 1)
            assertTrue(seen.ends.isEmpty())
            after(1)
            assertEquals(1, seen.ends.size)
            assertTrue(failedWith("SeekTimeout")(seen.ends.single()))
            assertTrue(seen.notes.none { it is PlayNote.Stall })
        }

    @Test
    fun aSeekThatCompletesWhilePausedDoesNotFail() =
        runTest {
            val (_, seen) =
                start(
                    at(500) { PlayerEvent.Paused(it, 1_800, 400) },
                    at(600) { PlayerEvent.Seeking(it, 1_900, 400, 60_000) },
                    at(1_000) { PlayerEvent.SeekDone(it, 2_300, 60_000) },
                )
            after(30.minutes.inWholeMilliseconds)
            assertTrue(seen.ends.isEmpty())
            endWatchers()
        }

    @Test
    fun aSeekIsNotAStallNote() =
        runTest {
            val (_, seen) =
                start(
                    at(500) { PlayerEvent.Seeking(it, 1_800, 400, 60_000) },
                    at(600) { PlayerEvent.Stalled(it, 1_900, positionMs = 400, bufferedMs = 0) },
                    at(700) { PlayerEvent.SeekDone(it, 2_000, 60_000) },
                    at(800) { PlayerEvent.Playing(2_100, 60.seconds, it) },
                )
            after(30.minutes.inWholeMilliseconds)
            assertTrue(seen.notes.none { it is PlayNote.Stall })
            assertTrue(seen.ends.isEmpty())
            endWatchers()
        }

    @Test
    fun aPlayingAfterASeekWithNoSeekDoneStillTimesALaterStall() =
        runTest {
            val (_, seen) =
                start(
                    at(500) { PlayerEvent.Seeking(it, 1_800, 400, 60_000) },
                    at(600) { PlayerEvent.Playing(1_900, 60.seconds, it) },
                    at(1_000) { PlayerEvent.Stalled(it, 2_300, positionMs = 60_400, bufferedMs = 0) },
                )
            after(1_000 + PLAYING_TIMEOUT_MS - 1)
            assertTrue(seen.ends.isEmpty())
            after(1)
            assertTrue(failedWith("StallTimeout")(seen.ends.single()))
        }

    @Test
    fun aStallThatOutlivesItsSeekStillTimesOut() =
        runTest {
            val (_, seen) =
                start(
                    at(500) { PlayerEvent.Seeking(it, 1_800, 400, 60_000) },
                    at(600) { PlayerEvent.Stalled(it, 1_900, positionMs = 400, bufferedMs = 0) },
                    at(700) { PlayerEvent.SeekDone(it, 2_000, 60_000) },
                )
            after(700 + PLAYING_TIMEOUT_MS - 1)
            assertTrue(seen.ends.isEmpty())
            after(1)
            assertTrue(failedWith("StallTimeout")(seen.ends.single()))
            assertTrue(seen.notes.none { it is PlayNote.Stall })
        }

    @Test
    fun aSeekCancelsARunningStallTimerAndTheStallRestartsItAtSeekDone() =
        runTest {
            val (_, seen) =
                start(
                    at(100) { PlayerEvent.Stalled(it, 1_400, positionMs = 400, bufferedMs = 0) },
                    at(500) { PlayerEvent.Seeking(it, 1_800, 400, 60_000) },
                    at(600) { PlayerEvent.SeekDone(it, 1_900, 60_000) },
                )
            after(100 + PLAYING_TIMEOUT_MS + 1)
            assertTrue("the old timer is gone", seen.ends.isEmpty())
            after(600 - 101)
            assertTrue(failedWith("StallTimeout")(seen.ends.single()))
        }

    @Test
    fun aPlayingAfterASeekIsNotAFirstPlaying() =
        runTest {
            val (play, seen) =
                start(
                    at(500) { PlayerEvent.Seeking(it, 1_800, 400, 60_000) },
                    at(600) { PlayerEvent.SeekDone(it, 1_900, 60_000) },
                    at(700) { PlayerEvent.Playing(9_300, 60.seconds, it) },
                    at(9_000) { PlayerEvent.Failed("ERROR_CODE_IO_UNSPECIFIED", null, it, 10_300) },
                )
            assertEquals(300L, play.playingMs)
            assertEquals(250L, play.firstFrameMs)
            after(9_000)
            val end = seen.ends.single() as PlaybackEnd.Failed
            assertEquals("the failure is timed from the first playing", 9_000L, end.afterMs)
        }

    private companion object {
        const val PLAYING_TIMEOUT_MS = 20_000L
    }
}
