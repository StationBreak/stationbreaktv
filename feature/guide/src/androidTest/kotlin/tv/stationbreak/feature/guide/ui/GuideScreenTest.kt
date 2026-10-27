// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import tv.stationbreak.core.ui.ScreenLayout
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

    // Whether live TV shows the guide, and when it last opened it; a test changes them to close and open again.
    private val showing = mutableStateOf(true)
    private val openedAt = mutableLongStateOf(1L)

    private fun show(
        current: ChannelId?,
        measure: Boolean = false,
    ) {
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
                    Box(Modifier.requiredSize(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp).focusable()) {
                        if (showing.value) {
                            GuideRoot(
                                vm,
                                current,
                                pictureHole = HOLE,
                                onTune = { tuned += it },
                                openedAtNanos = openedAt.longValue,
                                measure = measure,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showReady(
        current: ChannelId?,
        measure: Boolean = false,
    ) {
        lineup()
        show(current, measure)
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value.state is GuideState.Ready }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.setOnAccessibilityEventListener(null)
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
        // What a screen reader hears, put together only when it is asked for.
        cell(CH5, 60).assertContentDescriptionContains("Test Movie 201, ", substring = true)
        cell(CH5, 60).assertContentDescriptionContains(", on now", substring = true)
        assertEquals("Test Movie 201", text(GuideTags.PANEL_TITLE))
    }

    @Test
    fun panelAnnouncesWhatChangedNotTheMinuteCountdown() {
        showReady(CH5)
        // The panel is one polite live region: a screen reader reads it again when it changes, so the line and bar
        // that change every minute are kept out of it, while still being there to read.
        val panel = compose.onNodeWithTag(GuideTags.PANEL).fetchSemanticsNode().config
        val announced = panel.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }
        assertTrue(announced, announced.contains("Test Movie 201"))
        assertTrue(announced, !announced.contains("min left"))
        compose.onNodeWithText("min left", substring = true).assertExists()
    }

    @Test
    fun aRestingFocusSendsNoAccessibilityEvents() {
        // #438: with VoiceView on, a cell the focus rests on was read again every ~6 s. A screen reader reads again
        // what changes, so while nothing is pressed the guide must send it nothing: no content, selection or live
        // region change. A move does send events, which shows the recorder hears the guide.
        showReady(CH5)
        val events = recordAccessibilityEvents()
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        cell(CH5, 60).assertIsSelected()
        // The description's 150 ms rest and the details' 500 ms dwell, with room for the events they send to arrive.
        rest(SETTLE_MS)
        assertTrue("a move sends events", events.isNotEmpty())
        events.clear()

        rest(REST_MS)

        assertEquals(emptyList<String>(), events.toList())
    }

    /**
     * Lets [ms] go by on the screen's clock (its effects, frames and accessibility events run on it) in steps of a
     * frame or so, and as long on the wall clock, which the guide's own thread and the event delivery run on.
     */
    private fun rest(ms: Long) {
        val until = SystemClock.uptimeMillis() + ms
        repeat((ms / REST_STEP_MS).toInt()) {
            compose.mainClock.advanceTimeBy(REST_STEP_MS)
            SystemClock.sleep((until - SystemClock.uptimeMillis()).coerceIn(0, REST_STEP_MS))
        }
        compose.waitForIdle()
    }

    /**
     * The accessibility events the screen sends from now on, as "type changes=content-change-types class", while the
     * test runs. Compose sends them only when a screen reader is on; the test's own instrumentation stands in for one,
     * and Compose is told to send them as it would then.
     */
    private fun recordAccessibilityEvents(): MutableList<String> {
        val events = CopyOnWriteArrayList<String>()
        val app = compose.activity.packageName
        InstrumentationRegistry.getInstrumentation().uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.packageName == app) {
                val type = AccessibilityEvent.eventTypeToString(event.eventType)
                events += "$type changes=${event.contentChangeTypes} ${event.className}"
            }
        }
        compose.runOnUiThread { forceAccessibility(compose.activity.window.decorView) }
        return events
    }

    // Compose's own test switch (internal API, read by reflection): the semantics events go out as with a screen reader
    // on. A Compose update that renames it fails the test here rather than letting it pass without hearing anything.
    private fun forceAccessibility(root: View) {
        val composeView = checkNotNull(findComposeView(root)) { "no AndroidComposeView" }
        val delegate = checkNotNull(ViewCompat.getAccessibilityDelegate(composeView)) { "no accessibility delegate" }
        val flag = delegate.javaClass.declaredFields.first { it.name.startsWith(FORCE_ACCESSIBILITY) }
        flag.isAccessible = true
        flag.setBoolean(delegate, true)
    }

    private fun findComposeView(view: View): View? =
        when {
            view.javaClass.name.endsWith("AndroidComposeView") -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findComposeView(view.getChildAt(it)) }
            else -> null
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
    fun compactCellShowsItsTitleInThePanelOnly() {
        showReady(ch3)
        // Five minutes is too narrow for a title: nothing is written in the cell, focused or not, and nothing floats
        // over its neighbours. The panel names it.
        press(Key.DirectionRight)
        cell(ch3, 70).assertIsSelected()
        compose.onNodeWithTag(GuideTags.cellText(ch3, min(70)), useUnmergedTree = true).assertDoesNotExist()
        assertEquals("Test Movie 401", text(GuideTags.PANEL_TITLE))
        compose.onAllNodesWithText("Test Movie 401", useUnmergedTree = true).assertCountEquals(1)
        // The neighbour it used to float over is still there to read.
        compose.onNodeWithTag(GuideTags.cellText(ch3, min(75)), useUnmergedTree = true).assertExists()
    }

    // Far right on channel 7, then the guide closed: what a reopen must not come back to.
    private fun scrollFarRight() {
        repeat(6) { press(Key.DirectionRight) }
        cell(CH7, 240).assertIsSelected()
    }

    private fun assertFreshOpen() {
        compose.waitUntil(TIMEOUT_MS) {
            vm.snapshot.value.focus
                ?.cellStartMs == min(60)
        }
        compose.waitForIdle()
        cell(CH7, 60).assertIsSelected()
        assertEquals(min(60), (vm.snapshot.value.state as GuideState.Ready).viewportStartMs)
    }

    @Test
    fun reopeningAfterAGuideKeyCloseStartsFresh() {
        // As on the device: the measured build, the guide closed (the Guide key) and opened again (Back) later. Nothing
        // here goes by the clock, so the reopen follows at once.
        showReady(CH7, measure = true)
        scrollFarRight()
        compose.runOnIdle { showing.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            openedAt.longValue = 2L
            showing.value = true
        }
        assertFreshOpen()
    }

    @Test
    fun aCloseAndReopenInOneFrameStartsFresh() {
        // Live TV closed and opened the guide between two frames: the slot never left the screen, but this is a new
        // opening (a new open time), and it must open afresh.
        showReady(CH7)
        scrollFarRight()
        compose.runOnIdle { openedAt.longValue = 2L }
        assertFreshOpen()
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
    fun infoOverlayHidesTheGuidesOwnPanel() {
        showReady(CH7)
        press(Key.DirectionRight)
        assertEquals("Test Movie 103", text(GuideTags.PANEL_TITLE))
        press(Key.DirectionCenter)
        compose.onNodeWithTag(GuideTags.INFO).assertExists()
        // The title and the time once, in the overlay: not again in the panel underneath it.
        compose.onNodeWithTag(GuideTags.PANEL_TITLE, useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithText("Test Movie 103", useUnmergedTree = true).assertCountEquals(2) // overlay + cell
        back()
        compose.onNodeWithTag(GuideTags.INFO).assertDoesNotExist()
        assertEquals("Test Movie 103", text(GuideTags.PANEL_TITLE))
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
    fun aMoveRecomposesTheTwoCellsThePanelAndTheHintOnly() {
        showReady(CH7)
        // Right (a later cell: the hint changes to Info), then Down (on to channel 9, still in the window: no row
        // moves).
        for ((key, to) in listOf(Key.DirectionRight to cell(CH7, 90), Key.DirectionDown to cell(CH9, 0))) {
            val traced = traceComposition { press(key) }
            to.assertIsSelected()
            Log.i(LOG_TAG, "$key: $traced")
            // Something composed (the cells at least): if the compiler's markers change shape, this fails, rather than
            // the filtered checks below passing on nothing.
            assertTrue("no composition was traced", traced.all.isNotEmpty())
            val roots = traced.guideRoots
            // Each cell recomposes on its own (it reads only whether it is focused), and nothing over or around the
            // cells does: not their row, the channel column, the clock strip, the now line or the grid.
            assertEquals("$key: $roots", 2, roots.count { it == "ui.GuideCellView" })
            val others = roots.filterNot { it == "ui.GuideCellView" || it.startsWith("ui.DetailsPanel") }
            assertTrue("$key: $roots", others.all { it.startsWith("ui.HintRow") })
        }
    }

    /**
     * Every text on screen by node, its tag (or its text) and the paragraphs it is laid out in now; none for a text
     * that has never been measured (a title in a cell with no room for one).
     */
    private fun laidOut(): Map<Int, Pair<String, Any?>> =
        compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .associate { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                val text = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("")
                val name = node.config.getOrElse(SemanticsProperties.TestTag) { text }
                node.id to (name to layouts.singleOrNull()?.multiParagraph)
            }

    @Test
    fun aFocusMoveDoesNotLayOutTheCellsTextsAgain() {
        showReady(CH7)
        for (key in listOf(Key.DirectionRight, Key.DirectionDown)) {
            val before = laidOut()
            press(key)
            val after = laidOut()
            // Laid out again: new paragraphs for a text that was there before (one that changed, or one re-measured).
            val again =
                after
                    .filter { (id, now) -> before[id]?.let { it.second !== now.second } == true }
                    .values
                    .map { it.first }
            Log.i(LOG_TAG, "$key: ${again.size} of ${after.size} texts laid out again: $again")
            // The two cells whose focus changed only change colour: that is drawn, not laid out. The panel and the hint
            // say something else, and are laid out again for it.
            assertEquals("$key: $again", emptyList<String>(), again.filter { it.startsWith(CELL_TEXT) })
        }
    }

    @Test
    fun aCellPagedOutOfViewDoesNotLayItsTitleOut() {
        showReady(CH7)
        // Channel 9's first program (0 to 150 minutes) shows its title from 60 to 150; a page to 150 leaves none of it
        // in view, so it is compact there and its title is not measured at all (not measured to no width).
        repeat(2) { press(Key.DirectionRight) }
        val title = laidOut().entries.single { it.value.first == GuideTags.cellText(CH9, min(0)) }.key
        val paragraphs = laidOut().getValue(title).second
        assertNotNull("laid out while in view", paragraphs)
        press(Key.DirectionRight)
        assertEquals(min(150), (vm.snapshot.value.state as GuideState.Ready).viewportStartMs)
        assertTrue("laid out again", laidOut().getValue(title).second === paragraphs)
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
        // brought into it: the page moved the rows in the layout phase. Not the grid's body either: a page is the same
        // grid frame.
        assertEquals(mapOf("${CH7.value}@${min(120)}" to 1, "${CH7.value}@${min(150)}" to 1), composed.toMap())
    }

    @Test
    fun aLongChannelNumberEndsInAnEllipsis() {
        lineup()
        fake.channel(ChannelId("ch-1234"), 1234, (0L until 600L step 30).map { program(it, it + 30, 600) })
        show(CH7)
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value.state is GuideState.Ready }
        compose.waitForIdle()
        val number = compose.onNodeWithText("1234").fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        number.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertTrue("the number is cut with an ellipsis, not clipped", layouts.single().isLineEllipsized(0))
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
        // the two whose on-now state flipped (read from the clock) compose again, and both do.
        val flipped = setOf("${ch2.value}@${min(0)}", "${ch2.value}@${min(66)}")
        val cells = composed.keys.filter { it != BODY }
        assertEquals(flipped, cells.toSet())
    }

    @Test
    fun aFillInThatChangesOneRowRecomposesOnlyThatRowsCells() {
        // Rows of one long program are the same before and after the fill-in; only channel 7's grows past 3 hours.
        fake.channel(ch3, 3, listOf(program(0, 600, 400)))
        fake.channel(CH5, 5, listOf(program(0, 600, 200)))
        fake.channel(CH7, 7, (0L until 600L step 30).map { program(it, it + 30, 100 + (it / 30).toInt()) })
        fake.channel(CH9, 9, listOf(program(0, 600, 300)))
        fake.cachedUntilMs = min(180)
        schedule.gate = CompletableDeferred()
        show(CH5)
        compose.waitUntil(TIMEOUT_MS) { vm.snapshot.value.state is GuideState.Ready }
        compose.waitForIdle()
        composed.clear()

        schedule.gate?.complete(Unit)
        compose.waitUntil(TIMEOUT_MS) { (vm.snapshot.value.state as GuideState.Ready).model.complete }
        compose.waitForIdle()

        val cells = composed.keys.filter { it != BODY }
        assertTrue("the grown row composed its new cells", cells.isNotEmpty())
        assertTrue("only channel 7's cells: $cells", cells.all { it.startsWith("${CH7.value}@") })
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
        // A 1080p TV at xhdpi. Cell widths scale with the screen, so a smaller emulator (CI's default profile) would
        // make the focused cell compact; the screen is hosted at this size whatever the device is.
        const val HOST_WIDTH_DP = 960
        const val HOST_HEIGHT_DP = 540

        // Where live TV lays its picture on that screen, as it tells the guide.
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)))
        const val TIMEOUT_MS = 5_000L
        const val PRESS_MS = 50L
        const val BODY = "body"
        const val LOG_TAG = "GuideScreenTest"
        const val CELL_TEXT = "guide:cellText:"
        const val TICK_TIMEOUT_MS = 20_000L

        // Two of the ~6 s cycles seen on the device (#438), and the deferrals after a move.
        const val REST_MS = 13_000L
        const val SETTLE_MS = 1_500L
        const val REST_STEP_MS = 100L
        const val FORCE_ACCESSIBILITY = "accessibilityForceEnabledForTesting"
    }
}
