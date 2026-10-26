// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_MEDIA_NEXT
import android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
import android.view.KeyEvent.KEYCODE_MENU
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.ScheduleBuild
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.FlipDirection
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.NextHandle
import tv.stationbreak.feature.live.TuneOutcome
import tv.stationbreak.feature.live.TunePort
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** The remote's keys on the live screen (spec 15): which key sends which command, and what is left alone. */
@RunWith(AndroidJUnit4::class)
class LiveKeysTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sent = CopyOnWriteArrayList<LiveCommand>()

    private object IdleTuner : TunePort {
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

        override suspend fun close(session: Session) = Unit
    }

    /** No session is saved, so the sitting ends at once with the screen handing over: the keys are all we exercise. */
    private fun viewModel(): LiveViewModel {
        val server = FakeMediaServer()
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
                    build: ScheduleBuild,
                ): ScheduleWindow? = null
            }
        val noLineup =
            object : ChannelRepository {
                override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = emptyFlow()
            }
        val capabilities = DeviceCapabilities(setOf(Codecs.MP4), emptyList(), setOf(Codecs.AAC), emptySet(), 2)
        val sessions = SessionRepository(InMemorySecretStore(), server)
        return LiveViewModel(
            nothing,
            InMemoryLastChannelStore(),
            sessions,
            InMemoryLibraryRepository(),
            noLineup,
            Clock.System,
            ControllerTunerFactory(PlaybackResolver(server) { capabilities }, server),
            VideoPlayerFactory { _, _ -> error("no session, so no player") },
            server,
            ProgramDetailsCache(server, sessions, CoroutineScope(SupervisorJob())),
        )
    }

    private fun show() {
        val vm = viewModel()
        compose.setContent {
            StationBreakTheme { LiveRoot(vm, {}, { sent += it }) { _, _ -> IdleTuner } }
        }
        compose.waitForIdle()
    }

    private fun press(keys: List<Key>) {
        compose.onRoot().performKeyInput { keys.forEach { tap(it) } }
        compose.waitForIdle()
    }

    private fun KeyInjectionScope.tap(key: Key) {
        keyDown(key)
        keyUp(key)
    }

    private fun key(
        action: Int,
        code: Int,
    ) = KeyEvent(android.view.KeyEvent(action, code))

    @Test
    fun upAndChannelUpSendFlipUp() {
        show()
        press(listOf(Key.DirectionUp, Key.ChannelUp))
        assertEquals(List(2) { LiveCommand.Flip(FlipDirection.Up) }, sent.toList())
    }

    @Test
    fun downAndChannelDownSendFlipDown() {
        show()
        press(listOf(Key.DirectionDown, Key.ChannelDown))
        assertEquals(List(2) { LiveCommand.Flip(FlipDirection.Down) }, sent.toList())
    }

    @Test
    fun menuSendsPrevious() {
        show()
        press(listOf(Key.Menu))
        assertEquals(listOf<LiveCommand>(LiveCommand.Previous), sent.toList())
    }

    @Test
    fun aHeldKeyFlipsOncePerRepeat() {
        val repeat = KeyEvent(android.view.KeyEvent(0, 0, ACTION_DOWN, KEYCODE_DPAD_UP, 3))
        assertEquals(LiveCommand.Flip(FlipDirection.Up), liveKeyCommand(repeat))
    }

    @Test
    fun releasingAKeyDoesNothing() {
        assertNull(liveKeyCommand(key(ACTION_UP, KEYCODE_DPAD_UP)))
    }

    @Test
    fun theReleaseOfAMappedKeyIsConsumedButSendsNothingAndBackInTheGuidePassesBothWays() {
        val got = mutableListOf<LiveCommand>()
        for (code in listOf(KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_MENU)) {
            assertTrue("$code up consumed", handleLiveKey(key(ACTION_UP, code)) { got += it })
        }
        assertEquals(emptyList<LiveCommand>(), got)
        assertTrue(handleLiveKey(key(ACTION_DOWN, KEYCODE_DPAD_UP)) { got += it })
        assertEquals(listOf<LiveCommand>(LiveCommand.Flip(FlipDirection.Up)), got)
        val inGuide = OverlayUp(guide = true)
        assertFalse(handleLiveKey(key(ACTION_DOWN, KEYCODE_BACK), inGuide) { got += it })
        assertFalse(handleLiveKey(key(ACTION_UP, KEYCODE_BACK), inGuide) { got += it })
        assertEquals(1, got.size)
    }

    @Test
    fun theKeysStillWorkAfterTheScreenStopsAndStartsAgain() {
        show()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        press(listOf(Key.DirectionUp))
        assertEquals(listOf<LiveCommand>(LiveCommand.Flip(FlipDirection.Up)), sent.toList())
    }

    @Test
    fun backSendsNoCommandAndTheMediaKeysAreNotConsumed() {
        show()
        for (code in listOf(KEYCODE_BACK, KEYCODE_MEDIA_NEXT, KEYCODE_MEDIA_PREVIOUS)) {
            assertNull("$code", liveKeyCommand(key(ACTION_DOWN, code)))
        }
        press(listOf(Key.MediaNext, Key.MediaPrevious))
        assertEquals(emptyList<LiveCommand>(), sent.toList())
    }

    @Test
    fun selectWithNoOverlayAsksForTheActionsBanner() {
        show()
        press(listOf(Key.DirectionCenter))
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowBanner), sent.toList())
    }

    @Test
    fun infoKeyTwiceSendsShowInfoTwice() {
        show()
        press(listOf(Key.Info, Key.Info))
        // The keys only send; the machine decides that the first brings the banner and the second opens the overlay.
        // That part is LiveInfoTest.infoWithNoBannerShowsTheActionsBannerFirst.
        assertEquals(List(2) { LiveCommand.ShowInfo }, sent.toList())
    }

    @Test
    fun selectBelongsToTheButtonsWhileTheActionsBannerShows() {
        val got = mutableListOf<LiveCommand>()
        val actions = OverlayUp(actions = true)
        assertFalse(handleLiveKey(key(ACTION_DOWN, KEYCODE_DPAD_CENTER), actions) { got += it })
        assertFalse(handleLiveKey(key(ACTION_DOWN, KEYCODE_DPAD_CENTER), OverlayUp(info = true)) { got += it })
        assertEquals(emptyList<LiveCommand>(), got)
    }

    @Test
    fun sidewaysKeysKeepTheActionsBannerUpAndStillMoveTheFocus() {
        val got = mutableListOf<LiveCommand>()
        val actions = OverlayUp(actions = true)
        for (code in listOf(KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT)) {
            assertFalse(handleLiveKey(key(ACTION_DOWN, code), actions) { got += it })
        }
        assertEquals(List(2) { LiveCommand.ShowBanner }, got)
        got.clear()
        assertFalse(handleLiveKey(key(ACTION_DOWN, KEYCODE_DPAD_LEFT)) { got += it })
        assertEquals(emptyList<LiveCommand>(), got)
    }

    @Test
    fun backClosesAnOverlayButNeverATuneBanner() {
        val got = mutableListOf<LiveCommand>()
        val guide = mutableListOf<Boolean>()
        // With nothing up, Back opens the guide: consumed, but no command for the machine.
        assertTrue(handleLiveKey(key(ACTION_DOWN, KEYCODE_BACK), guide = { guide += it }) { got += it })
        assertEquals(listOf(true), guide)
        assertTrue(handleLiveKey(key(ACTION_DOWN, KEYCODE_BACK), OverlayUp(info = true)) { got += it })
        assertTrue(handleLiveKey(key(ACTION_UP, KEYCODE_BACK), OverlayUp(info = true)) { got += it })
        assertTrue(handleLiveKey(key(ACTION_DOWN, KEYCODE_BACK), OverlayUp(actions = true)) { got += it })
        assertEquals(listOf(LiveCommand.DismissOverlay, LiveCommand.DismissOverlay), got)
    }
}
