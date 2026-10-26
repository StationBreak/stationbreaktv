// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.view.SurfaceView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
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
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.Build
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
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
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.PlayerEvent
import tv.stationbreak.core.playback.PlayerSource
import tv.stationbreak.core.playback.VideoPlayer
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.NextHandle
import tv.stationbreak.feature.live.TuneOutcome
import tv.stationbreak.feature.live.TunePort
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The player belongs to a STARTED period (spec 14, review): moving the screen out of STARTED closes the tuner, detaches
 * the surface and releases the player, in that order, and coming back makes a new player.
 */
@RunWith(AndroidJUnit4::class)
class LiveDestinationLifecycleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = CopyOnWriteArrayList<String>()

    private inner class RecordingPlayer(
        private val number: Int,
    ) : VideoPlayer {
        override val events: SharedFlow<PlayerEvent> = MutableSharedFlow()

        override fun play(source: PlayerSource): Long = 0

        override fun stop() = Unit

        override fun attach(surface: SurfaceView?) {
            log += "attach(${if (surface == null) "null" else "surface"})#$number"
        }

        override fun release() {
            log += "release#$number"
        }
    }

    private inner class RecordingTuner : TunePort {
        override suspend fun tune(
            session: Session,
            item: ItemId,
            start: Duration,
        ): TuneOutcome = TuneOutcome.Cancelled

        override suspend fun preResolve(
            session: Session,
            item: ItemId,
            start: Duration,
        ): NextHandle? = null

        override suspend fun tune(
            session: Session,
            next: NextHandle,
            start: Duration?,
        ): TuneOutcome = TuneOutcome.Cancelled

        override suspend fun discard(
            session: Session,
            next: NextHandle,
        ) = Unit

        override suspend fun stop(session: Session) = Unit

        override suspend fun close(session: Session) {
            log += "close"
        }
    }

    private val session =
        Session(
            UserId("u1"),
            AccessToken("tok"),
            ServerInfo("Home", ServerId("abc"), ServerVersion(12, 2, 0)),
            ServerAddress(Scheme.Https, "media.example.com", 443),
            null,
            false,
        )

    private var created = 0

    private fun viewModel(signedIn: Boolean = true): LiveViewModel {
        val server = FakeMediaServer()
        val sessions = SessionRepository(InMemorySecretStore(), server)
        if (signedIn) runBlocking { sessions.save(session) }
        val nothing =
            object : ScheduleSource {
                override suspend fun onAir(
                    channel: ChannelId,
                    at: Instant,
                    next: Int,
                ): ChannelOnAir? = null

                override suspend fun standIns(
                    channel: ChannelId,
                    block: AiringBlock,
                    exclude: Set<CanonicalId>,
                ): List<AiringBlock> = emptyList()

                override suspend fun airings(at: Instant): List<ChannelAiring> = emptyList()

                override suspend fun schedule(
                    from: Instant,
                    until: Instant,
                    build: Build,
                ): ScheduleWindow? = null
            }
        val noLineup =
            object : ChannelRepository {
                override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = emptyFlow()
            }
        val capabilities = DeviceCapabilities(setOf(Codecs.MP4), emptyList(), setOf(Codecs.AAC), emptySet(), 2)
        return LiveViewModel(
            nothing,
            InMemoryLastChannelStore(),
            sessions,
            InMemoryLibraryRepository(),
            noLineup,
            Clock.System,
            ControllerTunerFactory(PlaybackResolver(server) { capabilities }, server),
            VideoPlayerFactory { _, _ -> RecordingPlayer(++created).also { log += "create#$created" } },
            server,
        )
    }

    private fun show(
        vm: LiveViewModel,
        signedOut: () -> Unit = {},
    ) {
        compose.setContent {
            StationBreakTheme { LiveRoot(vm, signedOut) { _, _ -> RecordingTuner() } }
        }
    }

    private fun awaitLog(entry: String) = compose.waitUntil(WAIT_MS) { entry in log }

    @Test
    fun leavingStartedClosesThenDetachesThenReleases() {
        show(viewModel())
        awaitLog("create#1")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        awaitLog("release#1")
        assertEquals(listOf("create#1", "attach(surface)#1", "close", "attach(null)#1", "release#1"), log.toList())
    }

    @Test
    fun comingBackMakesANewPlayer() {
        show(viewModel())
        awaitLog("create#1")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        awaitLog("release#1")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitLog("create#2")
        awaitLog("attach(surface)#2")
        assertEquals(1, log.count { it == "release#1" })
        assertTrue("the second player is not released while the screen is up", "release#2" !in log)
    }

    @Test
    fun theScreenStaysOnWhileShownAndStartedAndNotAfter() {
        var shown by mutableStateOf(true)
        var host: View? = null
        compose.setContent {
            host = LocalView.current
            StationBreakTheme { if (shown) LiveRoot(viewModel(), {}) { _, _ -> RecordingTuner() } }
        }
        compose.waitUntil(WAIT_MS) { host?.keepScreenOn == true }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(WAIT_MS) { host?.keepScreenOn == false }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(WAIT_MS) { host?.keepScreenOn == true }
        compose.runOnIdle { shown = false }
        compose.waitUntil(WAIT_MS) { host?.keepScreenOn == false }
    }

    @Test
    fun noSessionHandsBackWithoutMakingAPlayer() {
        var signedOut = 0
        show(viewModel(signedIn = false)) { signedOut++ }
        compose.waitUntil(WAIT_MS) { signedOut > 0 }
        assertTrue(log.isEmpty())
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
