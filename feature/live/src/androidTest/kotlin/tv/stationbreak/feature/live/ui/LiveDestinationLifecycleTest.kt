// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.ScheduleBuild
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
import tv.stationbreak.feature.live.LiveTimings
import tv.stationbreak.feature.live.NextHandle
import tv.stationbreak.feature.live.TuneOutcome
import tv.stationbreak.feature.live.TunePort
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The player belongs to a STARTED period (spec 14, review): moving the screen out of STARTED detaches the surface and
 * releases the player within that callback, then closes the tuner (#431), and coming back makes a new player.
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

        override fun pause(generation: Long) = Unit

        override fun resume(generation: Long) = Unit

        override fun seekTo(
            generation: Long,
            position: Duration,
        ) = Unit

        override fun position(generation: Long): Duration? = null

        override suspend fun currentPosition(generation: Long): Duration? = null

        override fun seekable(generation: Long): Boolean = false

        override fun attach(surface: SurfaceView?) {
            log += "attach(${if (surface == null) "null" else "surface"})#$number"
        }

        override fun release() {
            log += "release#$number"
        }
    }

    /** A tuner whose close takes [closeMs] (the server's encode stop, slow), and writes "close" when it is done. */
    private inner class RecordingTuner(
        private val closeMs: Long = 0,
    ) : TunePort {
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
            delay(closeMs)
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
                    build: ScheduleBuild,
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
            ProgramDetailsCache(server, sessions, CoroutineScope(SupervisorJob())),
            LiveTimings(),
        )
    }

    private fun show(
        vm: LiveViewModel,
        closeMs: Long = 0,
        signedOut: () -> Unit = {},
    ) {
        compose.setContent {
            StationBreakTheme { LiveRoot(vm, signedOut) { _, _ -> RecordingTuner(closeMs) } }
        }
    }

    private fun awaitLog(entry: String) = compose.waitUntil(WAIT_MS) { entry in log }

    /**
     * What the log held when [event] reached an observer added before the screen's own: a lifecycle runs a step down
     * (pause, stop) through its newest observers first, so this one sees the log as the screen's left it in that same
     * callback.
     */
    private fun logAt(event: Lifecycle.Event): CopyOnWriteArrayList<String> {
        val seen = CopyOnWriteArrayList<String>()
        compose.activityRule.scenario.onActivity { activity ->
            activity.lifecycle.addObserver(
                LifecycleEventObserver { _, e -> if (e == event && seen.isEmpty()) seen += listOf("@$e") + log },
            )
        }
        return seen
    }

    @Test
    fun leavingStartedReleasesThePlayerWithinTheStopThenClosesTheTuner() {
        // #431: the player is stopped, detached and released inside ON_STOP itself, not when the teardown gets to it;
        // the tuner's close (the server's encode stop) follows.
        val atStop = logAt(Lifecycle.Event.ON_STOP)
        show(viewModel())
        awaitLog("create#1")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        awaitLog("close")
        assertEquals(listOf("@ON_STOP") + RELEASED, atStop.toList())
        assertEquals(RELEASED + "close", log.toList())
    }

    @Test
    fun backingOutOfTheAppReleasesThePlayerAsTheScreenPauses() {
        // #431: Back out of the app finishes the activity. Android stops it only once the launcher is idle, up to 10 s
        // later, so the player must not wait for ON_STOP: a finishing screen never comes back.
        val atPause = logAt(Lifecycle.Event.ON_PAUSE)
        show(viewModel())
        awaitLog("create#1")
        compose.activityRule.scenario.onActivity { it.finish() }
        compose.waitUntil(WAIT_MS) { atPause.isNotEmpty() }
        assertEquals(listOf("@ON_PAUSE") + RELEASED, atPause.toList())
        awaitLog("close")
        assertEquals(1, log.count { it == "release#1" })
    }

    /** Home (or recents): the activity is told the user is leaving, before its pause. */
    private fun userLeaves() {
        compose.activityRule.scenario.onActivity {
            InstrumentationRegistry.getInstrumentation().callActivityOnUserLeaving(it)
        }
    }

    @Test
    fun homeReleasesThePlayerWithinTheUserLeaveHint() {
        // #431: Home is a user-leave hint, then a pause; Fire OS stops the screen only seconds later.
        show(viewModel())
        awaitLog("create#1")
        var atHint = emptyList<String>()
        compose.activityRule.scenario.onActivity {
            InstrumentationRegistry.getInstrumentation().callActivityOnUserLeaving(it)
            atHint = log.toList()
        }
        assertEquals(RELEASED, atHint)
        awaitLog("close")
        assertEquals(RELEASED + "close", log.toList())
    }

    @Test
    fun homeAgainBeforeTheFirstTeardownEndsLeavesNothingPlaying() {
        // Home, a quick return, and Home again, all while the first sitting's tuner close (the server's encode stop)
        // is still running: the sitting the return asked for must not start behind the launcher once it is done.
        show(viewModel(), closeMs = SLOW_CLOSE_MS)
        awaitLog("create#1")
        // Home: the hint, then the pause (the scenario's move to STARTED starts an activity on top, itself a hint).
        userLeaves()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        // Back, then Home again, before the first close is done.
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        userLeaves()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        awaitLog("close")
        // Time for a sitting queued behind the close to start, if one were going to.
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        val made = log.count { it.startsWith("create#") }
        val released = log.count { it.startsWith("release#") }
        assertEquals("nothing is left playing: $log", made, released)
    }

    @Test
    fun backFromHomeBeforeTheScreenStoppedStartsANewSitting() {
        show(viewModel())
        awaitLog("create#1")
        userLeaves()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitLog("attach(surface)#2")
        assertEquals(1, log.count { it == "release#1" })
        assertEquals(1, log.count { it.startsWith("create#") && it != "create#1" })
    }

    @Test
    fun aPauseWithoutTheHintKeepsPlaying() {
        // A system overlay (the voice search) pauses the screen with no hint: live TV plays on, and no second sitting
        // starts when it goes.
        show(viewModel())
        awaitLog("create#1")
        pauseOnly()
        compose.waitForIdle()
        resumeOnly()
        compose.waitForIdle()
        assertEquals(listOf("create#1", "attach(surface)#1"), log.toList())
    }

    /**
     * A pause with no other activity started and no user-leave hint, as a system overlay window gives. The scenario's
     * own move to STARTED starts a floating activity on top, and that start is itself a user-leave hint.
     */
    private fun pauseOnly() = compose.activityRule.scenario.onActivity { instrumentation().callActivityOnPause(it) }

    private fun resumeOnly() = compose.activityRule.scenario.onActivity { instrumentation().callActivityOnResume(it) }

    private fun instrumentation() = InstrumentationRegistry.getInstrumentation()

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
    fun leavingTheForegroundClosesTheGuideBeforeTheScreenStops() {
        // Home: the screen is paused at once, but stopped only once the launcher is up, seconds later on a Fire TV.
        // Coming back in between must not find the guide left open as it was (it would not be a fresh open).
        val vm = viewModel()
        show(vm)
        awaitLog("create#1")
        compose.runOnIdle { vm.openGuide(0L) }
        pauseOnly()
        compose.waitUntil(WAIT_MS) { !vm.guideOpen.value }
        assertTrue("still watching: paused is not stopped", "release#1" !in log)
        resumeOnly()
    }

    // Live TV with a guide slot that records each showing: "start@<opened at>" and "end".
    private fun showWithGuide(
        vm: LiveViewModel,
        showings: MutableList<String>,
    ) {
        compose.setContent {
            StationBreakTheme {
                LiveRoot(vm, {}, guide = { _, openedAtNanos, _, _ ->
                    DisposableEffect(openedAtNanos) {
                        showings += "start@$openedAtNanos"
                        onDispose { showings += "end" }
                    }
                }) { _, _ -> RecordingTuner() }
            }
        }
        awaitLog("create#1")
        compose.waitForIdle()
    }

    @Test
    fun aCloseAndReopenBetweenTwoFramesHandsTheGuideTheNewOpening() {
        // The flag goes true, false, true before a frame sees it; the slot stays, but it is told the new open time.
        val showings = CopyOnWriteArrayList<String>()
        val vm = viewModel()
        showWithGuide(vm, showings)
        compose.runOnIdle { vm.openGuide(1L) }
        compose.waitForIdle()
        compose.runOnIdle {
            vm.closeGuide()
            vm.openGuide(2L)
        }
        compose.waitForIdle()
        assertEquals(listOf("start@1", "end", "start@2"), showings.toList())
    }

    @Test
    fun guideKeyCloseThenBackReopenGivesTheGuideTwoShowings() {
        // The device's path: Back opens the guide, the Guide key closes it, Back opens it again (3 s later there;
        // nothing goes by the clock, so at once here). Each showing of the slot is one guide session: two starts, one
        // end, and the second opening at a later time.
        val showings = CopyOnWriteArrayList<String>()
        val vm = viewModel()
        showWithGuide(vm, showings)
        tap(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(WAIT_MS) { vm.guideOpen.value }
        tap(KeyEvent.KEYCODE_GUIDE)
        compose.waitUntil(WAIT_MS) { !vm.guideOpen.value }
        compose.waitForIdle()
        tap(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(WAIT_MS) { vm.guideOpen.value }
        compose.waitForIdle()
        assertEquals(listOf("start", "end", "start"), showings.map { it.substringBefore('@') })
        assertTrue(showings.toString(), showings[0] != showings[2])
    }

    private fun tap(code: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val at = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(at, at, KeyEvent.ACTION_DOWN, code, 0))
        compose.waitForIdle()
        instrumentation.sendKeySync(KeyEvent(at, at + PRESS_MS, KeyEvent.ACTION_UP, code, 0))
        compose.waitForIdle()
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
    fun pausingReleasesTheScreenSoTheDeviceCanSleep() {
        // ADR-0021: an untended pause lets the device sleep; Play turns the flag on again. The app takes no wake lock.
        var paused by mutableStateOf(false)
        var host: View? = null
        compose.setContent {
            host = LocalView.current
            StationBreakTheme { KeepScreenOn(LocalLifecycleOwner.current, paused) }
        }
        compose.waitUntil(WAIT_MS) { host?.keepScreenOn == true }
        compose.runOnIdle { paused = true }
        compose.waitUntil(WAIT_MS) { host?.keepScreenOn == false }
        compose.runOnIdle { paused = false }
        compose.waitUntil(WAIT_MS) { host?.keepScreenOn == true }
        compose.runOnIdle { paused = true }
        compose.waitUntil(WAIT_MS) { host?.keepScreenOn == false }
        // A paused screen that stops and starts again stays released.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(false, host?.keepScreenOn)
        compose.runOnIdle { paused = false }
        compose.waitUntil(WAIT_MS) { host?.keepScreenOn == true }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
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
        // The first player made, shown, then detached and released.
        val RELEASED = listOf("create#1", "attach(surface)#1", "attach(null)#1", "release#1")
        const val WAIT_MS = 10_000L
        const val SLOW_CLOSE_MS = 2_000L
        const val SETTLE_MS = 1_000L
        const val PRESS_MS = 50L
    }
}
