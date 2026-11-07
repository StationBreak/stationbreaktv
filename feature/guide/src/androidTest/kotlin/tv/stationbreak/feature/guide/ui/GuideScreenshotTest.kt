// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Environment
import android.os.ParcelFileDescriptor
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import kotlinx.coroutines.runBlocking
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
import tv.stationbreak.core.domain.ServerResult
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
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.episodeInfo
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.info
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.GuideViewModel
import java.io.File
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
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
 * test's. Dark is the default. The same three frames are taken again in Light. The focused cell is a wide episode with
 * its "S1 E19" line and the panel's year, runtime and genres; a long movie title elsewhere is cut at its cell's end. A
 * focused slate reads OFF AIR and the panel still says when it returns.
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

    /** One content for both themes: the rule's activity accepts setContent once. */
    private val theme = mutableStateOf(GuideTheme.Dark)
    private val openedAt = mutableLongStateOf(1L)
    private var composed = false

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.close()
        pool.shutdownNow()
    }

    @Test
    fun theFocusStates() {
        shootTheme(GuideTheme.Dark, suffix = "")
        shootTheme(GuideTheme.Light, suffix = "-light")
    }

    /** Focused cell, focused menu and resting menu, then (dark only) the off-air cell. */
    private fun shootTheme(
        shown: GuideTheme,
        suffix: String,
    ) {
        theme.value = shown
        showReady(CH7)
        cell(CH7, 60).assertIsSelected()
        channels().assertIsNotSelected()
        assertEquals(SERIES, text(GuideTags.cellText(CH7, T0 + 60 * MIN)))
        assertEquals("S1 E19", text(GuideTags.cellSecondLine(CH7, T0 + 60 * MIN)))
        assertEquals(OTHER_SERIES, text(GuideTags.cellText(CH5, T0 + 60 * MIN)))
        assertEquals("S2 E5", text(GuideTags.cellSecondLine(CH5, T0 + 60 * MIN)))
        val longTitle = GuideTags.cellText(ChannelId("ch-3"), T0 + 60 * MIN)
        assertEquals(LONG_TITLE, text(longTitle))
        assertTrue("the long title ends inside the cell", ellipsized(longTitle))
        awaitMeta()
        shoot("$FOCUSED_CELL$suffix.png")

        back()
        channels().assertIsSelected()
        shoot("$FOCUSED_MENU$suffix.png")

        press(Key.DirectionDown)
        cell(CH7, 60).assertIsSelected()
        channels().assertIsNotSelected()
        awaitMeta()
        shoot("$RESTING_MENU$suffix.png")

        if (shown != GuideTheme.Dark) return
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        cell(CH9, 150).assertIsSelected()
        assertEquals("OFF AIR", text(GuideTags.cellText(CH9, T0 + 150 * MIN)))
        assertTrue(text(GuideTags.PANEL_TITLE).startsWith("Off air until "))
        shoot(OFF_AIR)
    }

    /** The guide screen test's lineup: channel 9's slate starts two and a half hours in. */
    private fun showReady(current: ChannelId) {
        fillLineup()
        if (!composed) {
            signIn()
            val cache = ProgramDetailsCache(server, sessions, CoroutineScope(SupervisorJob()))
            vm = GuideViewModel(fake, cache, clock, pool.asCoroutineDispatcher(), ScreenshotLineup)
            compose.setContent { GuideHost(current) }
            composed = true
        } else {
            // A new opening restarts the session on the playing cell, in the theme set above. Wait for that cell:
            // the previous session is still Ready until the recomposition applies.
            openedAt.longValue += 1
        }
        awaitPlaying(current)
    }

    /** The details cache reads nothing while nobody is signed in, so the panel's meta line needs a session. */
    private fun signIn() {
        val address = ServerAddress(Scheme.Https, "media.example.com", 443)
        val info = ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0))
        runBlocking { sessions.save(Session(UserId("u1"), AccessToken("tok-0123456789"), info, address, null, true)) }
    }

    @Composable
    private fun GuideHost(current: ChannelId) {
        BackHandler { }
        StationBreakTheme {
            CompositionLocalProvider(LocalGuideColors provides theme.value.colors) {
                Box(Modifier.requiredSize(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp).focusable()) {
                    GuideRoot(
                        vm,
                        GuideOpening(current, null, openedAt.longValue),
                        pictureHole = HOLE,
                        onTune = {},
                        channels = { _, _, _, _ -> },
                    )
                }
            }
        }
    }

    private fun fillLineup() {
        // Four rows. Channel 3: a long movie title at 30 minutes (cut at the cell's end), mixed lengths around it.
        fake.channel(
            ChannelId("ch-3"),
            3,
            listOf(program(0, 30, 400), program(30, 60, 401), program(60, 90, 402, info = info(402, LONG_TITLE))) +
                listOf(program(90, 180, 403)) +
                (180L until 600L step 60).map { program(it, it + 60, 404 + (it / 60).toInt()) },
        )
        // Channel 5: a resting two-line episode of another series in view, then movies.
        fake.channel(
            CH5,
            5,
            listOf(
                program(0, 60, 200),
                program(60, 120, 201, info = episodeInfo(201, OTHER_SERIES, 2, 5)),
                program(120, 210, 202),
            ) + (210L until 600L step 60).map { program(it, minOf(it + 60, 600), 203 + (it / 60).toInt()) },
        )
        // Channel 7 (the playing channel, not the first row): the focused cell is a wide episode with numbers.
        fake.channel(
            CH7,
            7,
            listOf(
                program(0, 30, 100),
                program(30, 60, 101),
                program(60, 120, FOCUSED_ITEM, info = episodeInfo(FOCUSED_ITEM, SERIES, 1, 19)),
            ) + (120L until 600L step 30).map { program(it, it + 30, 100 + (it / 30).toInt()) },
        )
        fake.channel(CH9, 9, listOf(program(0, 150, 300), slate(150, 210), program(210, 600, 301)))
        server.itemDetailsResult =
            ServerResult.Success(
                ItemDetails(itemOf(FOCUSED_ITEM), OVERVIEW, YEAR, listOf("Animation", "Family"), RUNTIME_MIN.minutes),
            )
    }

    private fun awaitPlaying(current: ChannelId) {
        val playing = T0 + 60 * MIN
        compose.waitUntil(TIMEOUT_MS) {
            val snap = vm.snapshot.value
            val focus = snap.focus
            snap.state is GuideState.Ready &&
                snap.menu == null &&
                focus != null &&
                focus.channel == current &&
                focus.cellStartMs == playing
        }
        compose.waitForIdle()
        compose.awaitWindowFocus()
    }

    /** The panel's year, runtime and genres line comes with the details fetch, a dwell after the focus rests. */
    private fun awaitMeta() {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(GuideTags.PANEL_META, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("2018 · 22 min · Animation, Family", text(GuideTags.PANEL_META))
        compose.waitForIdle()
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
        val context = InstrumentationRegistry.getInstrumentation().context
        // The package's own external files, as the first-sync proof does. connectedAndroidTest then uninstalls the
        // test package and that directory goes with it, so a second copy is written on shared storage, which the
        // shell can still pull.
        val external = File(checkNotNull(context.getExternalFilesDir(null)), SCREENSHOT_DIR)
        val shared = File(Environment.getExternalStorageDirectory(), SCREENSHOT_DIR)
        grantPublicStorage(context.packageName)
        for (directory in listOf(external, shared)) {
            assertTrue("could not create $directory", directory.mkdirs() || directory.isDirectory)
            val file = File(directory, name)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)) }
            assertTrue("$name is empty", file.length() > 0)
            Log.i(LOG_TAG, "wrote ${file.absolutePath} (${file.length()} bytes)")
        }
    }

    /** Shell grant: a target of 34 does not receive this at install, and API 25 has no UiAutomation grant. */
    private fun grantPublicStorage(packageName: String) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val grantedAlready =
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        if (grantedAlready) return
        val granted =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant $packageName ${Manifest.permission.WRITE_EXTERNAL_STORAGE}",
            )
        ParcelFileDescriptor.AutoCloseInputStream(granted).use { it.readBytes() }
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE),
        )
    }

    private companion object {
        const val SERIES = "Test Series"
        const val OTHER_SERIES = "Test Series Two"
        const val FOCUSED_ITEM = 102
        const val OVERVIEW = "A test episode about nothing in particular, long enough to fill a line of the panel."
        const val YEAR = 2018
        const val RUNTIME_MIN = 22
        const val LONG_TITLE = "The Late Late Picture Show With a Title Long Enough to Reach the Cell's End"
        const val LOG_TAG = "GuideScreenshot"
        const val SCREENSHOT_DIR = "guide-screenshots"
        const val FOCUSED_CELL = "guide-focused-cell"
        const val FOCUSED_MENU = "guide-focused-menu"
        const val OFF_AIR = "guide-off-air.png"
        const val RESTING_MENU = "guide-resting-menu"
        const val HOST_WIDTH_DP = 960
        const val HOST_HEIGHT_DP = 540
        const val TIMEOUT_MS = 5_000L
        const val PRESS_MS = 50L
        const val PNG_QUALITY = 100
        const val FRAME_WAIT_MS = 500L
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)))
    }
}
