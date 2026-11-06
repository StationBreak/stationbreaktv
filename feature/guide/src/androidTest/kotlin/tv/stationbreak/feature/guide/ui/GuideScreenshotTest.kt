// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
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
import org.junit.Assert.assertNotNull
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
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.info
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.GuideViewModel
import java.io.File
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Instant

/** No lineup to watch: these shots never open channel management. */
private object ScreenshotLineup : ChannelRepository {
    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = flowOf(emptyList())

    override fun activeChannels(): Flow<List<ChannelSummary>> = flowOf(emptyList())
}

/**
 * The guide's focus treatment on the API 25 emulator (#566, #569), as PNG files for the proof. The same path as
 * `FirstSyncScreenshotTest`: UiAutomation's screenshot, because Compose's `captureToImage` needs API 26, written under
 * the test app's external files in [SCREENSHOT_DIR]. The lineup, the 960 by 540 host and the keys are the guide screen
 * test's. The focused cell is a long title, cut at the focus plate's end inset. A focused slate reads OFF AIR and the
 * panel still says when it returns; the dashed border is the resting slate, and the focus plate replaces it while
 * that cell has the focus.
 */
@RunWith(AndroidJUnit4::class)
class GuideScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeSchedule()
    private val server = FakeMediaServer()
    private val sessions = SessionRepository(InMemorySecretStore(), server)
    private val pool = Executors.newSingleThreadExecutor()
    private lateinit var vm: GuideViewModel

    private val clock =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(T0 + 65 * MIN)
        }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.close()
        pool.shutdownNow()
    }

    @Test
    fun theFocusStates() {
        showReady(CH7)
        cell(CH7, 60).assertIsSelected()
        channels().assertIsNotSelected()
        val focusedTitle = GuideTags.cellText(CH7, T0 + 60 * MIN)
        assertEquals(LONG_TITLE, text(focusedTitle))
        assertTrue("the long title ends at the inset, not on the ink gap", ellipsized(focusedTitle))
        shoot(FOCUSED_CELL)

        back()
        channels().assertIsSelected()
        shoot(FOCUSED_MENU)

        press(Key.DirectionDown)
        cell(CH7, 60).assertIsSelected()
        channels().assertIsNotSelected()
        shoot(RESTING_MENU)

        press(Key.DirectionDown)
        press(Key.DirectionRight)
        cell(CH9, 150).assertIsSelected()
        assertEquals("OFF AIR", text(GuideTags.cellText(CH9, T0 + 150 * MIN)))
        assertTrue(text(GuideTags.PANEL_TITLE).startsWith("Off air until "))
        shoot(OFF_AIR)
    }

    /** The guide screen test's lineup: channel 9's slate starts two and a half hours in. */
    private fun showReady(current: ChannelId) {
        fake.channel(ChannelId("ch-3"), 3, (0L until 600L step 30).map { program(it, it + 30, 400) })
        fake.channel(CH5, 5, (0L until 600L step 60).map { program(it, it + 60, 200 + (it / 60).toInt()) })
        fake.channel(
            CH7,
            7,
            (0L until 600L step 30).map { start ->
                val n = 100 + (start / 30).toInt()
                val title = if (start == 60L) LONG_TITLE else "Test Movie $n"
                program(start, start + 30, n, info = info(n, title))
            },
        )
        fake.channel(CH9, 9, listOf(program(0, 150, 300), slate(150, 210), program(210, 600, 301)))
        val cache = ProgramDetailsCache(server, sessions, CoroutineScope(SupervisorJob()))
        vm = GuideViewModel(fake, cache, clock, pool.asCoroutineDispatcher(), ScreenshotLineup)
        compose.setContent {
            BackHandler { }
            StationBreakTheme {
                Box(Modifier.requiredSize(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp).focusable()) {
                    GuideRoot(
                        vm,
                        GuideOpening(current, null, 1L),
                        pictureHole = HOLE,
                        onTune = {},
                        channels = { _, _, _, _ -> },
                    )
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value.state is GuideState.Ready }
        compose.waitForIdle()
        compose.awaitWindowFocus()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Back through the real input path, as the guide screen test does. */
    private fun back() {
        compose.awaitWindowFocus()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val at = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(at, at, ACTION_DOWN, KEYCODE_BACK, 0))
        instrumentation.sendKeySync(KeyEvent(at, at + PRESS_MS, ACTION_UP, KEYCODE_BACK, 0))
        compose.waitForIdle()
    }

    private fun cell(
        channel: ChannelId,
        startMin: Long,
    ) = compose.onNodeWithTag(GuideTags.cell(channel, T0 + startMin * MIN))

    private fun channels() = compose.onNodeWithTag(GuideTags.CHANNELS_BUTTON)

    private fun ellipsized(tag: String): Boolean {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().isLineEllipsized(0)
    }

    private fun text(tag: String): String =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    /** The whole screen, after the frame has been drawn. [name] is the file in [SCREENSHOT_DIR]. */
    private fun shoot(name: String) {
        compose.waitForIdle()
        Thread.sleep(FRAME_WAIT_MS)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("no screenshot", bitmap)
        val root = checkNotNull(InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null))
        val directory = File(root, SCREENSHOT_DIR).apply { mkdirs() }
        val file = File(directory, name)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)) }
        assertTrue("$name is empty", file.length() > 0)
        Log.i(LOG_TAG, "wrote ${file.absolutePath} (${file.length()} bytes)")
    }

    private companion object {
        const val LONG_TITLE = "The Late Late Picture Show With a Title Long Enough to Reach the Cell's End"
        const val LOG_TAG = "GuideScreenshot"
        const val SCREENSHOT_DIR = "guide-screenshots"
        const val FOCUSED_CELL = "guide-focused-cell.png"
        const val FOCUSED_MENU = "guide-focused-menu.png"
        const val OFF_AIR = "guide-off-air.png"
        const val RESTING_MENU = "guide-resting-menu.png"
        const val HOST_WIDTH_DP = 960
        const val HOST_HEIGHT_DP = 540
        const val TIMEOUT_MS = 5_000L
        const val PRESS_MS = 50L
        const val PNG_QUALITY = 100
        const val FRAME_WAIT_MS = 500L
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)))
    }
}
