// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.ScheduleBuild
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.ProgramInfoTags
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.guide.GuideSnapshot
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.GuideViewModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Instant

/** A schedule whose builds can be held back, so the screen can be seen while the guide is still loading. */
private class GatedSchedule(
    val inner: FakeSchedule,
) : ScheduleSource by inner {
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun schedule(
        from: Instant,
        until: Instant,
        build: ScheduleBuild,
    ): ScheduleWindow? {
        if (build == ScheduleBuild.AsNeeded) gate?.await()
        return inner.schedule(from, until, build)
    }
}

/** The guide on screen (spec 17): focus, the panel, cells of every kind, the info overlay and Back. */
@RunWith(AndroidJUnit4::class)
class GuideScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ch3 = ChannelId("ch-3")
    private val fake = FakeSchedule()
    private val schedule = GatedSchedule(fake)
    private val server = FakeMediaServer()
    private val sessions = SessionRepository(InMemorySecretStore(), server)
    private val tuned = CopyOnWriteArrayList<ChannelId>()
    private val composed = ConcurrentHashMap<String, Int>()
    private var exits = 0

    // The guide's own thread, as Dispatchers.Default would be in the app.
    private val pool = Executors.newSingleThreadExecutor()
    private val work = pool.asCoroutineDispatcher()
    private lateinit var vm: GuideViewModel

    // The clock stands at 65 minutes into the test day; the grid starts at the 60-minute half hour.
    // The clock stands at [clockMs], 65 minutes into the test day unless a test moves it.
    @Volatile
    private var clockMs = min(65)

    private val clock =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(clockMs)
        }

    private fun min(m: Long) = T0 + m * MIN

    /** Channel 3 has a 5-minute program (a compact cell), 5 and 7 half hours and hours, 9 a slate after 2.5 hours. */
    private fun lineup() {
        fake.channel(ch3, 3, listOf(program(0, 70, 400), program(70, 75, 401), program(75, 600, 402)))
        fake.channel(CH5, 5, (0L until 600L step 60).map { program(it, it + 60, 200 + (it / 60).toInt()) })
        fake.channel(CH7, 7, (0L until 600L step 30).map { program(it, it + 30, 100 + (it / 30).toInt()) })
        fake.channel(CH9, 9, listOf(program(0, 150, 300), slate(150, 210), program(210, 600, 301)))
    }

    private fun show(current: ChannelId?) {
        val cache = ProgramDetailsCache(server, sessions, CoroutineScope(SupervisorJob()))
        vm = GuideViewModel(schedule, cache, clock, work)
        compose.setContent {
            // Stands in for the activity: a Back that nothing on screen takes leaves live TV.
            BackHandler { exits++ }
            val count = { channel: ChannelId, start: Long -> composed.merge("${channel.value}@$start", 1, Int::plus) }
            CompositionLocalProvider(
                LocalCellComposed provides { channel, start -> count(channel, start) },
                LocalBodyComposed provides { composed.merge(BODY, 1, Int::plus) },
            ) {
                StationBreakTheme {
                    // As in the app: live TV's root is focusable and the guide takes the focus from it, so a Back the
                    // guide left alone would be spent moving the focus back out to the root.
                    Box(Modifier.fillMaxSize().focusable()) {
                        GuideRoot(vm, current, pictureInCorner = false, onTune = { tuned += it })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showReady(current: ChannelId?) {
        lineup()
        show(current)
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value.state is GuideState.Ready }
        compose.waitForIdle()
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

    /** Back through the real input path: unconsumed, it reaches the activity's back dispatcher on the release. */
    private fun back() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val at = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(at, at, ACTION_DOWN, KEYCODE_BACK, 0))
        instrumentation.sendKeySync(KeyEvent(at, at + PRESS_MS, ACTION_UP, KEYCODE_BACK, 0))
        compose.waitForIdle()
    }

    private fun cell(
        channel: ChannelId,
        startMin: Long,
    ) = compose.onNodeWithTag(GuideTags.cell(channel, min(startMin)))

    private fun text(tag: String): String =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    @Test
    fun focusStartsOnCurrentNowCell() {
        showReady(CH5)
        cell(CH5, 60).assertIsSelected()
        cell(CH7, 60).assertIsNotSelected()
        assertEquals("Test Movie 201", text(GuideTags.PANEL_TITLE))
    }

    @Test
    fun dpadMovesFocusAndPanelFollows() {
        showReady(CH7)
        assertTrue(text(GuideTags.HINT), text(GuideTags.HINT).startsWith("OK Watch"))
        press(Key.DirectionRight)
        cell(CH7, 90).assertIsSelected()
        cell(CH7, 60).assertIsNotSelected()
        assertEquals("Test Movie 103", text(GuideTags.PANEL_TITLE))
        // A later cell: OK opens its info rather than tuning.
        assertTrue(text(GuideTags.HINT), text(GuideTags.HINT).startsWith("OK Info"))
        press(Key.DirectionUp)
        cell(CH5, 60).assertIsSelected()
        assertEquals("Test Movie 201", text(GuideTags.PANEL_TITLE))
        assertTrue(text(GuideTags.HINT).endsWith("Back Exit live TV"))
    }

    @Test
    fun compactCellShowsTitleOnFocus() {
        showReady(ch3)
        // Five minutes is too narrow for a title: nothing is written in the cell until it has the focus.
        compose.onNodeWithTag(GuideTags.COMPACT_LABEL).assertDoesNotExist()
        press(Key.DirectionRight)
        cell(ch3, 70).assertIsSelected()
        assertEquals("Test Movie 401", text(GuideTags.COMPACT_LABEL))
        assertEquals("Test Movie 401", text(GuideTags.PANEL_TITLE))
        press(Key.DirectionRight)
        compose.onNodeWithTag(GuideTags.COMPACT_LABEL).assertDoesNotExist()
    }

    @Test
    fun slateReadsOffAirUntil() {
        showReady(CH9)
        press(Key.DirectionRight)
        cell(CH9, 150).assertIsSelected()
        val slate = text(GuideTags.cellText(CH9, min(150)))
        assertTrue(slate, slate.startsWith("Off air until "))
        assertTrue(text(GuideTags.PANEL_TITLE).startsWith("Off air until "))
        // Nothing to watch or read about: the hint only says how to leave.
        assertEquals("Back Exit live TV", text(GuideTags.HINT))
    }

    @Test
    fun backClosesInfoOverlayFirst() {
        showReady(CH7)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        compose.onNodeWithTag(GuideTags.INFO).assertExists()
        assertEquals("Test Movie 103", text(ProgramInfoTags.TITLE))
        back()
        compose.onNodeWithTag(GuideTags.INFO).assertDoesNotExist()
        assertEquals(0, exits)
        // The overlay is gone: the next Back is the activity's, and leaves live TV.
        back()
        assertEquals(1, exits)
    }

    @Test
    fun selectOnNowCellAsksToTuneItsChannel() {
        showReady(CH7)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        compose.waitUntil(TIMEOUT_MS) { tuned.isNotEmpty() }
        assertEquals(listOf(CH9), tuned.toList())
        compose.onNodeWithTag(GuideTags.INFO).assertDoesNotExist()
    }

    @Test
    fun focusMoveRecomposesOnlyTwoCells() {
        showReady(CH7)
        composed.clear()
        press(Key.DirectionRight)
        cell(CH7, 90).assertIsSelected()
        // Not the grid's body either (BODY would be in the map): the move changed the focus, not the frame.
        // The cell that lost the focus and the one that took it; no row and no other cell.
        assertEquals(mapOf("${CH7.value}@${min(60)}" to 1, "${CH7.value}@${min(90)}" to 1), composed.toMap())
    }

    @Test
    fun horizontalPageRecomposesNoCells() {
        showReady(CH7)
        // 60 -> 90 -> 120 stay in the first 90 minutes; the cell at 150 starts past them, so that move pages.
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        cell(CH7, 120).assertIsSelected()
        assertEquals(min(60), (vm.snapshot.value.state as GuideState.Ready).viewportStartMs)
        composed.clear()

        press(Key.DirectionRight)

        assertEquals(min(150), (vm.snapshot.value.state as GuideState.Ready).viewportStartMs)
        cell(CH7, 150).assertIsSelected()
        // Only the two cells whose focus changed; every other cell of every row kept its composition, and none was
        // brought into it: the page moved the rows in the layout phase.
        val cells = composed.filterKeys { it != BODY }
        assertEquals(mapOf("${CH7.value}@${min(120)}" to 1, "${CH7.value}@${min(150)}" to 1), cells)
    }

    @Test
    fun minuteTickRecomposesOnlyChangedCells() {
        // Ten seconds before 66 minutes, when channel 2's first program ends and its second begins.
        clockMs = min(65) + 50_000
        lineup()
        val ch2 = ChannelId("ch-2")
        fake.channel(ch2, 2, listOf(program(0, 66, 500), program(66, 600, 501)))
        show(CH7)
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value.state is GuideState.Ready }
        compose.waitForIdle()
        val before = (vm.snapshot.value.state as GuideState.Ready).model
        composed.clear()

        // The tick due at 66 minutes reads the clock past it and rebuilds the model.
        clockMs = min(66) + 5_000
        compose.waitUntil(TICK_TIMEOUT_MS) { (vm.snapshot.value.state as GuideState.Ready).nowMs == clockMs }
        compose.waitForIdle()

        val after = (vm.snapshot.value.state as GuideState.Ready).model
        assertTrue("the tick rebuilt the model", after !== before)
        assertTrue("the rebuilt frame reached the grid", (composed[BODY] ?: 0) > 0)
        // The rebuilt model and its cells are equal to the old ones, so the grid and its cells skip by equality; only
        // the two whose on-now state flipped (read from the clock) may compose again.
        val flipped = setOf("${ch2.value}@${min(0)}", "${ch2.value}@${min(66)}")
        val cells = composed.keys.filter { it != BODY }
        assertTrue("recomposed: $cells", flipped.containsAll(cells))
    }

    @Test
    fun loadingAndEmptyStates() {
        // Nothing built yet, and the build held back: the guide says it is loading, with the way out shown.
        fake.nothingCached = true
        schedule.gate = CompletableDeferred()
        lineup()
        show(CH7)
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value.state == GuideState.Loading }
        compose.waitForIdle()
        compose.onNodeWithTag(GuideTags.LOADING).assertExists()
        assertEquals("Back Exit live TV", text(GuideTags.HINT))
        schedule.gate?.complete(Unit)
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value.state is GuideState.Ready }
        compose.waitForIdle()
        compose.onNodeWithTag(GuideTags.LOADING).assertDoesNotExist()
        cell(CH7, 60).assertIsSelected()

        // Nobody signed in: nothing to show, said plainly.
        vm.close()
        fake.signedOut = true
        vm.open(CH7)
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value == GuideSnapshot(GuideState.Empty, null) }
        compose.waitForIdle()
        compose.onNodeWithTag(GuideTags.EMPTY).assertExists()
        compose.onNodeWithTag(GuideTags.GRID).assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val PRESS_MS = 50L
        const val BODY = "body"
        const val TICK_TIMEOUT_MS = 20_000L
    }
}
