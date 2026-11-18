// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
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
import org.junit.Assert.assertFalse
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

private object NoLineupChanges : ChannelRepository {
    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = flowOf(emptyList())

    override fun activeChannels(): Flow<List<ChannelSummary>> = flowOf(emptyList())
}

/** The first visit's note (#94): in the panel's place until the first key, which it never swallows. */
@RunWith(AndroidJUnit4::class)
class GuideFirstVisitUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeSchedule()
    private val server = FakeMediaServer()
    private val sessions = SessionRepository(InMemorySecretStore(), server)
    private val pool = Executors.newSingleThreadExecutor()
    private lateinit var vm: GuideViewModel
    private val tuned = CopyOnWriteArrayList<ChannelId>()
    private var exits = 0
    private val openedAt = System.nanoTime()

    private val clock =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(T0 + 65 * MIN)
        }

    private fun show(firstVisit: Boolean = true) {
        fake.channel(CH5, 5, (0L until 600L step 60).map { program(it, it + 60, 200 + (it / 60).toInt()) })
        fake.channel(CH7, 7, (0L until 600L step 30).map { program(it, it + 30, 100 + (it / 30).toInt()) })
        val cache = ProgramDetailsCache(server, sessions, CoroutineScope(SupervisorJob()))
        vm = GuideViewModel(fake, cache, clock, pool.asCoroutineDispatcher(), NoLineupChanges)
        compose.setContent {
            StationBreakTheme {
                BackHandler { exits++ }
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).focusable()) {
                    GuideRoot(
                        vm,
                        GuideOpening(CH5, null, openedAt, firstVisit),
                        pictureHole = HOLE,
                        onTune = { tuned += it },
                        channels = { _, _, _, _ -> },
                    )
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value.state is GuideState.Ready }
        compose.waitForIdle()
        compose.awaitWindowFocus()
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

    private fun back() {
        compose.awaitWindowFocus()
        val at = SystemClock.uptimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeySync(KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        instrumentation.sendKeySync(KeyEvent(at, at + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        compose.waitForIdle()
    }

    private fun note() = compose.onNodeWithTag(GuideTags.FIRST_VISIT)

    private fun cell(
        startMin: Long,
        channel: ChannelId = CH5,
    ) = compose.onNodeWithTag(GuideTags.cell(channel, T0 + startMin * MIN))

    private fun text(tag: String): String =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    @Test
    fun theNoteStandsInThePanelsPlaceWithTitleAndBody() {
        show()
        note().assertExists()
        assertEquals("You’re on the air.", text(GuideTags.PANEL_TITLE))
        assertEquals(
            "Here are your channels. Pick one to watch, or press OK to keep watching this one.",
            text(GuideTags.FIRST_VISIT_BODY),
        )
        compose.onNodeWithTag(GuideTags.PANEL).assertDoesNotExist()
        // The highlight is on the playing channel.
        cell(60).assertIsSelected()
    }

    @Test
    fun nothingElseMovesWhenTheNoteIsDismissed() {
        show()
        val withNote = compose.onNodeWithTag(GuideTags.GRID).getBoundsInRoot()
        val clockWith = compose.onNodeWithTag(GuideTags.CLOCK).getBoundsInRoot()
        val noteBounds = note().getBoundsInRoot()
        press(Key.DirectionRight)
        note().assertDoesNotExist()
        assertEquals(withNote, compose.onNodeWithTag(GuideTags.GRID).getBoundsInRoot())
        assertEquals(clockWith, compose.onNodeWithTag(GuideTags.CLOCK).getBoundsInRoot())
        assertEquals(noteBounds, compose.onNodeWithTag(GuideTags.PANEL).getBoundsInRoot())
    }

    @Test
    fun theFirstKeyDismissesTheNoteAndStillActs() {
        show()
        press(Key.DirectionDown)
        note().assertDoesNotExist()
        compose.onNodeWithTag(GuideTags.PANEL).assertExists()
        // The Down moved the highlight to the next channel's cell: it was not swallowed.
        cell(60).assertIsNotSelected()
        cell(60, CH7).assertIsSelected()
    }

    @Test
    fun okReturnsToThePictureOfThePlayingChannel() {
        show()
        press(Key.DirectionCenter)
        assertEquals(listOf(CH5), tuned.toList())
        note().assertDoesNotExist()
    }

    @Test
    fun backOpensTheMenuSheetAndASecondBackLeaves() {
        show()
        back()
        note().assertDoesNotExist()
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value.menu == GuideMenuEntry.Channels }
        assertEquals(0, exits)
        back()
        assertEquals(1, exits)
    }

    @Test
    fun theNoteIsNotAFocusStopAndIsAnnouncedOnce() {
        show()
        val config = note().fetchSemanticsNode().config
        assertFalse(config.contains(SemanticsActions.RequestFocus))
        assertEquals(LiveRegionMode.Polite, config[SemanticsProperties.LiveRegion])
        // One node, with both lines, so a screen reader says the note as one announcement.
        val said = config[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertTrue(said, said.contains("on the air") && said.contains("press OK"))
        // Dismissed once: it does not come back on later keys.
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        note().assertDoesNotExist()
    }

    @Test
    fun withoutTheFlagThereIsNoNote() {
        show(firstVisit = false)
        note().assertDoesNotExist()
        compose.onNodeWithTag(GuideTags.PANEL).assertExists()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
    }
}
