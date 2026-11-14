// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import tv.stationbreak.core.domain.FakeSyncNow
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.ChannelHoldings
import tv.stationbreak.core.domain.lineup.ChannelHoldingsReader
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.GuideOpening
import tv.stationbreak.core.domain.lineup.HeldTitle
import tv.stationbreak.core.domain.lineup.HomeShows
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
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.stationBreak
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemLogo
import tv.stationbreak.core.model.MediaSummary
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.LocalScreenFit
import tv.stationbreak.core.ui.ProgramInfoTags
import tv.stationbreak.core.ui.ScreenFit
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.core.ui.mark.AssetMarkLoader
import tv.stationbreak.core.ui.mark.LocalMarkCache
import tv.stationbreak.core.ui.mark.MarkCache
import tv.stationbreak.core.ui.mark.MarkKind
import tv.stationbreak.core.ui.mark.MarkKindKey
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.GuideViewModel
import java.io.File
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What the channel box is told channel 7 holds: a channel with homes, six of them named and 31 more. */
private object FrameHoldings : ChannelHoldingsReader {
    override suspend fun read(
        id: ChannelId,
        at: Instant,
    ): ChannelHoldings? = if (id == CH7) FRAME_HOLDINGS else null
}

private const val FRAME_HOUR_MS = 3_600_000L
private val FRAME_HOMES =
    listOf("Little Farm", "Robot Factory", "Giant Robot Hour", "Test Series", "Test Series Two", "Late Late Show")
        .mapIndexed { i, name ->
            val id = CanonicalId.parse((i + 1).toString(16).padStart(32, '0'))
            HeldTitle(id, name, (60 - i * 5) * FRAME_HOUR_MS, i < 2)
        }
private val FRAME_KNOWN = HomeShows.Known(37, FRAME_HOMES)
private val FRAME_HOLDINGS = ChannelHoldings(412 * FRAME_HOUR_MS, 37, FRAME_KNOWN, emptyList(), Long.MAX_VALUE)

/** No lineup to watch: these shots never open channel management. */
private object ScreenshotLineup : ChannelRepository {
    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = flowOf(emptyList())

    override fun activeChannels(): Flow<List<ChannelSummary>> = flowOf(emptyList())
}

/**
 * The guide's focus treatment on the API 25 emulator (#566, #569), as PNG files for the proof. The same path as
 * `FirstSyncScreenshotTest`: UiAutomation's screenshot, because Compose's `captureToImage` needs API 26, written under
 * the test app's external files in [SCREENSHOT_DIR]. The lineup, the 960 by 540 host and the keys are the guide screen
 * test's. Dark is the default. The same frames are taken again in Light. The focused cell is a wide episode with
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
    private val fit = mutableStateOf(ScreenFit.Safe)

    // The text size the host draws at (the TV's own font scale), and the longest Sync now line the guide has.
    private val fontScale = mutableFloatStateOf(1f)
    private val syncNow =
        FakeSyncNow(SyncNowState.Failed(SyncFailure.Suspect, changedNothing = true))
    private val openedAt = mutableLongStateOf(1L)
    private var composed = false

    /** The logos the guide draws from; null (initials only) except in the channel marks frames. */
    private var marks: MarkCache? = null

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

    /**
     * Both screen fits (Safe 5%, Edge 1.5%), dark then light, on the focused episode: the surfaces run to the screen's
     * edges in both, the text keeps to the fit's safe area, and the corner picture's hole follows it.
     */
    @Test
    fun theScreenFits() {
        for ((shown, suffix) in listOf(GuideTheme.Dark to "", GuideTheme.Light to "-light")) {
            theme.value = shown
            for (chosen in ScreenFit.entries) {
                fit.value = chosen
                showReady(CH7)
                cell(CH7, 60).assertIsSelected()
                assertTextInsideSafeArea(chosen)
                assertSurfacesReachTheScreenEdge()
                shoot("$FIT_FRAME-${chosen.name.lowercase()}$suffix.png")
            }
        }
    }

    /**
     * The details panel carrying the detail (#723): the item's logo for its title, then the meta line, the tags
     * (rating, resolution, audio, CC, both scores), the progress and times, and a description that takes the height
     * that is left.
     * Dark then Light, where the logo sits on a dark plate. The logo is drawn here (the guide never sees a server).
     */
    @Test
    fun theDetailsPanel() {
        server.itemDetailsResult = ServerResult.Success(richDetails(withLogo = true))
        shootDetails(GuideTheme.Dark, suffix = "")
        shootDetails(GuideTheme.Light, suffix = "-light")
    }

    /** The same panel for an item with no logo: the text title, as before, and the same tags and description. */
    @Test
    fun theDetailsPanelWithoutALogo() {
        server.itemDetailsResult = ServerResult.Success(richDetails(withLogo = false))
        shootDetails(GuideTheme.Dark, suffix = "", frame = DETAILS_TEXT_FRAME)
        shootDetails(GuideTheme.Light, suffix = "-light", frame = DETAILS_TEXT_FRAME)
    }

    /** The channel box (#757): the details panel while focus rests on the channel column, dark then light. */
    @Test
    fun theChannelBox() {
        for ((shown, suffix) in listOf(GuideTheme.Dark to "", GuideTheme.Light to "-light")) {
            theme.value = shown
            showReady(CH7)
            focusTheChannelColumn()
            assertEquals("Test Channel 7", text(GuideTags.CHANNEL_PANEL_NAME))
            awaitHoldings()
            assertEquals("412 hours of programmes (about 17 days) · 37 titles", text(GuideTags.CHANNEL_PANEL_HOURS))
            assertEquals("Home to:", text(GuideTags.CHANNEL_PANEL_LIST_LABEL))
            assertEquals(FRAME_HOMES.joinToString(", ") { it.name } + " + 31 more", text(GuideTags.CHANNEL_PANEL_LIST))
            shoot("$CHANNEL_BOX_FRAME$suffix.png")
        }
    }

    /**
     * Station breaks (rules format 5) on the playing channel: a two-minute break narrower than the compact width (its
     * fill only, no plate text) and a twenty-five-minute break with its whole STATION BREAK plate. Right from the playing episode
     * stops on the short break, then the next programme, then the long break; Left comes back the same way. Each focused
     * break names itself in the panel ("Station break · Up next at ..."). Dark then Light.
     */
    @Test
    fun theStationBreaks() {
        for ((shown, suffix) in listOf(GuideTheme.Dark to "", GuideTheme.Light to "-light")) {
            theme.value = shown
            showReady(CH7, fill = ::fillBreaksLineup)
            cell(CH7, 60).assertIsSelected()
            assertTrue("the short break is ${widthDp(CH7, SHORT_BREAK_MIN)} dp", widthDp(CH7, SHORT_BREAK_MIN) < 48f)
            assertTrue(
                "the short break draws no plate text",
                compose
                    .onAllNodesWithTag(GuideTags.cellText(CH7, T0 + SHORT_BREAK_MIN * MIN), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
            assertTrue("the long break is ${widthDp(CH7, LONG_BREAK_MIN)} dp", widthDp(CH7, LONG_BREAK_MIN) >= 48f)
            assertEquals("STATION BREAK", text(GuideTags.cellText(CH7, T0 + LONG_BREAK_MIN * MIN)))
            assertTrue("the long break's plate is cut", !ellipsized(GuideTags.cellText(CH7, T0 + LONG_BREAK_MIN * MIN)))
            shoot("$BREAKS_FRAME$suffix.png")

            press(Key.DirectionRight)
            cell(CH7, SHORT_BREAK_MIN).assertIsSelected()
            assertTrue(text(GuideTags.PANEL_TITLE).startsWith("Station break · Up next at "))
            shoot("$BREAK_SHORT_FOCUSED_FRAME$suffix.png")

            press(Key.DirectionRight)
            cell(CH7, SHORT_BREAK_MIN + 2).assertIsSelected()
            press(Key.DirectionRight)
            cell(CH7, LONG_BREAK_MIN).assertIsSelected()
            assertTrue(text(GuideTags.PANEL_TITLE).startsWith("Station break · Up next at "))
            shoot("$BREAK_LONG_FOCUSED_FRAME$suffix.png")

            press(Key.DirectionLeft)
            cell(CH7, SHORT_BREAK_MIN + 2).assertIsSelected()
            press(Key.DirectionLeft)
            cell(CH7, SHORT_BREAK_MIN).assertIsSelected()
            press(Key.DirectionLeft)
            cell(CH7, 60).assertIsSelected()
        }
    }

    private fun widthDp(
        channel: ChannelId,
        startMin: Long,
    ): Float {
        val px = cell(channel, startMin).fetchSemanticsNode().boundsInRoot.width
        return px / compose.density.density
    }

    /** The default lineup with channel 7's day carrying a short and a long station break after the playing episode. */
    private fun fillBreaksLineup() {
        fillLineup()
        fake.channel(
            CH7,
            7,
            listOf(
                program(0, 30, 100),
                program(30, 60, 101),
                program(60, SHORT_BREAK_MIN, FOCUSED_ITEM, info = episodeInfo(FOCUSED_ITEM, SERIES, 1, 19)),
                stationBreak(SHORT_BREAK_MIN, SHORT_BREAK_MIN + 2),
                program(SHORT_BREAK_MIN + 2, LONG_BREAK_MIN, 110),
                stationBreak(LONG_BREAK_MIN, LONG_BREAK_MIN + LONG_BREAK_LENGTH_MIN),
            ) +
                (LONG_BREAK_MIN + LONG_BREAK_LENGTH_MIN until 600L step 30).map {
                    program(
                        it,
                        minOf(it + 30, 600),
                        111 + (it / 30).toInt(),
                    )
                },
        )
    }

    /** The holdings come once the focus has rested on the channel: a dwell after the column is reached. */
    private fun awaitHoldings() {
        compose.waitUntil(TIMEOUT_MS) {
            compose
                .onAllNodesWithTag(GuideTags.CHANNEL_PANEL_HOURS, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitForIdle()
    }

    /** Left along the row: the earliest cell is a few presses away, and one more goes to the channel column. */
    private fun focusTheChannelColumn() {
        repeat(MAX_LEFT_PRESSES) {
            if (compose.onAllNodesWithTag(GuideTags.CHANNEL_PANEL).fetchSemanticsNodes().isEmpty()) {
                press(Key.DirectionLeft)
            }
        }
        compose.onNodeWithTag(GuideTags.CHANNEL_PANEL).assertIsDisplayed()
    }

    private fun shootDetails(
        shown: GuideTheme,
        suffix: String,
        frame: String = DETAILS_FRAME,
    ) {
        theme.value = shown
        showReady(CH7, fill = ::fillLineupKeepingDetails)
        val expectsLogo = server.itemDetailsResult.let { it is ServerResult.Success && it.value.logo != null }
        compose.waitUntil(TIMEOUT_MS) {
            val tag = if (expectsLogo) GuideTags.PANEL_LOGO else GuideTags.PANEL_OVERVIEW
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose
                .onAllNodesWithTag(GuideTags.PANEL_OVERVIEW, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(
            listOf("TV-PG", "HD", "5.1", "CC", "Critics 68%", "Audience 6.6"),
            (0..5).map { text("${GuideTags.PANEL_TAG}$it") },
        )
        compose.onNodeWithTag(GuideTags.PANEL_TAGS, useUnmergedTree = true).assertIsDisplayed()
        val panel = compose.onNodeWithTag(GuideTags.PANEL).fetchSemanticsNode().boundsInRoot
        val overview =
            compose
                .onNodeWithTag(GuideTags.PANEL_OVERVIEW, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            "the description ends at ${overview.bottom}, past the panel's ${panel.bottom}",
            overview.bottom <= panel.bottom,
        )
        shoot("$frame$suffix.png")
    }

    private fun richDetails(withLogo: Boolean) =
        ItemDetails(
            itemOf(FOCUSED_ITEM),
            LONG_OVERVIEW,
            YEAR,
            listOf("Animation", "Family"),
            RUNTIME_MIN.minutes,
            officialRating = "TV-PG",
            criticRating = 68,
            communityRating = 6.6,
            logo = if (withLogo) ItemLogo(ItemId("series-art"), "frame-logo") else null,
            media = MediaSummary(1920, 1080, 6, hasSubtitles = true),
        )

    // The same lineup, with the details this test set rather than the default ones.
    private fun fillLineupKeepingDetails() {
        val keep = server.itemDetailsResult
        fillLineup()
        server.itemDetailsResult = keep
    }

    /** Light lettering with an orange outline of its own: a stand-in for a real logo, which is usually light. */
    private fun logoArt(): ImageBitmap {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF28A2E.toInt()
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 64f
            }
        val label = "TEST SERIES"
        val bitmap = Bitmap.createBitmap(paint.measureText(label).toInt() + 8, 96, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(label, 4f, 70f, paint)
        return bitmap.asImageBitmap()
    }

    /** The Settings panel with its five rows: the last (Even out volume) must end above the grid's clock strip. */
    @Test
    fun theSettingsPanel() = shootSettings(GuideTheme.Dark, suffix = "")

    // One test a theme: the panel's state is the activity's, and a second pass would start on the open panel.
    @Test
    fun theSettingsPanelLight() = shootSettings(GuideTheme.Light, suffix = "-light")

    // All five rows clear of the grid at both screen fits and at the font scales a TV is set to, with the Sync now
    // line as long as it gets (and room for it on two lines).
    @Test
    fun theSettingsPanelFitsAtSafeAndFontScale1() = shootSettings(GuideTheme.Dark, "-safe-1", ScreenFit.Safe, 1f)

    @Test
    fun theSettingsPanelFitsAtSafeAndFontScale115() = shootSettings(GuideTheme.Dark, "-safe-115", ScreenFit.Safe, 1.15f)

    @Test
    fun theSettingsPanelFitsAtEdgeAndFontScale1() = shootSettings(GuideTheme.Dark, "-edge-1", ScreenFit.Edge, 1f)

    @Test
    fun theSettingsPanelFitsAtEdgeAndFontScale115() = shootSettings(GuideTheme.Dark, "-edge-115", ScreenFit.Edge, 1.15f)

    private fun shootSettings(
        shown: GuideTheme,
        suffix: String,
        chosen: ScreenFit = ScreenFit.Safe,
        scale: Float = 1f,
    ) {
        theme.value = shown
        fit.value = chosen
        fontScale.floatValue = scale
        showReady(CH7)
        // Back opens the menu sheet, Down is Settings, and Select opens the panel (the sheet steps aside for it).
        back()
        channels().assertIsSelected()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(GuideTags.FIT_EDGE).fetchSemanticsNodes().isNotEmpty()
        }
        val gridTop = bounds(GuideTags.GRID).top
        // The longest Sync now line the guide has is one line at this width, so a line more is added to each row's
        // bottom: the fit holds when the line wraps too.
        val status = bounds(GuideTags.SETTINGS_SYNC_STATUS)
        val wrapExtra = if (lineCount(GuideTags.SETTINGS_SYNC_STATUS) >= 2) 0f else status.height
        for (tag in PANEL_ROW_TAGS) {
            val rect = bounds(tag)
            assertTrue("$tag was squeezed out of the panel ($rect)", rect.height > 0f)
            val bottom = rect.bottom + wrapExtra
            assertTrue("$tag (bottom $bottom) runs into the grid (top $gridTop)", bottom <= gridTop)
        }
        val picked = if (chosen == ScreenFit.Safe) GuideTags.FIT_SAFE else GuideTags.FIT_EDGE
        compose.onNodeWithTag(picked).assertIsSelected()
        shoot("$SETTINGS_FRAME$suffix.png")
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun lineCount(tag: String): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single().lineCount
    }

    // The panel's title starts at the fit's safe left edge, not at the screen's.
    private fun assertTextInsideSafeArea(chosen: ScreenFit) {
        val screen = compose.onNodeWithTag(GuideTags.SCREEN).fetchSemanticsNode().boundsInRoot
        val title =
            compose.onNodeWithTag(GuideTags.PANEL_TITLE, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val safeLeftPx = screen.width * chosen.overscan
        assertEquals("the panel title keeps to the safe area", safeLeftPx, title.left - screen.left, TITLE_SLACK_PX)
    }

    // A resting cell of the last row draws to the screen's right edge, where the page is bare below the grid.
    private fun assertSurfacesReachTheScreenEdge() {
        val screen = compose.onNodeWithTag(GuideTags.SCREEN).fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag(GuideTags.cell(CH9, T0)).fetchSemanticsNode().boundsInRoot
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val x = bitmap.width - EDGE_PROBE_PX
        val onCell = bitmap.getPixel(x, (row.center.y / screen.height * bitmap.height).toInt())
        val belowGrid = bitmap.getPixel(x, ((row.bottom + BELOW_GRID_PX) / screen.height * bitmap.height).toInt())
        val diff = listOf(Color.red(onCell) - Color.red(belowGrid), Color.green(onCell) - Color.green(belowGrid))
        assertTrue("no surface at the screen's right edge: $diff", diff.any { kotlin.math.abs(it) >= SURFACE_DIFF })
    }

    /** The channel marks: real logos from the app's assets beside an initials tile and a long name, dark then light. */
    @Suppress("InjectDispatcher") // The real cache loads on Dispatchers.IO, as in the app.
    @Test
    fun theChannelMarks() {
        marks =
            MarkCache(
                AssetMarkLoader(targetAssets(), targetDensity()),
                CoroutineScope(SupervisorJob()),
                Dispatchers.IO,
            )
        for ((shown, suffix) in listOf(GuideTheme.Dark to "", GuideTheme.Light to "-light")) {
            theme.value = shown
            showReady(MARK_FARM, ::fillMarksLineup)
            awaitMarks()
            shoot("$MARKS_FRAME$suffix.png")
        }
    }

    private fun targetDensity() =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext.resources.displayMetrics.density

    private fun targetAssets() = InstrumentationRegistry.getInstrumentation().targetContext.assets

    /**
     * Four rows with generic names and real catalog keys: two starters and a third with a three-digit number and a
     * name too long for its column, and a custom channel that has no key and so keeps its initials.
     */
    private fun fillMarksLineup() {
        val rows =
            listOf(
                Triple(MARK_FARM, 4, "Test Channel Farm") to "little-farm",
                Triple(MARK_ROBOTS, 28, "Test Channel Robots") to "robot-factory",
                Triple(MARK_LONG, 123, "Marks Test Long Channel Name Here") to "giant-robot-hour",
                Triple(MARK_CUSTOM, 620, "Test Channel Custom") to null,
            )
        for ((row, key) in rows) {
            val (id, number, name) = row
            val blocks = (0L until 600L step 60).map { program(it, it + 60, number * 10 + (it / 60).toInt()) }
            fake.channels[id] = FakeSchedule.Entry(number, name, blocks = blocks, recipeKey = key)
        }
    }

    /** Waits for the three logos to be drawn, and checks the custom channel still shows its initials. */
    private fun awaitMarks() {
        val starters = listOf(MARK_FARM, MARK_ROBOTS, MARK_LONG)
        compose.waitUntil(TIMEOUT_MS) { starters.all { markKind(it) == MarkKind.Logo } }
        assertEquals(MarkKind.Initials, markKind(MARK_CUSTOM))
        compose.waitForIdle()
    }

    private fun markKind(channel: ChannelId): MarkKind? =
        compose
            .onAllNodesWithTag(GuideTags.channelMark(channel))
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(MarkKindKey)

    /** Focused cell and the menu sheet over it (#756), then the focused movie and (dark only) the off-air cell. */
    private fun shootTheme(
        shown: GuideTheme,
        suffix: String,
    ) {
        theme.value = shown
        showReady(CH7)
        cell(CH7, 60).assertIsSelected()
        compose.onNodeWithTag(GuideTags.MENU_SHEET).assertDoesNotExist()
        assertEquals(SERIES, text(GuideTags.cellText(CH7, T0 + 60 * MIN)))
        assertEquals("S1 E19", text(GuideTags.cellSecondLine(CH7, T0 + 60 * MIN)))
        assertEquals(OTHER_SERIES, text(GuideTags.cellText(CH5, T0 + 60 * MIN)))
        assertEquals("S2 E5", text(GuideTags.cellSecondLine(CH5, T0 + 60 * MIN)))
        val longTitle = GuideTags.cellText(ChannelId("ch-3"), T0 + 60 * MIN)
        assertEquals(LONG_TITLE, text(longTitle))
        assertTrue("the long title ends inside the cell", ellipsized(longTitle))
        shoot("$FOCUSED_CELL$suffix.png")

        // Back on the playing cell opens the menu sheet (#756) over the guide, on Channels.
        back()
        channels().assertIsSelected()
        compose.onNodeWithTag(GuideTags.MENU_SHEET).assertIsDisplayed()
        shoot("$MENU_SHEET_FRAME$suffix.png")

        // The Menu key closes it (Back would leave live TV from a sheet Back opened); the focus is on the cell again.
        press(Key.Menu)
        compose.onNodeWithTag(GuideTags.MENU_SHEET).assertDoesNotExist()
        cell(CH7, 60).assertIsSelected()

        shootFocusedMovie(suffix)
        if (shown != GuideTheme.Dark) return
        press(Key.DirectionRight)
        cell(CH9, 150).assertIsSelected()
        assertEquals("OFF AIR", text(GuideTags.cellText(CH9, T0 + 150 * MIN)))
        assertTrue(text(GuideTags.PANEL_TITLE).startsWith("Off air until "))
        shoot(OFF_AIR)
    }

    /** Down from the episode to channel 9's movie on now: no episode line, so the panel has room for the meta line. */
    private fun shootFocusedMovie(suffix: String) {
        press(Key.DirectionDown)
        cell(CH9, 0).assertIsSelected()
        awaitMeta()
        compose.onNodeWithTag(GuideTags.PANEL_META, useUnmergedTree = true).assertIsDisplayed()
        shoot("$FOCUSED_MOVIE$suffix.png")
    }

    /** The guide screen test's lineup: channel 9's slate starts two and a half hours in. */
    private fun showReady(
        current: ChannelId,
        fill: () -> Unit = ::fillLineup,
    ) {
        fill()
        if (!composed) {
            signIn()
            val cache = ProgramDetailsCache(server, sessions, CoroutineScope(SupervisorJob()))
            vm =
                GuideViewModel(
                    fake,
                    cache,
                    clock,
                    pool.asCoroutineDispatcher(),
                    ScreenshotLineup,
                    sync = syncNow,
                    logos = { logoArt() },
                    holdings = FrameHoldings,
                )
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
            CompositionLocalProvider(
                LocalScreenFit provides fit.value,
                LocalGuideColors provides theme.value.colors,
                LocalMarkCache provides marks,
                LocalDensity provides Density(LocalDensity.current.density, fontScale.floatValue),
            ) {
                Box(Modifier.requiredSize(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp).focusable()) {
                    GuideRoot(
                        vm,
                        GuideOpening(current, null, openedAt.longValue),
                        pictureHole = holeFor(fit.value),
                        onTune = {},
                        channels = { _, _, _, _ -> },
                    )
                }
            }
        }
    }

    private fun holeFor(chosen: ScreenFit) =
        ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp), chosen))

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
        // A run started with `am instrument -e sharedCopy false` (a TV where the shell installs the test package for
        // one user) keeps only the package's own copy, and the shell pulls it from there.
        val sharedCopy = InstrumentationRegistry.getArguments().getString("sharedCopy") != "false"
        if (sharedCopy) grantPublicStorage(context.packageName)
        for (directory in if (sharedCopy) listOf(external, shared) else listOf(external)) {
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
        const val LONG_OVERVIEW =
            "Four astronauts returning from a first mission to Mars enter a time warp and crash on a far-future " +
                "Earth " +
                "devastated by atomic war. At first unaware of where they are, but finding the surface overrun by " +
                "mutants, they discover an underground civilisation whose men have grown timid and whose leaders are " +
                "suspicious of the surface-dwellers and what they might have brought with them from the sky."
        const val YEAR = 2018
        const val RUNTIME_MIN = 22
        const val LONG_TITLE = "The Late Late Picture Show With a Title Long Enough to Reach the Cell's End"
        const val LOG_TAG = "GuideScreenshot"
        const val SCREENSHOT_DIR = "guide-screenshots"
        const val FOCUSED_CELL = "guide-focused-cell"
        const val FOCUSED_MOVIE = "guide-focused-movie"
        const val MENU_SHEET_FRAME = "guide-menu-sheet"
        const val OFF_AIR = "guide-off-air.png"
        const val MARKS_FRAME = "guide-marks"
        const val DETAILS_FRAME = "guide-details"
        const val CHANNEL_BOX_FRAME = "guide-channel-box"
        const val MAX_LEFT_PRESSES = 6
        const val DETAILS_TEXT_FRAME = "guide-details-text"
        val MARK_FARM = ChannelId("ch-marks-farm")
        val MARK_ROBOTS = ChannelId("ch-marks-robots")
        val MARK_LONG = ChannelId("ch-marks-long")
        val MARK_CUSTOM = ChannelId("ch-marks-custom")
        const val HOST_WIDTH_DP = 960
        const val HOST_HEIGHT_DP = 540
        const val TIMEOUT_MS = 5_000L
        const val PRESS_MS = 50L
        const val PNG_QUALITY = 100
        const val FRAME_WAIT_MS = 500L
        const val FIT_FRAME = "guide-fit"
        const val BREAKS_FRAME = "guide-breaks"
        const val BREAK_SHORT_FOCUSED_FRAME = "guide-break-short-focused"
        const val BREAK_LONG_FOCUSED_FRAME = "guide-break-long-focused"
        const val SHORT_BREAK_MIN = 88L
        const val LONG_BREAK_MIN = 120L
        const val LONG_BREAK_LENGTH_MIN = 25L
        val PANEL_ROW_TAGS =
            listOf(
                GuideTags.THEME_DARK,
                GuideTags.FIT_EDGE,
                GuideTags.SETTINGS_LIBRARIES,
                GuideTags.SETTINGS_SYNC_NOW,
                GuideTags.SETTINGS_SYNC_STATUS,
                GuideTags.LEVELLING_ON,
            )
        const val SETTINGS_FRAME = "guide-settings"
        const val TITLE_SLACK_PX = 2f
        const val EDGE_PROBE_PX = 3
        const val BELOW_GRID_PX = 12f
        const val SURFACE_DIFF = 6
    }
}
