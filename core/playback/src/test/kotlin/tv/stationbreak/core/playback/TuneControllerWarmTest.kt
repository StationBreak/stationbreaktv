// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.playback.PlaybackTestData.insigniaLike
import tv.stationbreak.core.playback.PlaybackTestData.source
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Spec 24: the controller warms a held handle, stamps its key on the play, and reports what became of it. */
class TuneControllerWarmTest {
    private val server = FakeMediaServer()
    private val urls = RecordingUrls()
    private val resolver = PlaybackResolver(server) { insigniaLike() }
    private val session = PlaybackTestData.session
    private val transcode get() = source(directPlay = false, directStream = false)

    private class Switch(
        var on: Boolean,
    ) : WarmSwitches {
        override fun warmAllowed(): Boolean = on

        override fun audioAllowed(): Boolean = true
    }

    private inner class Rig(
        config: WarmConfig = WarmConfig(warmNextDecoder = true),
        switches: WarmSwitches = WarmSwitches.None,
        val player: FakeVideoPlayer = FakeVideoPlayer(),
    ) {
        val controller =
            TuneController(resolver, server, player, warm = config, warmSwitches = switches) { 1_000L }

        suspend fun prepare(id: String): Prepared {
            val start = Duration.ZERO
            return checkNotNull(controller.preResolve(session, ItemId(id), start))
        }

        suspend fun tune(prepared: Prepared) = controller.tune(session, prepared, null) as TuneResult.Played
    }

    private fun answer(media: MediaSource) {
        server.playbackInfoResult =
            ServerResult.Success(PlaybackInfo(ItemId("item1"), PlaySessionId("ps1"), listOf(media), urls))
    }

    private fun playing(
        outcome: WarmOutcome? = null,
        atMs: Long = 1_400,
    ) = listOfNotNull(outcome?.let { o -> Step { PlayerEvent.WarmDecided(it, o) } }) +
        listOf(
            Step { PlayerEvent.FirstFrame(atMs - 50, it) },
            Step { PlayerEvent.Playing(atMs, Duration.ZERO, it) },
        )

    private fun swapped(
        ageMs: Long = 800,
        readyMs: Long = 300,
    ) = WarmOutcome(true, WarmReason.None, ageMs, readyMs, 5)

    @Test
    fun warmNextRequestsDirectPlayOnly() =
        runTest {
            val rig = Rig()
            answer(transcode)
            assertFalse(rig.controller.warmNext(rig.prepare("a")))
            assertTrue(rig.player.warmed.isEmpty())
            answer(source())
            assertTrue(rig.controller.warmNext(rig.prepare("b")))
            assertEquals(1, rig.player.warmed.size)
        }

    @Test
    fun warmNextSkipsWhenSwitchOff() =
        runTest {
            answer(source())
            val tunable = Rig(WarmConfig(warmNextDecoder = false))
            assertFalse(tunable.controller.warmNext(tunable.prepare("a")))
            val switch = Switch(false)
            val switched = Rig(switches = switch)
            assertFalse(switched.controller.warmNext(switched.prepare("a")))
            assertTrue(tunable.player.warmed.isEmpty() && switched.player.warmed.isEmpty())
            // A switch read when it is asked, not once: turning it back on warms.
            switch.on = true
            assertTrue(switched.controller.warmNext(switched.prepare("b")))
        }

    @Test
    fun warmNextWarmsTheHandlesSourceAndAnswersTrueAgainForTheSameHandle() =
        runTest {
            answer(source())
            val rig = Rig()
            val a = rig.prepare("a")
            assertTrue(rig.controller.warmNext(a))
            assertTrue(rig.controller.warmNext(a))
            // The player's own answer for the same source is the same handle: nothing is dropped.
            assertTrue(rig.player.calls.none { it.startsWith("dropWarm") })
            assertNull(
                rig.player.warmed
                    .first()
                    .warmKey,
            )
        }

    @Test
    fun warmNextOfAHandleAlreadyPlayedOrDiscardedDoesNothing() =
        runTest {
            answer(source())
            val rig = Rig()
            val a = rig.prepare("a")
            rig.controller.discard(session, a)
            assertFalse(rig.controller.warmNext(a))
            assertTrue(rig.player.warmed.isEmpty())
        }

    @Test
    fun aPlayerThatCannotWarmGivesNoStandby() =
        runTest {
            answer(source())
            val rig = Rig(player = FakeVideoPlayer().also { it.warmAnswer = VideoPlayer.NO_WARM_HANDLE })
            val a = rig.prepare("a")
            assertFalse(rig.controller.warmNext(a))
            rig.controller.discard(session, a)
            assertTrue(rig.player.calls.none { it.startsWith("dropWarm") })
        }

    @Test
    fun tuneWithWarmKeyHandsKeyToPlayer() =
        runTest {
            answer(source())
            val rig = Rig()
            rig.player.script = listOf(playing(swapped()))
            val a = rig.prepare("a")
            rig.controller.warmNext(a)
            rig.tune(a)
            assertEquals(
                "w1",
                rig.player.played
                    .single()
                    .warmKey,
            )
            assertEquals(
                rig.player.warmed
                    .single()
                    .url,
                rig.player.played
                    .single()
                    .url,
            )
        }

    @Test
    fun warmUseReportsSwap() =
        runTest {
            answer(source())
            val rig = Rig()
            rig.player.script = listOf(playing(swapped(ageMs = 900, readyMs = 350)))
            val a = rig.prepare("a")
            rig.controller.warmNext(a)
            assertEquals(WarmUse(true, WarmReason.None, 900, 350), rig.tune(a).warm)
        }

    @Test
    fun warmUseCarriesThePlayersReasons() =
        runTest {
            for (reason in listOf(WarmReason.Mismatch, WarmReason.NotReady, WarmReason.Error, WarmReason.Timeout)) {
                answer(source())
                val rig = Rig()
                rig.player.script = listOf(playing(WarmOutcome(false, reason, 60, 25, 0)))
                val a = rig.prepare("a")
                rig.controller.warmNext(a)
                assertEquals(WarmUse(false, reason, 60, 25), rig.tune(a).warm)
            }
        }

    @Test
    fun aKeyedPlayThatSaidNothingIsAColdMismatch() =
        runTest {
            answer(source())
            val rig = Rig()
            rig.player.script = listOf(playing())
            val a = rig.prepare("a")
            rig.controller.warmNext(a)
            assertEquals(WarmUse(false, WarmReason.Mismatch, 0, 0), rig.tune(a).warm)
        }

    @Test
    fun aTranscodeHandleReportsTranscode() =
        runTest {
            answer(transcode)
            val rig = Rig()
            rig.player.script = listOf(playing())
            val a = rig.prepare("a")
            assertFalse(rig.controller.warmNext(a))
            assertEquals(WarmUse(false, WarmReason.Transcode, 0, 0), rig.tune(a).warm)
            assertNull(
                rig.player.played
                    .single()
                    .warmKey,
            )
        }

    @Test
    fun aSwitchedOffTuneReportsDisabled() =
        runTest {
            answer(source())
            val rig = Rig(WarmConfig(warmNextDecoder = false))
            rig.player.script = listOf(playing())
            val a = rig.prepare("a")
            assertEquals(WarmUse(false, WarmReason.Disabled, 0, 0), rig.tune(a).warm)
        }

    @Test
    fun aDirectHandleThatWasNeverWarmedReportsWrongDirection() =
        runTest {
            answer(source())
            val rig = Rig()
            rig.player.script = listOf(playing())
            assertEquals(WarmUse(false, WarmReason.WrongDirection, 0, 0), rig.tune(rig.prepare("a")).warm)
        }

    @Test
    fun aTuneWithNoHandleReportsNoHandle() =
        runTest {
            answer(source())
            val rig = Rig()
            rig.player.script = listOf(playing())
            val result = rig.controller.tune(session, ItemId("a"), Duration.ZERO, null) as TuneResult.Played
            assertEquals(WarmUse(false, WarmReason.NoHandle, 0, 0), result.warm)
            assertNull(
                rig.player.played
                    .single()
                    .warmKey,
            )
        }

    @Test
    fun aTuneWithNoHandleWhileAnotherIsHeldIsWrongDirection() =
        runTest {
            answer(source())
            val rig = Rig()
            rig.player.script = listOf(playing())
            rig.controller.warmNext(rig.prepare("a"))
            val result = rig.controller.tune(session, ItemId("b"), Duration.ZERO, null) as TuneResult.Played
            assertEquals(WarmReason.WrongDirection, result.warm?.reason)
        }

    @Test
    fun differentHandleIsCold() =
        runTest {
            answer(source())
            val rig = Rig()
            rig.player.script = listOf(playing())
            val a = rig.prepare("a")
            val b = rig.prepare("b")
            rig.controller.warmNext(a)
            val result = rig.tune(b)
            assertNull(
                rig.player.played
                    .single()
                    .warmKey,
            )
            assertEquals(WarmUse(false, WarmReason.WrongDirection, 0, 0), result.warm)
        }

    @Test
    fun warmedStandbyIsGoneOnceItsHandlePlays() =
        runTest {
            answer(source())
            val rig = Rig()
            rig.player.script = listOf(playing(swapped()))
            val a = rig.prepare("a")
            rig.controller.warmNext(a)
            rig.tune(a)
            rig.controller.discard(session, a)
            rig.controller.close(session)
            assertTrue(rig.player.calls.none { it.startsWith("dropWarm") })
        }

    @Test
    fun discardDropsHeldWarm() =
        runTest {
            answer(source())
            val rig = Rig()
            val a = rig.prepare("a")
            rig.controller.warmNext(a)
            rig.controller.discard(session, a)
            rig.controller.discard(session, a)
            assertEquals(listOf("warm", "dropWarm:w1"), rig.player.calls)
        }

    @Test
    fun closeDropsHeldWarm() =
        runTest {
            answer(source())
            val rig = Rig()
            rig.controller.warmNext(rig.prepare("a"))
            rig.controller.close(session)
            assertTrue("dropWarm:w1" in rig.player.calls)
        }

    @Test
    fun aHandleEndedByAnOverrideDropsItsWarm() =
        runTest {
            answer(source())
            val rig = Rig()
            rig.player.script = listOf(playing())
            val a = rig.prepare("a")
            rig.controller.warmNext(a)
            rig.controller.tune(session, a, TrackChoice(MediaSourceId("src1"), TrackOverride(1, SubtitleChoice.Off)))
            assertTrue("dropWarm:w1" in rig.player.calls)
            assertNull(
                rig.player.played
                    .single()
                    .warmKey,
            )
        }

    @Test
    fun warmingOneDropsTheOther() =
        runTest {
            answer(source())
            val rig = Rig()
            rig.player.script = listOf(playing())
            val a = rig.prepare("a")
            val b = rig.prepare("b")
            rig.controller.warmNext(a)
            rig.controller.warmNext(b)
            assertEquals(listOf("warm", "dropWarm:w1", "warm"), rig.player.calls)
            // A no longer holds the standby: it is cold, and discarding it does not drop B's.
            assertEquals(WarmReason.WrongDirection, rig.tune(a).warm?.reason)
            assertNull(
                rig.player.played
                    .single()
                    .warmKey,
            )
        }

    @Test
    fun aSeekMissAfterASwapFallsBackColdAndSaysSo() =
        runTest {
            answer(source())
            val rig = Rig()
            rig.player.script =
                listOf(
                    listOf(
                        Step { PlayerEvent.WarmDecided(it, swapped()) },
                        Step { PlayerEvent.Playing(1_300, Duration.ZERO, it) },
                    ),
                    listOf(Step { PlayerEvent.Playing(2_000, 60.seconds, it) }),
                )
            val a = controllerPrepared(rig)
            rig.controller.warmNext(a)
            val result = rig.controller.tune(session, a, null, 60.seconds) as TuneResult.Played
            assertEquals("w1", rig.player.played[0].warmKey)
            assertNull(rig.player.played[1].warmKey)
            // The swap stays visible (and its numbers); the fallback says the play that ended up playing was cold.
            assertEquals(WarmUse(true, WarmReason.None, 800, 300), result.warm)
            assertEquals(FallbackCause.SeekMiss, result.fallback?.cause)
        }

    @Test
    fun aTuneCancelledAfterTheHandleIsTakenButBeforeThePlayDropsTheHeldStandby() =
        runTest {
            answer(source())
            var victim: Job? = null
            val player = FakeVideoPlayer()
            val on = WarmConfig(warmNextDecoder = true)
            val controller = TuneController(resolver, server, player, warm = on) { victim?.cancel().let { 1_000L } }
            val a = checkNotNull(controller.preResolve(session, ItemId("a"), Duration.ZERO))
            controller.warmNext(a)
            val job =
                launch {
                    victim = coroutineContext.job
                    controller.tune(session, a, null)
                }
            job.join()
            assertTrue(job.isCancelled)
            assertTrue("nothing played", player.played.isEmpty())
            assertEquals(listOf("warm", "dropWarm:w1"), player.calls.filter { it != "stop" })
        }

    private suspend fun controllerPrepared(rig: Rig) = rig.prepare("a")

    @Test
    fun warmAndPlayRunOnTheCallersThread() =
        runTest {
            answer(source())
            val fake = FakeVideoPlayer()
            fake.script = listOf(playing(swapped()))
            val threads = mutableListOf<Pair<String, String>>()
            val spy =
                object : VideoPlayer by fake {
                    override fun warm(source: PlayerSource): String {
                        threads += "warm" to Thread.currentThread().name.substringBefore(" @")
                        return fake.warm(source)
                    }

                    override fun play(source: PlayerSource): Long {
                        threads += "play" to Thread.currentThread().name.substringBefore(" @")
                        return fake.play(source)
                    }
                }
            val controller = TuneController(resolver, server, spy, warm = WarmConfig(warmNextDecoder = true)) { 1_000L }
            val a = checkNotNull(controller.preResolve(session, ItemId("a"), Duration.ZERO))
            controller.warmNext(a)
            controller.tune(session, a, null)
            assertEquals(listOf("warm", "play"), threads.map { it.first })
            assertEquals(1, threads.map { it.second }.toSet().size)
        }
}
