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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
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
import org.junit.Assert.assertTrue
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
 * goes, the menu row on Settings, and the Settings sheet (#336): its choice rows changed in place, the Libraries slot
 * over the guide, the Update library row below Libraries (Part 5), and the sign-out slot over the sheet.
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

    // The ways out the slots were given, and the keys that got past the guide to its host.
    @Volatile
    private var done: (() -> Unit)? = null

    @Volatile
    private var stayed: (() -> Unit)? = null
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
                        signOut = { onDone -> SignOutSlot(onDone) },
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

    // The sign-out question, standing in for feature:settings': it never takes the key focus either.
    @Composable
    private fun SignOutSlot(onDone: () -> Unit) {
        LaunchedEffect(Unit) { stayed = onDone }
        Box(Modifier.fillMaxSize().testTag(SIGN_OUT_SLOT))
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

    // The value a choice row says it holds, as a screen reader is told it.
    private fun awaitValue(
        tag: String,
        value: String,
    ) {
        val says = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
        compose.waitUntil(TIMEOUT_MS) { runCatching { compose.onNodeWithTag(tag).assert(says) }.isSuccess }
    }

    private fun openSettings() {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(GuideTags.SETTINGS_SHEET).fetchSemanticsNodes().isNotEmpty()
        }
        awaitSelected(GuideTags.SETTINGS_APPEARANCE)
    }

    private fun sheetOpen() = compose.onAllNodesWithTag(GuideTags.SETTINGS_SHEET).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun goneSelection_lineShows_settingsOpensLibraries_slotCoversTheGuide_doneReturnsToThePanel() {
        showGone()

        // The line stands where the grid goes, and the menu row has the focus, on Settings.
        compose.onNodeWithTag(GuideTags.NO_LIBRARIES).assertExists()
        compose.onNodeWithTag(GuideTags.GRID).assertDoesNotExist()
        awaitSelected(GuideTags.SETTINGS_BUTTON)

        // Select opens the sheet on Appearance; Down is its Screen fit row (Safe, the default), and Libraries is past
        // When changing channel and Even out volume.
        openSettings()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitSelected(GuideTags.SETTINGS_SCREEN_FIT)
        awaitValue(GuideTags.SETTINGS_SCREEN_FIT, "Safe")
        repeat(ROWS_TO_LIBRARIES - 1) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitSelected(GuideTags.SETTINGS_LIBRARIES)

        // Select draws the slot over the guide, which leaves the semantics tree.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) { done != null }
        compose.onNodeWithTag(SLOT).assertExists()
        compose.onNodeWithTag(GuideTags.SCREEN, useUnmergedTree = true).assertDoesNotExist()

        // Keys while the slot covers the guide: none moves the guide or the sheet, and none gets past the guide.
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

        // The slot's onDone brings the sheet back, still on Libraries, with the line where the grid goes.
        compose.runOnUiThread { done?.invoke() }
        compose.waitForIdle()
        compose.onNodeWithTag(SLOT).assertDoesNotExist()
        awaitSelected(GuideTags.SETTINGS_LIBRARIES)
        compose.onNodeWithTag(GuideTags.NO_LIBRARIES).assertExists()

        // Down is Update library. Select starts one update, the sheet stays open, and the line under the row says it
        // is updating; a held Select starts no other.
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

    // A choice row (#336): Left and Right change its value in place, OK moves it on to the next one, and none of them
    // closes the sheet; the setting the host holds follows. Back closes the sheet.
    @Test
    fun evenOutVolumeRow_changesInPlace_okMovesItOn_andOnlyBackCloses() {
        showGone()
        openSettings()
        repeat(ROWS_TO_LEVELLING) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitSelected(GuideTags.SETTINGS_LEVELLING)
        // On is the default.
        awaitValue(GuideTags.SETTINGS_LEVELLING, "On")
        assertEquals(true, levelling.value)

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitValue(GuideTags.SETTINGS_LEVELLING, "Off")
        assertEquals(false, levelling.value)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitValue(GuideTags.SETTINGS_LEVELLING, "On")
        assertEquals(true, levelling.value)

        // OK flips it, and flips it back: the sheet stays open, on the row.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitValue(GuideTags.SETTINGS_LEVELLING, "Off")
        assertEquals(false, levelling.value)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitValue(GuideTags.SETTINGS_LEVELLING, "On")
        assertEquals(true, levelling.value)
        assertTrue("OK closed the sheet", sheetOpen())
        awaitSelected(GuideTags.SETTINGS_LEVELLING)

        // Back closes it, keeping the value, and the menu sheet is back on Settings.
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(TIMEOUT_MS) { !sheetOpen() }
        assertEquals(true, levelling.value)
        awaitSelected(GuideTags.SETTINGS_BUTTON)
    }

    // What a screen reader is told (#336): one node a row; a choice says its value as its state and has no button role,
    // a link and an action are buttons, and only the row under the D-pad is selected. The flags are headings.
    @Test
    fun theSheetsRowsSayWhatTheyAre() {
        showGone()
        openSettings()
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val noRole = SemanticsMatcher.keyNotDefined(SemanticsProperties.Role)
        val choices =
            mapOf(
                GuideTags.SETTINGS_APPEARANCE to ("Appearance" to "Dark"),
                GuideTags.SETTINGS_SCREEN_FIT to ("Screen fit" to "Safe"),
                GuideTags.SETTINGS_CHANNEL_CHANGE to ("When changing channel" to "Stand-by card"),
                GuideTags.SETTINGS_LEVELLING to ("Even out volume" to "On"),
            )
        for ((tag, labelAndValue) in choices) {
            compose
                .onNodeWithTag(tag)
                .assertContentDescriptionEquals(labelAndValue.first)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, labelAndValue.second))
                .assert(noRole)
        }
        val actions =
            mapOf(
                GuideTags.SETTINGS_LIBRARIES to "Libraries",
                GuideTags.SETTINGS_SYNC_NOW to "Update library",
                GuideTags.SETTINGS_SIGN_OUT to "Sign out",
            )
        for ((tag, label) in actions) {
            compose
                .onNodeWithTag(tag)
                .assertContentDescriptionEquals(label)
                .assert(button)
                .assertIsNotSelected()
        }
        compose.onNodeWithTag(GuideTags.SETTINGS_APPEARANCE).assertIsSelected()
        for (tag in (choices.keys - GuideTags.SETTINGS_APPEARANCE)) compose.onNodeWithTag(tag).assertIsNotSelected()
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onNodeWithTag(GuideTags.SETTINGS_TITLE).assert(heading)

        // The hint row says what the keys do on the row: a choice, then a link.
        compose.onNodeWithTag(GuideTags.SETTINGS_HINT).assertTextEquals("Left or right Change   OK Next   Back Close")
        repeat(ROWS_TO_LIBRARIES) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitSelected(GuideTags.SETTINGS_LIBRARIES)
        compose.onNodeWithTag(GuideTags.SETTINGS_HINT).assertTextEquals("OK Open   Back Close")
        compose.onNodeWithTag(GuideTags.SETTINGS_APPEARANCE).assertIsNotSelected()
    }

    // One announcing path (#336): the sheet's root is the one live region in it, and says a value change and the
    // Update library line; the line itself is not a live region, so nothing is said twice.
    @Test
    fun theSheetHasOneLiveRegion_andItSaysValuesAndTheUpdateLine() {
        showGone()
        openSettings()
        val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

        // Every live-region node in the tree, tagged or not.
        fun liveNodes() = compose.onAllNodes(live, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, liveNodes().size)
        assertEquals(GuideTags.SETTINGS_SHEET, liveNodes().single().config.getOrNull(SemanticsProperties.TestTag))
        compose.onNodeWithTag(GuideTags.SETTINGS_SHEET).assertContentDescriptionEquals("Appearance, Dark")

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(TIMEOUT_MS) {
            runCatching {
                compose.onNodeWithTag(GuideTags.SETTINGS_SHEET).assertContentDescriptionEquals("Appearance, Light")
            }.isSuccess
        }

        repeat(SettingsRow.SyncNow.ordinal) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitSelected(GuideTags.SETTINGS_SYNC_NOW)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(GuideTags.SETTINGS_SYNC_STATUS).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(GuideTags.SETTINGS_SHEET).assertContentDescriptionEquals("Update library, Updating…")
        val why = "the Update library line is not a second live region"
        assertEquals(why, 1, liveNodes().size)
    }

    // Sign out (#336) asks first: OK on its row shows the sign-out slot over the sheet, which holds every key, and
    // staying brings the sheet back on Sign out.
    @Test
    fun signOutRow_showsTheQuestionOverTheSheet_andStayingReturnsToIt() {
        showGone()
        openSettings()
        repeat(SettingsRow.entries.size) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitSelected(GuideTags.SETTINGS_SIGN_OUT)
        compose.onNodeWithTag(GuideTags.SETTINGS_HINT).assertTextEquals("OK Sign out   Back Close")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) { stayed != null }
        compose.onNodeWithTag(SIGN_OUT_SLOT).assertExists()

        hostKeys.clear()
        for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK)) press(key)
        assertEquals("keys that got past the guide", emptyList<Key>(), hostKeys.toList())
        compose.onNodeWithTag(SIGN_OUT_SLOT).assertExists()

        compose.runOnUiThread { stayed?.invoke() }
        compose.waitForIdle()
        compose.onNodeWithTag(SIGN_OUT_SLOT).assertDoesNotExist()
        awaitSelected(GuideTags.SETTINGS_SIGN_OUT)
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
    // the sheet is left open on Libraries.
    @Test
    fun librariesSlot_pausesTheGuide_andOneReloadFollowsItsClosing() {
        val lineup = MutableStateFlow(listOf(summary(CH5, 5), summary(CH7, 7)))
        showGone(
            object : ChannelRepository {
                override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = lineup

                override fun activeChannels(): Flow<List<ChannelSummary>> = lineup
            },
        )
        openSettings()
        repeat(ROWS_TO_LIBRARIES) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
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
        const val SIGN_OUT_SLOT = "test:signOutSlot"
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        const val TIMEOUT_MS = 5_000L

        // Down presses from Appearance: to Even out volume (Screen fit, When changing channel, it), and to Libraries.
        const val ROWS_TO_LEVELLING = 3
        const val ROWS_TO_LIBRARIES = 4
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
    }
}
