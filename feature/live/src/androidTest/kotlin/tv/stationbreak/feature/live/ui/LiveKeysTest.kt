// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_MEDIA_NEXT
import android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.ScheduleSource
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
            }
        val noLineup =
            object : ChannelRepository {
                override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = emptyFlow()
            }
        val capabilities = DeviceCapabilities(setOf(Codecs.MP4), emptyList(), setOf(Codecs.AAC), emptySet(), 2)
        return LiveViewModel(
            nothing,
            InMemoryLastChannelStore(),
            SessionRepository(InMemorySecretStore(), server),
            InMemoryLibraryRepository(),
            noLineup,
            Clock.System,
            ControllerTunerFactory(PlaybackResolver(server) { capabilities }, server),
            VideoPlayerFactory { _, _ -> error("no session, so no player") },
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
    fun backAndTheOtherKeysAreNotConsumed() {
        show()
        for (code in listOf(KEYCODE_BACK, KEYCODE_DPAD_CENTER, KEYCODE_MEDIA_NEXT, KEYCODE_MEDIA_PREVIOUS)) {
            assertNull("$code", liveKeyCommand(key(ACTION_DOWN, code)))
        }
        press(listOf(Key.DirectionCenter, Key.MediaNext, Key.MediaPrevious))
        assertEquals(emptyList<LiveCommand>(), sent.toList())
    }
}
