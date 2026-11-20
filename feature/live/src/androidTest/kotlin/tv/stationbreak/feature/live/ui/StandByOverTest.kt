// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemoryLastChannelStore
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.FakeFirstLineup
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.PlayKey
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.selection.FakeLibraryChoices
import tv.stationbreak.core.domain.tracks.InMemoryTrackChoiceStore
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.playback.PlayControl
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.PlayerEvent
import tv.stationbreak.core.playback.PlayerSource
import tv.stationbreak.core.playback.TrackChoice
import tv.stationbreak.core.playback.VideoPlayer
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.core.playback.WarmConfig
import tv.stationbreak.core.playback.WarmSwitches
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.DisplayOutput
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.LiveTimings
import tv.stationbreak.feature.live.NextHandle
import tv.stationbreak.feature.live.TuneOutcome
import tv.stationbreak.feature.live.TunePort
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * When live TV tells the app it has stopped standing by (`onStandByOver`, which lifts setup's cheer held over it): not
 * while it starts or tunes, once the picture is up, and at once for a card that says why there is no picture.
 */
@RunWith(AndroidJUnit4::class)
class StandByOverTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val session =
        Session(
            UserId("u1"),
            AccessToken("tok"),
            ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0)),
            ServerAddress(Scheme.Https, "media.example.com", 443),
            null,
            false,
        )
    private val account = LibraryAccount(ServerId("srv1"), UserId("u1"))

    // Half an hour into the test day, when channel 7's programme is on.
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(T0 + 30 * MIN)
        }

    /** A tuner whose first tune holds until [release]: the picture arrives when the test says. */
    private class HeldTuner : TunePort {
        val tuning = CompletableDeferred<Unit>()
        private val picture = CompletableDeferred<Unit>()

        fun release() {
            picture.complete(Unit)
        }

        override suspend fun tune(
            session: Session,
            item: ItemId,
            start: Duration,
            choice: TrackChoice?,
        ): TuneOutcome {
            tuning.complete(Unit)
            picture.await()
            return TuneOutcome.Playing(flow { awaitCancellation() }, PlayControl.None)
        }

        override suspend fun preResolve(
            session: Session,
            item: ItemId,
            start: Duration,
        ): NextHandle? = null

        override suspend fun tune(
            session: Session,
            next: NextHandle,
            start: Duration?,
            choice: TrackChoice?,
        ): TuneOutcome = TuneOutcome.Cancelled

        override suspend fun discard(
            session: Session,
            next: NextHandle,
        ) = Unit

        override fun warmNext(
            session: Session,
            next: NextHandle,
        ): Boolean = false

        override suspend fun stop(session: Session) = Unit

        override suspend fun close(session: Session) = Unit
    }

    private class StillPlayer : VideoPlayer {
        override val events: SharedFlow<PlayerEvent> = MutableSharedFlow()

        override val videoAspect: StateFlow<Float?> = MutableStateFlow(null)

        override fun play(source: PlayerSource): Long = 0

        override fun stop() = Unit

        override fun pause(generation: Long) = Unit

        override fun resume(generation: Long) = Unit

        override fun seekTo(
            generation: Long,
            position: Duration,
        ) = Unit

        override fun position(generation: Long): Duration? = null

        override suspend fun currentPosition(generation: Long): Duration? = null

        override fun seekable(generation: Long): Boolean = false

        override fun attach(surface: SurfaceView?) = Unit

        override fun release() = Unit

        override fun warm(source: PlayerSource): String = "w1"

        override fun dropWarm(key: String) = Unit

        override fun trimMemory(level: Int) = Unit
    }

    /** Signed in; with [onAir], channel 7 is in the lineup and on the air, else the library has no account yet. */
    private fun viewModel(onAir: Boolean): LiveViewModel {
        val server = FakeMediaServer()
        val sessions = SessionRepository(InMemorySecretStore(), server)
        val library = InMemoryLibraryRepository()
        runBlocking {
            sessions.save(session)
            if (onAir) library.useAccount(account)
        }
        val schedule = FakeSchedule()
        if (onAir) schedule.channel(CH7, 7, listOf(program(0, 60, 1)))
        val lineup =
            object : ChannelRepository {
                private val list = if (onAir) listOf(summary()) else emptyList()

                // No account has a lineup until one is active: none, with nothing on the air.
                private val listed = if (onAir) flowOf(list) else emptyFlow()

                override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = listed

                override fun activeChannels(): Flow<List<ChannelSummary>> = flowOf(list)
            }
        val capabilities = DeviceCapabilities(setOf(Codecs.MP4), emptyList(), setOf(Codecs.AAC), emptySet(), 2)
        return LiveViewModel(
            schedule,
            InMemoryLastChannelStore(),
            sessions,
            library,
            lineup,
            clock,
            ControllerTunerFactory(
                PlaybackResolver(server) { capabilities },
                server,
                WarmConfig(),
                WarmSwitches.None,
                InMemoryTrackChoiceStore(),
            ),
            VideoPlayerFactory { _, _ -> StillPlayer() },
            server,
            ProgramDetailsCache(server, sessions, CoroutineScope(SupervisorJob())),
            LiveTimings(),
            FakeLibraryChoices(null),
            DisplayOutput.Present,
            FakeFirstLineup(),
        )
    }

    private fun summary() =
        ChannelSummary(
            CH7,
            7,
            "Test Channel 7",
            ChannelStatus.On,
            ChannelKind.Starter,
            Instant.fromEpochMilliseconds(T0),
            defaultNumber = 7,
            kid = false,
            optIn = false,
            playKey = PlayKey("test"),
        )

    @Test
    fun notWhileItStartsOrTunesThenOnceThePictureIsUp() {
        val tuner = HeldTuner()
        val vm = viewModel(onAir = true)
        var over = 0
        compose.setContent {
            StationBreakTheme { LiveRoot(vm, {}, onStandByOver = { over++ }) { _, _ -> tuner } }
        }

        compose.waitUntil(WAIT_MS) { tuner.tuning.isCompleted }
        compose.waitForIdle()
        assertTrue("held in a stand-by: ${vm.state.value}", isStandingBy(vm.state.value))
        assertEquals("nothing is over while it starts and tunes", 0, over)

        tuner.release()
        compose.waitUntil(WAIT_MS) { vm.state.value is LiveState.Watching }
        compose.waitUntil(WAIT_MS) { over > 0 }
        compose.waitForIdle()
        assertEquals("once, for the picture", 1, over)
    }

    @Test
    fun aNoChannelsCardEndsTheStandByAtOnce() {
        // Signed in, but nothing is on the air: the no-channels card is no stand-by.
        val vm = viewModel(onAir = false)
        var over = 0
        compose.setContent {
            StationBreakTheme { LiveRoot(vm, {}, onStandByOver = { over++ }) { _, _ -> HeldTuner() } }
        }

        compose.waitUntil(WAIT_MS) { over > 0 }
        compose.waitForIdle()
        assertEquals(1, over)
        compose.onNodeWithTag(LiveTags.NO_CHANNELS).assertExists()
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
