// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.FakeSyncNow
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.GuideOpening
import tv.stationbreak.core.domain.lineup.PlayKey
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.domain.selection.FakeLibraryChoices
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.guide.GuideMenuEntry
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.GuideViewModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Instant

/** No lineup to watch. */
private object StillLineup : ChannelRepository {
    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = flowOf(emptyList())

    override fun activeChannels(): Flow<List<ChannelSummary>> = flowOf(emptyList())
}

/**
 * The guide with every chosen library gone (spec 26), over the real screen and real key events: the line where the grid
 * goes, the menu row on Settings, the Settings panel's Libraries row, the Libraries slot over the guide, and the Sync
 * now row below Libraries (Part 5).
 */
@RunWith(AndroidJUnit4::class)
class GuideNoLibrariesUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeSchedule()
    private val server = FakeMediaServer()
    private val sessions = SessionRepository(InMemorySecretStore(), server)
    private val pool = Executors.newSingleThreadExecutor()
    private val choices = FakeLibraryChoices(ChoiceView(setOf(ItemId("lib-a")), emptySet(), gone = true))
    private val syncNow = FakeSyncNow()

    // The Even out volume setting the host holds (on by default, as the store is).
    private val levelling = mutableStateOf(true)
    private lateinit var vm: GuideViewModel

    // The way out the slot was given, and the keys that got past the guide to its host.
    @Volatile
    private var done: (() -> Unit)? = null
    private val hostKeys = CopyOnWriteArrayList<Key>()
    private val guideOpenedAt = System.nanoTime()

    private val clock =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(T0 + 65 * MIN)
        }

    private fun showGone(lineup: ChannelRepository = StillLineup) {
        // The schedule still holds programmes: the guide shows the line all the same.
        fake.channel(CH5, 5, (0L until 600L step 60).map { program(it, it + 60, 200 + (it / 60).toInt()) })
        fake.channel(CH7, 7, (0L until 600L step 30).map { program(it, it + 30, 100 + (it / 30).toInt()) })
        val cache = ProgramDetailsCache(server, sessions, CoroutineScope(SupervisorJob()))
        val work = pool.asCoroutineDispatcher()
        vm = GuideViewModel(fake, cache, clock, work, lineup, selection = choices.selection, sync = syncNow)
        compose.setContent {
            StationBreakTheme {
                // The host: a key the guide does not keep comes here.
                Box(
                    Modifier
                        .requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp)
                        .onKeyEvent {
                            if (it.type == KeyEventType.KeyDown) hostKeys += it.key
                            false
                        }.focusable(),
                ) {
                    GuideRoot(
                        vm,
                        GuideOpening(CH5, null, guideOpenedAt),
                        pictureHole = HOLE,
                        onTune = {},
                        channels = { _, _, _, _ -> },
                        libraries = { _, onDone -> Slot(onDone) },
                        levelling = levelling.value,
                        onLevelling = { enabled, _ -> levelling.value = enabled },
                    )
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value.state is GuideState.Ready }
        compose.waitForIdle()
        compose.awaitWindowFocus()
    }

    // It never takes the key focus, so the guide keeps it while the slot covers it: the guide must keep every key.
    @Composable
    private fun Slot(onDone: () -> Unit) {
        LaunchedEffect(Unit) { done = onDone }
        Box(Modifier.fillMaxSize().testTag(SLOT))
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.close()
        pool.shutdownNow()
    }

    private fun press(keyCode: Int) {
        compose.awaitWindowFocus()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun awaitSelected(tag: String) {
        compose.waitUntil(TIMEOUT_MS) { runCatching { compose.onNodeWithTag(tag).assertIsSelected() }.isSuccess }
    }

    @Test
    fun goneSelection_lineShows_settingsOpensLibraries_slotCoversTheGuide_doneReturnsToThePanel() {
        showGone()

        // The line stands where the grid goes, and the menu row has the focus, on Settings.
        compose.onNodeWithTag(GuideTags.NO_LIBRARIES).assertExists()
        compose.onNodeWithTag(GuideTags.GRID).assertDoesNotExist()
        awaitSelected(GuideTags.SETTINGS_BUTTON)

        // Select opens the panel; Down is its Screen fit row (Safe chosen, the default), and Down again Libraries.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(GuideTags.SETTINGS_LIBRARIES).fetchSemanticsNodes().isNotEmpty()
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitSelected(GuideTags.FIT_SAFE)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitSelected(GuideTags.SETTINGS_LIBRARIES)

        // Select draws the slot over the guide, which leaves the semantics tree.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) { done != null }
        compose.onNodeWithTag(SLOT).assertExists()
        compose.onNodeWithTag(GuideTags.SCREEN, useUnmergedTree = true).assertDoesNotExist()

        // Keys while the slot covers the guide: none moves the guide or the panel, and none gets past the guide.
        hostKeys.clear()
        for (key in listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )) {
            press(key)
        }
        assertEquals("keys that got past the guide", emptyList<Key>(), hostKeys.toList())
        assertEquals(GuideMenuEntry.Settings, vm.snapshot.value.menu)
        compose.onNodeWithTag(SLOT).assertExists()

        // The slot's onDone brings the panel back, still on Libraries, with the line where the grid goes.
        compose.runOnUiThread { done?.invoke() }
        compose.waitForIdle()
        compose.onNodeWithTag(SLOT).assertDoesNotExist()
        awaitSelected(GuideTags.SETTINGS_LIBRARIES)
        compose.onNodeWithTag(GuideTags.NO_LIBRARIES).assertExists()

        // Down is Sync now. Select starts one sync, the panel stays open, and the line beside the row says it is
        // syncing; a held Select starts no other.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitSelected(GuideTags.SETTINGS_SYNC_NOW)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(GuideTags.SETTINGS_SYNC_STATUS).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, syncNow.starts)
        awaitSelected(GuideTags.SETTINGS_SYNC_NOW)

        // Up is Libraries again.
        press(KeyEvent.KEYCODE_DPAD_UP)
        awaitSelected(GuideTags.SETTINGS_LIBRARIES)
    }

    // Even out volume (#498), the panel's last row: Down stops on it, Left and Right pick Off and On,
    // and the setting the host holds follows. Select closes the panel keeping the pick, as the other choice rows do.
    @Test
    fun evenOutVolumeRow_leftRightPickAndSelectClosesKeepingThePick() {
        showGone()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(GuideTags.SETTINGS_LIBRARIES).fetchSemanticsNodes().isNotEmpty()
        }
        // Screen fit, Libraries, Sync now, then the new row; one more Down stays on it.
        repeat(ROWS_TO_LEVELLING) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        // On is the default.
        awaitSelected(GuideTags.LEVELLING_ON)
        compose.onNodeWithTag(GuideTags.LEVELLING_OFF).assertIsNotSelected()
        assertEquals(true, levelling.value)

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitSelected(GuideTags.LEVELLING_OFF)
        compose.onNodeWithTag(GuideTags.LEVELLING_ON).assertIsNotSelected()
        assertEquals(false, levelling.value)

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitSelected(GuideTags.LEVELLING_ON)
        assertEquals(true, levelling.value)

        // Select on Off closes the panel and keeps Off: it does not flip it.
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitSelected(GuideTags.LEVELLING_OFF)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(GuideTags.SETTINGS_LIBRARIES).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(false, levelling.value)
    }

    private fun summary(
        id: ChannelId,
        number: Int,
    ) = ChannelSummary(
        id,
        number,
        "Test Channel $number",
        ChannelStatus.On,
        ChannelKind.Starter,
        Instant.fromEpochMilliseconds(T0),
        number,
        kid = false,
        optIn = false,
        playKey = PlayKey("test"),
    )

    // The Libraries screen hides the guide: a lineup change under it reloads nothing until it closes, then once, and
    // the panel is left open on Settings.
    @Test
    fun librariesSlot_pausesTheGuide_andOneReloadFollowsItsClosing() {
        val lineup = MutableStateFlow(listOf(summary(CH5, 5), summary(CH7, 7)))
        showGone(
            object : ChannelRepository {
                override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = lineup

                override fun activeChannels(): Flow<List<ChannelSummary>> = lineup
            },
        )
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(GuideTags.SETTINGS_LIBRARIES).fetchSemanticsNodes().isNotEmpty()
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitSelected(GuideTags.SETTINGS_LIBRARIES)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) { done != null }
        compose.waitForIdle()

        val reads = fake.scheduleCalls.size
        lineup.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        assertEquals("the guide read the schedule under the Libraries screen", reads, fake.scheduleCalls.size)

        compose.runOnUiThread { done?.invoke() }
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT_MS) { fake.scheduleCalls.size > reads }
        Thread.sleep(SETTLE_MS)
        assertEquals(
            "one reload after the screen closed",
            1,
            fake.scheduleCalls.size - reads,
        )
        assertEquals(GuideMenuEntry.Settings, vm.snapshot.value.menu)
        awaitSelected(GuideTags.SETTINGS_LIBRARIES)
    }

    private companion object {
        const val SETTLE_MS = 500L
        const val SLOT = "test:librariesSlot"
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        const val TIMEOUT_MS = 5_000L

        // Down presses from Appearance to the Even out volume row: Screen fit, Libraries, Sync now, it.
        const val ROWS_TO_LEVELLING = 4
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
    }
}
