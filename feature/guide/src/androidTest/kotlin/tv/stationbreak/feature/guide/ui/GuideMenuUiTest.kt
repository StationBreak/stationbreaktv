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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.GuideOpening
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.guide.GuideMenuEntry
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.GuideViewModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Instant

/** No lineup to watch: these tests close the screen without a change. */
private object QuietLineup : ChannelRepository {
    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = flowOf(emptyList())

    override fun activeChannels(): Flow<List<ChannelSummary>> = flowOf(emptyList())
}

/**
 * The menu sheet (#756: Channels, Settings and Exit, opened by the Menu key or by Back on the playing cell) and the
 * slot Channels opens over the guide (spec 21).
 */
@RunWith(AndroidJUnit4::class)
class GuideMenuUiTest {
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

    private val guideOpenedAt = System.nanoTime()

    private val clock =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(T0 + 65 * MIN)
        }

    private fun showReady() {
        fake.channel(CH5, 5, (0L until 600L step 60).map { program(it, it + 60, 200 + (it / 60).toInt()) })
        fake.channel(CH7, 7, (0L until 600L step 30).map { program(it, it + 30, 100 + (it / 30).toInt()) })
        val cache = ProgramDetailsCache(server, sessions, CoroutineScope(SupervisorJob()))
        vm = GuideViewModel(fake, cache, clock, pool.asCoroutineDispatcher(), QuietLineup)
        compose.setContent {
            StationBreakTheme {
                // Stands in for the activity: a Back that nothing on screen takes leaves live TV.
                BackHandler { exits++ }
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).focusable()) {
                    GuideRoot(
                        vm,
                        // One showing of the guide: the value live TV holds for it, the same on every recomposition.
                        GuideOpening(CH5, null, guideOpenedAt),
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

    private fun channels() = compose.onNodeWithTag(GuideTags.CHANNELS_BUTTON)

    private fun settings() = compose.onNodeWithTag(GuideTags.SETTINGS_BUTTON)

    private fun sheet() = compose.onNodeWithTag(GuideTags.MENU_SHEET)

    private fun hint(): String =
        compose
            .onNodeWithTag(GuideTags.HINT, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    private fun exit() = compose.onNodeWithTag(GuideTags.EXIT_BUTTON)

    private fun cell(startMin: Long) = compose.onNodeWithTag(GuideTags.cell(CH5, T0 + startMin * MIN))

    // The view model has the Channels entry focused at once; the semantics follow a frame later, so they are
    // waited for.
    private fun awaitOnChannels() {
        val shown = vm.snapshot.value
        val state = shown.state::class.simpleName
        assertEquals("the view model's menu (state $state)", GuideMenuEntry.Channels, shown.menu)
        compose.waitUntil(TIMEOUT_MS) { runCatching { channels().assertIsSelected() }.isSuccess }
    }

    // Back from the playing cell opens the menu sheet, on Channels.
    private fun openTheSlot() {
        back()
        awaitOnChannels()
        press(Key.DirectionCenter)
        compose.waitUntil(TIMEOUT_MS) { done != null }
        compose.onNodeWithTag(SLOT).assertExists()
    }

    @Test
    fun selectOnChannelsShowsTheSlot() {
        showReady()
        cell(60).assertIsSelected()
        compose.onNodeWithTag(SLOT).assertDoesNotExist()
        openTheSlot()
        assertEquals(1, openings.size)
        // The guide stays open underneath (its session and focus), but is out of the semantics tree while the screen
        // covers it: a screen reader sees one screen.
        assertTrue(vm.snapshot.value.state is GuideState.Ready)
        assertEquals(GuideMenuEntry.Channels, vm.snapshot.value.menu)
        compose.onNodeWithTag(GuideTags.SCREEN, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun closingTheSlotReturnsToChannels() {
        showReady()
        openTheSlot()
        compose.runOnUiThread { done?.invoke(null) }
        compose.waitForIdle()
        compose.onNodeWithTag(SLOT).assertDoesNotExist()
        // The sheet is back, as it was, on Channels.
        sheet().assertExists()
        awaitOnChannels()
        cell(60).assertIsNotSelected()
        // The guide has its keys back: the sheet Back opened is left by Back again, as the menu row was.
        compose.awaitWindowFocus()
        assertEquals(0, exits)
        back()
        assertEquals(1, exits)
        assertEquals(emptyList<ChannelId>(), tuned.toList())
    }

    @Test
    fun keysGoToTheSlotWhileItShows() {
        showReady()
        openTheSlot()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertEquals(listOf(Key.DirectionDown, Key.DirectionRight), slotKeys.toList())
        // The guide did not move under it (and is out of the semantics tree, so its nodes cannot be asked).
        assertEquals(GuideMenuEntry.Channels, vm.snapshot.value.menu)
    }

    @Test
    fun aBackThatClosesTheSlotIsNotTheGuidesAndTheNextLeaves() {
        showReady()
        finishOnBack = true
        openTheSlot()
        // The slot finishes on its own Back release; the guide does not take that press.
        back()
        compose.onNodeWithTag(SLOT).assertDoesNotExist()
        awaitOnChannels()
        assertEquals(0, exits)
        // Back in the sheet Back opened leaves live TV, as Back from the menu row did.
        back()
        assertEquals(1, exits)
    }

    @Test
    fun theExitRowIsInTheSheetFocusableAndLeaves() {
        showReady()
        // No menu row between the panel and the grid: the rows exist only in the sheet.
        exit().assertDoesNotExist()
        back()
        awaitOnChannels()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        exit().assertIsSelected()
        channels().assertIsNotSelected()
        assertEquals(0, exits)
        press(Key.DirectionCenter)
        assertEquals(1, exits)
        compose.onNodeWithTag(SLOT).assertDoesNotExist()
    }

    @Test
    fun backFromAnyRowOfABackOpenedSheetLeavesLiveTv() {
        showReady()
        back()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        exit().assertIsSelected()
        assertEquals(0, exits)
        back()
        assertEquals(1, exits)
    }

    @Test
    fun backFromThePlayingCellOpensTheSheetAndBackAgainLeaves() {
        showReady()
        cell(60).assertIsSelected()
        assertTrue(hint(), hint().contains("Back To the menu"))
        back()
        awaitOnChannels()
        assertEquals("Back Exit live TV", hint())
        assertEquals(0, exits)
        back()
        assertEquals(1, exits)
    }

    @Test
    fun backFromAnotherCellGoesToThePlayingCellThenOpensTheSheetThenLeaves() {
        showReady()
        press(Key.DirectionRight)
        cell(120).assertIsSelected()
        back()
        cell(60).assertIsSelected()
        sheet().assertDoesNotExist()
        back()
        awaitOnChannels()
        assertEquals(0, exits)
        back()
        assertEquals(1, exits)
    }

    @Test
    fun theMenuKeyClosesASheetBackOpenedAndASheetItReopensIsClosedByBack() {
        showReady()
        back()
        awaitOnChannels()
        press(Key.Menu)
        sheet().assertDoesNotExist()
        cell(60).assertIsSelected()
        press(Key.Menu)
        awaitOnChannels()
        assertEquals("Back Close the menu", hint())
        back()
        sheet().assertDoesNotExist()
        cell(60).assertIsSelected()
        assertEquals(0, exits)
        // And Back from the playing cell is the way out again.
        back()
        awaitOnChannels()
        back()
        assertEquals(1, exits)
    }

    @Test
    fun upAndDownMoveBetweenTheRowsAndStopAtTheEnds() {
        showReady()
        press(Key.Menu)
        awaitOnChannels()
        press(Key.DirectionUp)
        channels().assertIsSelected()
        press(Key.DirectionDown)
        settings().assertIsSelected()
        channels().assertIsNotSelected()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        exit().assertIsSelected()
        press(Key.DirectionUp)
        settings().assertIsSelected()
        // Left and Right stay in the sheet.
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        settings().assertIsSelected()
    }

    @Test
    fun selectOnSettingsOpensThePanelAndBackReturnsToTheSheetOnSettings() {
        showReady()
        press(Key.Menu)
        press(Key.DirectionDown)
        settings().assertIsSelected()
        press(Key.DirectionCenter)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(GuideTags.THEME_DARK).fetchSemanticsNodes().isNotEmpty()
        }
        // The panel is not read under the sheet: the sheet steps aside while it shows.
        sheet().assertDoesNotExist()
        back()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(GuideTags.THEME_DARK).fetchSemanticsNodes().isEmpty()
        }
        sheet().assertExists()
        settings().assertIsSelected()
    }

    @Test
    fun theMenuKeyOpensAndClosesTheSheetFromAProgrammeCellAndTheFocusReturns() {
        showReady()
        press(Key.DirectionRight)
        cell(120).assertIsSelected()
        sheet().assertDoesNotExist()
        assertTrue(hint(), hint().contains("Menu Open the menu"))
        press(Key.Menu)
        sheet().assertExists()
        awaitOnChannels()
        cell(120).assertIsNotSelected()
        assertEquals("Back Close the menu", hint())
        press(Key.Menu)
        sheet().assertDoesNotExist()
        cell(120).assertIsSelected()
        assertEquals(0, exits)
        // Opened again, it is on Channels again, and Back closes it to the same cell.
        press(Key.Menu)
        awaitOnChannels()
        back()
        sheet().assertDoesNotExist()
        cell(120).assertIsSelected()
    }

    @Test
    fun theMenuKeyOpensAndClosesTheSheetFromTheChannelColumnAndTheFocusReturns() {
        showReady()
        press(Key.DirectionLeft)
        compose.onNodeWithTag(GuideTags.CHANNEL_PANEL).assertExists()
        press(Key.Menu)
        sheet().assertExists()
        awaitOnChannels()
        press(Key.Menu)
        sheet().assertDoesNotExist()
        // Still on the channel column: its panel and its hint, no cell selected.
        compose.onNodeWithTag(GuideTags.CHANNEL_PANEL).assertExists()
        assertTrue(hint(), hint().contains("Back to programs"))
        cell(60).assertIsNotSelected()
        press(Key.DirectionRight)
        cell(60).assertIsSelected()
    }

    @Test
    fun theMenuKeyIsNotTheGuidesWhileAScreenCoversIt() {
        showReady()
        openTheSlot()
        compose.runOnUiThread { slotKeys.clear() }
        press(Key.Menu)
        assertEquals(listOf(Key.Menu), slotKeys.toList())
        assertEquals(GuideMenuEntry.Channels, vm.snapshot.value.menu)
    }

    @Test
    fun noMenuRowSitsBetweenThePanelAndTheGridAndThePanelKeepsTheBand() {
        showReady()
        for (tag in listOf(GuideTags.CHANNELS_BUTTON, GuideTags.SETTINGS_BUTTON, GuideTags.EXIT_BUTTON)) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
        val panel = compose.onNodeWithTag(GuideTags.PANEL).getBoundsInRoot()
        val grid = compose.onNodeWithTag(GuideTags.GRID).getBoundsInRoot()
        // The panel takes the whole band beside the picture, and the grid stays where it was under it.
        assertEquals("panel height", (HOLE.bottom - HOLE.top).value, panel.height.value, 0.5f)
        assertEquals("gap to the grid", TopGap.value, (grid.top - panel.bottom).value, 0.5f)
    }

    @Test
    fun theSheetIs220DpWideFullHeightOnTheLeftEdgeWithRowsThirtySixDpTall() {
        showReady()
        press(Key.Menu)
        awaitOnChannels()
        val scrim = compose.onNodeWithTag(GuideTags.MENU_SCRIM).getBoundsInRoot()
        val sheet = sheet().getBoundsInRoot()
        assertEquals("sheet width", 220f, sheet.width.value, 0.5f)
        assertEquals("sheet left", scrim.left.value, sheet.left.value, 0.5f)
        assertEquals("sheet height", scrim.height.value, sheet.height.value, 0.5f)
        assertEquals("the scrim covers the screen", HEIGHT_DP.toFloat(), scrim.height.value, 0.5f)
        for (tag in listOf(GuideTags.CHANNELS_BUTTON, GuideTags.SETTINGS_BUTTON, GuideTags.EXIT_BUTTON)) {
            val row = compose.onNodeWithTag(tag).getBoundsInRoot()
            assertEquals("$tag height", 36f, row.height.value, 0.5f)
            assertTrue("$tag is inside the sheet", row.right <= sheet.right && row.left >= sheet.left)
        }
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
