// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.GuideViewModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Instant

/** No lineup to watch: these tests close the screen without a change. */
private object QuietLineup : ChannelRepository {
    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = flowOf(emptyList())
}

/** The Channels button and the slot it opens over the guide (spec 21). */
@RunWith(AndroidJUnit4::class)
class GuideChannelsButtonUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeSchedule()
    private val server = FakeMediaServer()
    private val sessions = SessionRepository(InMemorySecretStore(), server)
    private val pool = Executors.newSingleThreadExecutor()
    private lateinit var vm: GuideViewModel

    // What the slot saw: how often it was drawn, the keys it got, and the way out it was given.
    private val openings = CopyOnWriteArrayList<Long>()
    private val slotKeys = CopyOnWriteArrayList<Key>()

    @Volatile
    private var done: ((ChannelId?) -> Unit)? = null
    private val tuned = CopyOnWriteArrayList<ChannelId>()
    private var exits = 0

    // Whether the slot, as the real screen does, finishes on the release of its own Back.
    private var finishOnBack = false

    private val clock =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(T0 + 65 * MIN)
        }

    private fun showReady() {
        fake.channel(CH5, 5, (0L until 600L step 60).map { program(it, it + 60, 200 + (it / 60).toInt()) })
        fake.channel(CH7, 7, (0L until 600L step 30).map { program(it, it + 30, 100 + (it / 30).toInt()) })
        val cache = ProgramDetailsCache(server, sessions, CoroutineScope(SupervisorJob()))
        vm = GuideViewModel(fake, cache, clock, pool.asCoroutineDispatcher(), InMemoryLibraryRepository(), QuietLineup)
        compose.setContent {
            StationBreakTheme {
                // Stands in for the activity: a Back that nothing on screen takes leaves live TV.
                BackHandler { exits++ }
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).focusable()) {
                    GuideRoot(
                        vm,
                        CH5,
                        pictureHole = HOLE,
                        onTune = { tuned += it },
                        channels = { _, openedAt, _, onDone -> Slot(openedAt, onDone) },
                    )
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value.state is GuideState.Ready }
        compose.waitForIdle()
        compose.awaitWindowFocus()
    }

    @androidx.compose.runtime.Composable
    private fun Slot(
        openedAt: Long,
        onDone: (ChannelId?) -> Unit,
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(openedAt) {
            openings += openedAt
            done = onDone
            focus.requestFocus()
        }
        Box(
            Modifier
                .fillMaxSize()
                .testTag(SLOT)
                .focusRequester(focus)
                .onKeyEvent {
                    if (it.type == KeyEventType.KeyDown) slotKeys += it.key
                    if (finishOnBack && it.key == Key.Back && it.type == KeyEventType.KeyUp) onDone(null)
                    true
                }.focusable(),
        )
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.close()
        pool.shutdownNow()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Back through the real input path, as the guide's own test does. */
    private fun back() {
        compose.awaitWindowFocus()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val at = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        instrumentation.sendKeySync(KeyEvent(at, at + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        compose.waitForIdle()
    }

    private fun button() = compose.onNodeWithTag(GuideTags.CHANNELS_BUTTON)

    private fun cell(startMin: Long) = compose.onNodeWithTag(GuideTags.cell(CH5, T0 + startMin * MIN))

    private fun openTheSlot() {
        press(Key.DirectionUp)
        button().assertIsSelected()
        press(Key.DirectionCenter)
        compose.waitUntil(TIMEOUT_MS) { done != null }
        compose.onNodeWithTag(SLOT).assertExists()
    }

    @Test
    fun selectOnTheButtonShowsTheSlot() {
        showReady()
        cell(60).assertIsSelected()
        compose.onNodeWithTag(SLOT).assertDoesNotExist()
        openTheSlot()
        assertEquals(1, openings.size)
        // The guide stays open underneath.
        compose.onNodeWithTag(GuideTags.SCREEN).assertExists()
    }

    @Test
    fun closingTheSlotReturnsToTheButton() {
        showReady()
        openTheSlot()
        compose.runOnUiThread { done?.invoke(null) }
        compose.waitForIdle()
        compose.onNodeWithTag(SLOT).assertDoesNotExist()
        button().assertIsSelected()
        cell(60).assertIsNotSelected()
        // The guide has its keys back: Down returns to the cell.
        compose.awaitWindowFocus()
        press(Key.DirectionDown)
        cell(60).assertIsSelected()
        assertEquals(emptyList<ChannelId>(), tuned.toList())
    }

    @Test
    fun keysGoToTheSlotWhileItShows() {
        showReady()
        openTheSlot()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertEquals(listOf(Key.DirectionDown, Key.DirectionRight), slotKeys.toList())
        button().assertIsSelected()
        cell(60).assertIsNotSelected()
    }

    @Test
    fun aBackThatClosesTheSlotIsNotTheGuidesAndTheNextTwoWalkOut() {
        showReady()
        finishOnBack = true
        openTheSlot()
        // The slot finishes on its own Back release; the guide does not take that press.
        back()
        compose.onNodeWithTag(SLOT).assertDoesNotExist()
        button().assertIsSelected()
        assertEquals(0, exits)
        // Back from the button goes to the playing cell, and the next leaves live TV.
        back()
        cell(60).assertIsSelected()
        assertEquals(0, exits)
        back()
        assertEquals(1, exits)
    }

    @Test
    fun aTuneFromTheSlotTunesThroughTheGuide() {
        showReady()
        openTheSlot()
        compose.runOnUiThread { done?.invoke(CH7) }
        compose.waitForIdle()
        compose.onNodeWithTag(SLOT).assertDoesNotExist()
        assertEquals(listOf(CH7), tuned.toList())
    }

    private companion object {
        const val SLOT = "test:channelsSlot"
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        const val TIMEOUT_MS = 5_000L
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
    }
}
