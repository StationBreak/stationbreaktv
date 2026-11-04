// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.ChangeRefusal
import tv.stationbreak.core.domain.lineup.ChangeResult
import tv.stationbreak.core.domain.lineup.ChannelChange
import tv.stationbreak.core.domain.lineup.ChannelEditor
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.domain.lineup.PlayKey
import tv.stationbreak.core.domain.lineup.ResetResult
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.DraftFilters
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.FakeCatalogPicker
import tv.stationbreak.core.domain.lineup.builder.FakeChannelBuilder
import tv.stationbreak.core.domain.lineup.builder.FakeChannelPreviews
import tv.stationbreak.core.domain.lineup.builder.OpenResult
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.channels.ChannelsFocus
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.ChannelsViewModel
import tv.stationbreak.feature.channels.builder.BuilderViewModel
import tv.stationbreak.feature.channels.builder.ui.BuilderTags
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Instant

private val ALPHA = ChannelId("ch-alpha")
private val BRAVO = ChannelId("ch-bravo")
private val CHARLIE = ChannelId("ch-charlie")
private val DELTA = ChannelId("ch-delta")
private val NIGHT = ChannelId("ch-night")

@Suppress("LongParameterList") // A test builder: every field has a default.
private fun summary(
    id: ChannelId,
    number: Int,
    name: String,
    state: ChannelStatus = ChannelStatus.On,
    optIn: Boolean = false,
    kind: ChannelKind = ChannelKind.Starter,
): ChannelSummary {
    val since = Instant.fromEpochMilliseconds(T0)
    return ChannelSummary(
        id,
        number,
        name,
        state,
        kind,
        since,
        number,
        kid = false,
        optIn = optIn,
        playKey = PlayKey("test"),
    )
}

/** A lineup that follows the edits made to it, as the real one does. */
private class Lineup(
    initial: List<ChannelSummary>,
    private val access: LineupAccess,
) : ChannelRepository,
    ChannelEditor {
    val list = MutableStateFlow(initial)
    val changes = CopyOnWriteArrayList<ChannelChange>()

    val resets = AtomicInteger()

    @Volatile
    var resetGate: CompletableDeferred<Unit>? = null

    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = list

    override fun activeChannels(): Flow<List<ChannelSummary>> = list

    override fun access(account: LibraryAccount): Flow<LineupAccess> = flowOf(access)

    override suspend fun change(
        account: LibraryAccount,
        change: ChannelChange,
    ): ChangeResult {
        changes += change
        val now = list.value
        when (change) {
            is ChannelChange.SwitchOff -> {
                list.value = now.map { if (it.id == change.id) it.copy(state = ChannelStatus.UserHidden) else it }
            }

            is ChannelChange.SwitchOn -> {
                list.value = now.map { if (it.id == change.id) it.copy(state = ChannelStatus.On) else it }
            }

            is ChannelChange.SetNumber -> {
                val holder = now.firstOrNull { it.number == change.number && it.id != change.id }
                if (holder != null && change.swapWith == null) {
                    return ChangeResult.Refused(ChangeRefusal.NumberTaken(holder.id))
                }
                val old = now.first { it.id == change.id }.number
                list.value =
                    now.map {
                        when (it.id) {
                            change.id -> it.copy(number = change.number)
                            change.swapWith -> it.copy(number = old)
                            else -> it
                        }
                    }
            }

            is ChannelChange.ClearNumber -> {
                Unit
            }
        }
        return ChangeResult.Done
    }

    override suspend fun reset(account: LibraryAccount): ResetResult {
        resets.incrementAndGet()
        resetGate?.await()
        return ResetResult.Chosen
    }
}

/** The channel screen on screen (spec 21): the list, the panels and the keys that drive them, D-pad only. */
@RunWith(AndroidJUnit4::class)
class ChannelsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = LibraryAccount(ServerId("srv1"), UserId("u1"))
    private val library = InMemoryLibraryRepository()
    private val schedule = FakeSchedule()

    // The ViewModel's own thread, as Dispatchers.Default would be in the app.
    private val pool = Executors.newSingleThreadExecutor()
    private val work = pool.asCoroutineDispatcher()
    private val tuned = CopyOnWriteArrayList<ChannelId>()
    private var closed = 0
    private val showing = mutableStateOf(true)
    private val openedAt = mutableLongStateOf(1L)
    private val currentNow = mutableStateOf<ChannelId?>(null)
    private val builder = FakeChannelBuilder()
    private lateinit var vm: ChannelsViewModel
    private lateinit var lineup: Lineup

    private val clock =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(T0)
        }

    private fun lineup(extra: List<ChannelSummary> = emptyList()) =
        extra +
            listOf(
                summary(ALPHA, 6, "Alpha"),
                summary(BRAVO, 7, "Bravo"),
                summary(CHARLIE, 9, "Charlie", ChannelStatus.Shelf),
                summary(DELTA, 12, "Delta", ChannelStatus.UserHidden),
                summary(NIGHT, 30, "Night", ChannelStatus.Shelf, optIn = true),
            )

    private fun show(
        current: ChannelId?,
        access: LineupAccess = LineupAccess.Editable,
        bravoHidden: HiddenReason = HiddenReason.NoMatches,
        extra: List<ChannelSummary> = emptyList(),
    ) {
        schedule.channel(ALPHA, 6, emptyList())
        schedule.channel(BRAVO, 7, emptyList(), hidden = bravoHidden)
        lineup = Lineup(lineup(extra), access)
        currentNow.value = current
        runBlocking { library.useAccount(account) }
        vm = ChannelsViewModel(library, lineup, lineup, schedule, clock, work)
        val builderVm =
            BuilderViewModel(library, builder, FakeCatalogPicker(), FakeChannelPreviews(), lineup, clock, work)
        compose.setContent {
            StationBreakTheme {
                // As in the app: live TV's root is focusable and the screen takes the focus from it.
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).focusable()) {
                    if (showing.value) {
                        ChannelsRoot(vm, builderVm, currentNow.value, openedAt.longValue, HOLE, onDone = { tune ->
                            if (tune == null) closed++ else tuned += tune
                        })
                    }
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) {
            vm.ui.value.rows
                .isNotEmpty() && vm.ui.value.focus != null
        }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.close()
        pool.shutdownNow()
    }

    private fun press(vararg pads: Pad) {
        pads.forEach { pad ->
            compose.onRoot().performKeyInput { pressKey(keyOf(pad)) }
            compose.waitForIdle()
        }
    }

    /** The Text inside a digit's box, which carries the tag and the selection. */
    private fun digitText(position: Int): SemanticsNodeInteraction {
        val box = compose.onNodeWithTag(ChannelsTags.digit(position), useUnmergedTree = true)
        return box.onChild()
    }

    private fun keyOf(pad: Pad): Key =
        when (pad) {
            Pad.Up -> Key.DirectionUp
            Pad.Down -> Key.DirectionDown
            Pad.Left -> Key.DirectionLeft
            Pad.Right -> Key.DirectionRight
            Pad.Select -> Key.DirectionCenter
            Pad.Back -> Key.Back
        }

    private fun until(condition: () -> Boolean) {
        compose.waitUntil(TIMEOUT_MS, condition)
        compose.waitForIdle()
    }

    private fun exists(tag: String): Boolean {
        val nodes = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes(atLeastOneRootRequired = false)
        return nodes.isNotEmpty()
    }

    @Test
    fun listShowsGroupsAndReasons() {
        show(ALPHA)
        until { exists(ChannelsTags.reason(BRAVO)) }
        listOf(ChannelsTags.HEADING_ON, ChannelsTags.HEADING_SHELF, ChannelsTags.HEADING_OFF)
            .forEach { assertTrue(it, exists(it)) }
        compose
            .onNodeWithTag(ChannelsTags.reason(BRAVO), useUnmergedTree = true)
            .assertTextContains("Nothing in your libraries fits this channel", substring = true)
        assertTrue(!exists(ChannelsTags.reason(ALPHA)))
        compose.onNodeWithTag(ChannelsTags.row(ALPHA)).assertIsSelected()
        assertTrue(exists(ChannelsTags.RESET))
    }

    @Test
    fun anAllFilteredReasonShowsItsText() {
        show(ALPHA, bravoHidden = HiddenReason.AllFilteredOut)
        until { exists(ChannelsTags.reason(BRAVO)) }
        compose
            .onNodeWithTag(ChannelsTags.reason(BRAVO), useUnmergedTree = true)
            .assertTextContains("Everything that fits is filtered out", substring = true)
    }

    @Test
    fun selectOpensTheActionsPanel() {
        show(ALPHA)
        until { exists(ChannelsTags.reason(BRAVO)) }
        press(Pad.Select)
        until { exists(ChannelsTags.PANEL) }
        listOf(ChannelsTags.ACTION_WATCH, ChannelsTags.ACTION_SWITCH_OFF, ChannelsTags.ACTION_CHANGE_NUMBER)
            .forEach { assertTrue(it, exists(it)) }
        assertTrue(exists(ChannelsTags.ACTION_COPY))
        assertTrue(!exists(ChannelsTags.ACTION_EDIT))
        assertTrue(!exists(ChannelsTags.ACTION_SWITCH_ON))
        assertTrue(!exists(ChannelsTags.ACTION_DEFAULT_NUMBER))
        compose.onNodeWithTag(ChannelsTags.ACTION_WATCH).assertIsSelected()
        press(Pad.Select)
        assertEquals(listOf(ALPHA), tuned.toList())
    }

    @Test
    fun numberPickerSetsANumberWithTheDPad() {
        show(ALPHA)
        press(Pad.Select, Pad.Down, Pad.Down, Pad.Down, Pad.Select)
        until { exists(ChannelsTags.PICKER) }
        digitText(0).assertTextEquals("0")
        digitText(2).assertTextEquals("6")
        // 006: the middle digit up once is 016; Left from the first digit stays put.
        press(Pad.Left, Pad.Right, Pad.Up)
        compose.onNodeWithTag(ChannelsTags.digit(1)).assertIsSelected()
        digitText(1).assertTextEquals("1")
        press(Pad.Select)
        until { lineup.changes.isNotEmpty() && !exists(ChannelsTags.PANEL) }
        assertEquals(listOf<ChannelChange>(ChannelChange.SetNumber(ALPHA, 16)), lineup.changes.toList())
    }

    @Test
    fun takenNumberAsksToSwap() {
        show(ALPHA)
        press(Pad.Select, Pad.Down, Pad.Down, Pad.Down, Pad.Select)
        until { exists(ChannelsTags.PICKER) }
        // 006 to 007: Bravo has it.
        press(Pad.Right, Pad.Right, Pad.Up, Pad.Select)
        until { exists(ChannelsTags.ACTION_CONFIRM) }
        compose
            .onNodeWithTag(ChannelsTags.PANEL_TEXT, useUnmergedTree = true)
            .assertTextContains("Channel 7 is Bravo. Swap numbers?", substring = true)
        press(Pad.Select)
        until {
            !exists(ChannelsTags.PANEL) && lineup.list.value
                .first { it.id == ALPHA }
                .number == 7
        }
        assertEquals(
            6,
            lineup.list.value
                .first { it.id == BRAVO }
                .number,
        )
        assertEquals(ChannelChange.SetNumber(ALPHA, 7, BRAVO), lineup.changes.last())
    }

    @Test
    fun switchOffMovesTheRowToOffAndFocusFollows() {
        show(ALPHA)
        press(Pad.Select, Pad.Down, Pad.Select)
        until {
            lineup.list.value
                .first { it.id == ALPHA }
                .state == ChannelStatus.UserHidden && !exists(ChannelsTags.PANEL)
        }
        compose.onNodeWithTag(ChannelsTags.row(ALPHA)).assertIsSelected()
        val off = compose.onNodeWithTag(ChannelsTags.HEADING_OFF).getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag(ChannelsTags.row(ALPHA)).getUnclippedBoundsInRoot()
        assertTrue("Alpha ($row) sits under the Off heading ($off)", row.top > off.top)
    }

    @Test
    fun optInAsksBeforeSwitchingOn() {
        show(NIGHT)
        press(Pad.Select)
        until { exists(ChannelsTags.ACTION_SWITCH_ON) }
        press(Pad.Select)
        until { exists(ChannelsTags.ACTION_CONFIRM) }
        compose
            .onNodeWithTag(ChannelsTags.PANEL_TEXT, useUnmergedTree = true)
            .assertTextContains("Night shows adult content. Switch it on?", substring = true)
        assertTrue("nothing is sent before the answer", lineup.changes.isEmpty())
        // The question starts on Cancel: a stray Select does not switch it on.
        compose.onNodeWithTag(ChannelsTags.ACTION_CANCEL).assertIsSelected()
        press(Pad.Up, Pad.Select)
        until { lineup.changes.isNotEmpty() && !exists(ChannelsTags.PANEL) }
        val expected = listOf<ChannelChange>(ChannelChange.SwitchOn(NIGHT, confirmedOptIn = true))
        assertEquals(expected, lineup.changes.toList())
    }

    @Test
    fun resetAsksThenShowsChoosing() {
        show(ALPHA)
        lineup.resetGate = CompletableDeferred()
        repeat(lineup().size) { press(Pad.Down) }
        compose.onNodeWithTag(ChannelsTags.RESET).assertIsSelected()
        press(Pad.Select)
        until { exists(ChannelsTags.PANEL) }
        compose
            .onNodeWithTag(ChannelsTags.PANEL_TEXT, useUnmergedTree = true)
            .assertTextContains(
                "Reset channels? Your numbers and switched-off channels on this TV will be lost.",
                substring = true,
            )
        press(Pad.Up, Pad.Select)
        until { exists(ChannelsTags.CHOOSING) }
        val choosing = compose.onNodeWithTag(ChannelsTags.CHOOSING, useUnmergedTree = true)
        choosing.assertTextContains("Choosing channels", substring = true)
        lineup.resetGate?.complete(Unit)
        until { !exists(ChannelsTags.CHOOSING) }
    }

    @Test
    fun backClosesThePanelThenTheScreen() {
        show(ALPHA)
        press(Pad.Select)
        until { exists(ChannelsTags.PANEL) }
        press(Pad.Back)
        until { !exists(ChannelsTags.PANEL) }
        assertEquals("the first Back only closed the panel", 0, closed)
        press(Pad.Back)
        assertEquals(1, closed)
        assertTrue(tuned.isEmpty())
    }

    @Test
    fun notEditableShowsOnlyWatch() {
        show(ALPHA, LineupAccess.ReadOnly)
        until { exists(ChannelsTags.NOT_EDITABLE) }
        compose
            .onNodeWithTag(ChannelsTags.NOT_EDITABLE, useUnmergedTree = true)
            .assertTextContains("Channels can't be changed on this TV until the app is updated.", substring = true)
        press(Pad.Select)
        until { exists(ChannelsTags.PANEL) }
        assertTrue(exists(ChannelsTags.ACTION_WATCH))
        listOf(
            ChannelsTags.ACTION_SWITCH_OFF,
            ChannelsTags.ACTION_SWITCH_ON,
            ChannelsTags.ACTION_COPY,
            ChannelsTags.ACTION_EDIT,
            ChannelsTags.ACTION_CHANGE_NUMBER,
            ChannelsTags.ACTION_DEFAULT_NUMBER,
        ).forEach { assertTrue(it, !exists(it)) }
    }

    /** Keys sent one straight after the other, with no frame between them. */
    private fun pressTogether(vararg pads: Pad) {
        compose.onRoot().performKeyInput { pads.forEach { pressKey(keyOf(it)) } }
        compose.waitForIdle()
    }

    @Test
    fun aDoubleSelectOnResetDoesNotReset() {
        show(ALPHA)
        repeat(lineup().size) { press(Pad.Down) }
        pressTogether(Pad.Select, Pad.Select)
        until { !exists(ChannelsTags.PANEL) }
        assertEquals(0, lineup.resets.get())
    }

    @Test
    fun aDoubleSelectOnTheOptInQuestionDoesNotSwitchOn() {
        show(NIGHT)
        // Row, the switch-on action, then a third Select at once: the question's default is Cancel.
        pressTogether(Pad.Select, Pad.Select, Pad.Select)
        until { !exists(ChannelsTags.PANEL) }
        assertTrue(lineup.changes.isEmpty())
    }

    @Test
    fun theViewModelIsClosedWhenTheScreenLeaves() {
        show(ALPHA)
        press(Pad.Select)
        until { exists(ChannelsTags.PANEL) }
        compose.runOnUiThread { showing.value = false }
        compose.waitForIdle()
        assertEquals(ChannelsUi.Empty, vm.ui.value)
    }

    @Test
    fun selectOnZeroZeroZeroStaysInThePicker() {
        show(ALPHA)
        press(Pad.Select, Pad.Down, Pad.Down, Pad.Down, Pad.Select)
        until { exists(ChannelsTags.PICKER) }
        // 006 to 000.
        press(Pad.Right, Pad.Right)
        repeat(6) { press(Pad.Down) }
        press(Pad.Select)
        assertTrue(exists(ChannelsTags.PICKER))
        assertTrue(lineup.changes.isEmpty())
    }

    @Test
    fun reopeningWithANewTokenAndAnotherChannelStartsClean() {
        show(ALPHA)
        press(Pad.Select)
        until { exists(ChannelsTags.PANEL) }
        compose.runOnUiThread {
            currentNow.value = BRAVO
            openedAt.longValue = 2L
        }
        compose.waitForIdle()
        until { vm.ui.value.focus == ChannelsFocus.Row(BRAVO) }
        assertTrue(!exists(ChannelsTags.PANEL))
        compose.onNodeWithTag(ChannelsTags.row(BRAVO)).assertIsSelected()
    }

    @Test
    fun watchFinishesTheScreenOnceWithTheChannel() {
        show(ALPHA)
        until { exists(ChannelsTags.reason(BRAVO)) }
        pressTogether(Pad.Select, Pad.Select, Pad.Select)
        assertEquals(listOf(ALPHA), tuned.toList())
        assertEquals(0, closed)
    }

    @Test
    fun newChannelRowOpensTheStepsAndBackReturns() {
        show(null)
        compose.onNodeWithTag(ChannelsTags.NEW_CHANNEL).assertIsSelected()
        press(Pad.Select)
        until { exists(BuilderTags.SCREEN) }
        compose.onNodeWithTag(BuilderTags.TITLE).assertTextContains("New channel")
        press(Pad.Back)
        until { !exists(BuilderTags.SCREEN) }
        compose.onNodeWithTag(ChannelsTags.NEW_CHANNEL).assertIsSelected()
        assertEquals("the builder's Back did not leave the screen", 0, closed)
        // The screen has its keys back.
        press(Pad.Down, Pad.Select)
        until { exists(ChannelsTags.PANEL) }
    }

    @Test
    fun copyToCustomiseOpensTheBuilderWithTheCopyNameFromTheSuffixResource() {
        val draft =
            ChannelDraft(
                "Alpha",
                listOf(DraftSource.Library("lib1", "Library 1")),
                emptyList(),
                DraftFilters(),
                kids = false,
                number = 600,
                origin = ALPHA,
                seed = Seed(1uL),
            )
        builder.openResult = OpenResult.Opened(draft, "Alpha")
        show(ALPHA)
        // Watch is offered once the reasons are read; until then Copy is one choice earlier.
        until { exists(ChannelsTags.reason(BRAVO)) }
        // Watch, Switch off, Copy to customise.
        press(Pad.Select, Pad.Down, Pad.Down, Pad.Select)
        until { exists(BuilderTags.SCREEN) && !exists(ChannelsTags.PANEL) }
        until { runCatching { compose.onNodeWithTag(BuilderTags.TITLE).assertTextContains("Alpha (copy)") }.isSuccess }
        press(Pad.Back)
        until { !exists(BuilderTags.SCREEN) }
        compose.onNodeWithTag(ChannelsTags.row(ALPHA)).assertIsSelected()
    }

    @Test
    fun aCustomChannelFromANewerAppOffersNeitherCopyNorEditAndSaysWhy() {
        val newer = ChannelId("custom:newer")
        show(newer, extra = listOf(summary(newer, 5, "Newer", kind = ChannelKind.UnusableCustom)))
        press(Pad.Select)
        until { exists(ChannelsTags.PANEL) }
        compose
            .onNodeWithTag(ChannelsTags.NEWER_APP, useUnmergedTree = true)
            .assertTextContains("newer version of the app", substring = true)
        listOf(ChannelsTags.ACTION_COPY, ChannelsTags.ACTION_EDIT).forEach { assertTrue(it, !exists(it)) }
    }

    @Test
    fun anUnreadableStarterKeepsTheOrdinaryPanel() {
        val old = ChannelId("starter:old")
        show(old, extra = listOf(summary(old, 5, "Old", kind = ChannelKind.Unusable)))
        press(Pad.Select)
        until { exists(ChannelsTags.PANEL) }
        assertTrue(!exists(ChannelsTags.NEWER_APP))
        listOf(ChannelsTags.ACTION_COPY, ChannelsTags.ACTION_EDIT).forEach { assertTrue(it, !exists(it)) }
    }

    private companion object {
        // A 1080p TV at xhdpi, whatever the device is, as the guide's tests host it.
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
        const val TIMEOUT_MS = 5_000L
    }
}
