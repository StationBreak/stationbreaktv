// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.children
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.kids.NO_FLOOR
import tv.stationbreak.core.domain.lineup.ChangeRefusal
import tv.stationbreak.core.domain.lineup.ChangeResult
import tv.stationbreak.core.domain.lineup.ChannelChange
import tv.stationbreak.core.domain.lineup.ChannelEditor
import tv.stationbreak.core.domain.lineup.ChannelHoldings
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelProperties
import tv.stationbreak.core.domain.lineup.ChannelPropertiesReader
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.ChannelTiming
import tv.stationbreak.core.domain.lineup.FakeReadiness
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.FamilyTier
import tv.stationbreak.core.domain.lineup.HeldTitle
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.HomeShows
import tv.stationbreak.core.domain.lineup.HouseholdSetting
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.domain.lineup.MovedChannel
import tv.stationbreak.core.domain.lineup.PlayKey
import tv.stationbreak.core.domain.lineup.ResetResult
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ShelfReason
import tv.stationbreak.core.domain.lineup.ThemeAnchor
import tv.stationbreak.core.domain.lineup.ThemeAnchorKind
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.DraftFilters
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.FakeCatalogPicker
import tv.stationbreak.core.domain.lineup.builder.FakeChannelBuilder
import tv.stationbreak.core.domain.lineup.builder.FakeChannelPreviews
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.KeptOrder
import tv.stationbreak.core.domain.lineup.builder.Narrowing
import tv.stationbreak.core.domain.lineup.builder.OnlyKind
import tv.stationbreak.core.domain.lineup.builder.OpenResult
import tv.stationbreak.core.domain.lineup.builder.RulesDescription
import tv.stationbreak.core.domain.lineup.builder.ShownOrder
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.core.ui.mark.MarkKind
import tv.stationbreak.core.ui.mark.MarkKindKey
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelsFocus
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.ChannelsViewModel
import tv.stationbreak.feature.channels.Choice
import tv.stationbreak.feature.channels.builder.BuilderViewModel
import tv.stationbreak.feature.channels.builder.ui.BuilderTags
import tv.stationbreak.feature.channels.choicesOf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Instant

private val ALPHA = ChannelId("ch-alpha")
private val BRAVO = ChannelId("ch-bravo")
private val CHARLIE = ChannelId("ch-charlie")
private val DELTA = ChannelId("ch-delta")
private val NIGHT = ChannelId("ch-night")
private val ECHO = ChannelId("ch-echo")

// The shelf reason for a channel that fits but missed the cut.
private const val LINEUP_FULL = "Fits, but your lineup is full. Switch it on to add it."

@Suppress("LongParameterList") // A test builder: every field has a default.
private fun summary(
    id: ChannelId,
    number: Int,
    name: String,
    state: ChannelStatus = ChannelStatus.On,
    optIn: Boolean = false,
    kind: ChannelKind = ChannelKind.Starter,
    shelfReason: ShelfReason? = null,
    viewerMade: Boolean = kind == ChannelKind.Custom,
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
        shelfReason = shelfReason,
        viewerMade = viewerMade,
        floor = NO_FLOOR,
    )
}

/** The properties of a channel of [lineup]: a themed channel's, with every block the sheet draws. */
private class SamplePropertiesReader(
    private val lineup: () -> Lineup,
) : ChannelPropertiesReader {
    override suspend fun read(
        id: ChannelId,
        at: Instant,
    ): ChannelProperties? {
        val summary = lineup().list.value.firstOrNull { it.id == id } ?: return null
        val description =
            RulesDescription(
                sources = listOf(DraftSource.Genre("Crime", "Crime"), DraftSource.Collection("c1", "Noir Classics")),
                leaveOut = listOf(DraftSource.Tag("late", "late")),
                kept = listOf(KeptItem.Order(KeptOrder.Curated)),
                narrowing = Narrowing(notGenres = listOf("Horror"), only = OnlyKind.Films),
                ceiling = null,
                order = ShownOrder.Kept(KeptOrder.Curated),
                showsCaps = false,
                seriesCap = null,
                filmCap = null,
            )
        return ChannelProperties(
            summary = summary,
            personality = "Rain on the glass.",
            description = description,
            anchors = listOf(ThemeAnchor(ThemeAnchorKind.Collection, "Noir Classics")),
            timing = ChannelTiming("06:00", null, "07EE-7E07"),
            leftoversCeiling = null,
            hidden = null,
            holdings = HOLDINGS.takeIf { id == ALPHA },
        )
    }

    companion object {
        private fun heldTitle(n: Int): HeldTitle {
            val id = CanonicalId.parse(n.toString(16).padStart(32, '0'))
            return HeldTitle(id, "Show $n", n * 3_600_000L, n == 1)
        }

        /** A channel with homes: 412 hours, 37 titles, six listed and 31 more. */
        val HOLDINGS =
            ChannelHoldings(
                totalMs = 412 * 3_600_000L,
                titleCount = 37,
                homes = HomeShows.Known(37, (1..6).map { heldTitle(it) }),
                top = emptyList(),
                validUntilMs = Long.MAX_VALUE,
            )
    }
}

/** A lineup that follows the edits made to it, as the real one does. */
private class Lineup(
    initial: List<ChannelSummary>,
    private val access: LineupAccess,
) : ChannelRepository,
    ChannelEditor,
    HouseholdSetting {
    val list = MutableStateFlow(initial)
    val household = MutableStateFlow(true)
    val changesAt = MutableStateFlow(6 * 3_600_000L)
    val changes = CopyOnWriteArrayList<ChannelChange>()

    val resets = AtomicInteger()

    @Volatile
    var resetGate: CompletableDeferred<Unit>? = null

    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = list

    override fun activeChannels(): Flow<List<ChannelSummary>> = list

    override fun useMyCollections(account: LibraryAccount): Flow<Boolean> = household

    override fun changesStartAt(account: LibraryAccount): Flow<Long> = changesAt

    override suspend fun peekMovedNotices(account: LibraryAccount): List<MovedChannel> = emptyList()

    override suspend fun acknowledgeMoved(account: LibraryAccount): Boolean = true

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

            is ChannelChange.UseMyCollections -> {
                household.value = change.on
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
@Suppress("LargeClass") // One rig of fakes for every case the screen has on a device, free-tier gates included (#811).
class ChannelsScreenTest {
    // The v2 rule: its effects run on a standard test dispatcher. Under v1 a state this test's ViewModel set on its own
    // thread resumed the screen's collector there, and the recomposition ran off the main thread, which corrupted
    // the composition and crashed the run when the activity was destroyed (#783).
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = LibraryAccount(ServerId("srv1"), UserId("u1"))
    private val library = InMemoryLibraryRepository()
    private val schedule = FakeSchedule()

    // The ViewModels' work runs in the main thread's message queue, so Compose's idle wait covers it and the
    // assertions below, which read from this thread after an idle wait, do not meet a state change from another thread.
    // The ViewModels' own thread safety is covered by their unit tests.
    private val work = Handler(Looper.getMainLooper()).asCoroutineDispatcher()
    private val tuned = CopyOnWriteArrayList<ChannelId>()
    private var closed = 0
    private val showing = mutableStateOf(true)
    private val openedAt = mutableLongStateOf(1L)
    private val currentNow = mutableStateOf<ChannelId?>(null)
    private val builder = FakeChannelBuilder()

    // Pro is on unless a test closes the gates (#811): the free-tier tests below set it to false before show().
    private val pro = FakeProEntitlement()
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
                summary(CHARLIE, 9, "Charlie", ChannelStatus.Shelf, shelfReason = ShelfReason.BelowCap),
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
        val properties = SamplePropertiesReader { lineup }
        vm =
            ChannelsViewModel(
                library,
                lineup,
                lineup,
                lineup,
                pro,
                builder,
                schedule,
                properties,
                FakeReadiness(),
                clock,
                work,
            )
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

    /**
     * Waits for [condition], which runs on the main thread, between its other work. The semantics tree is read there
     * ([nodes]) and never from this thread: a read here builds stale semantics configurations on this thread, and a
     * recomposition on the main thread at that moment (a delayed ViewModel step, say, which Compose's idle wait does
     * not cover) is refused by Compose ("Detected multithreaded access to SnapshotStateObserver").
     */
    private fun until(condition: () -> Boolean) {
        compose.waitUntil(TIMEOUT_MS) { compose.runOnUiThread(condition) }
        compose.waitForIdle()
    }

    private fun titleOf(node: SemanticsNode): String? =
        node.config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }

    /** The nodes of the unmerged semantics tree that [matcher] matches. Main thread only. */
    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> {
        val root =
            generateSequence(listOf(compose.activity.window.decorView)) { views ->
                views.filterIsInstance<ViewGroup>().flatMap { group -> group.children.toList() }
            }.takeWhile { it.isNotEmpty() }
                .flatten()
                .filterIsInstance<RootForTest>()
                .firstOrNull() ?: return emptyList()
        val all =
            generateSequence(listOf(root.semanticsOwner.unmergedRootSemanticsNode)) { level ->
                level.flatMap { it.children }.takeIf { it.isNotEmpty() }
            }
        return all.flatten().filter(matcher::matches).toList()
    }

    private fun exists(tag: String): Boolean = compose.runOnUiThread { nodes(hasTestTag(tag)).isNotEmpty() }

    /** Walks the list to the line [matcher] names: the band flags push a long list past what is composed at once. */
    private fun scrollListTo(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        compose.waitForIdle()
    }

    @Test
    fun listShowsGroupsAndReasons() {
        show(ALPHA)
        until { exists(ChannelsTags.reason(BRAVO)) }
        listOf(ChannelsTags.HEADING_ON, ChannelsTags.HEADING_SHELF, ChannelsTags.HEADING_OFF)
            .forEach {
                scrollListTo(hasTestTag(it))
                assertTrue(it, exists(it))
            }
        compose
            .onNodeWithTag(ChannelsTags.reason(BRAVO), useUnmergedTree = true)
            .assertTextContains("Nothing in your libraries fits this channel", substring = true)
        assertTrue(!exists(ChannelsTags.reason(ALPHA)))
        compose.onNodeWithTag(ChannelsTags.row(ALPHA)).assertIsSelected()
        // Reset is the list's last line; with the app's type it can sit below the fold, so walk the list to it.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(ChannelsTags.RESET))
        assertTrue(exists(ChannelsTags.RESET))
    }

    @Test
    fun aShelvedRowShowsAndSpeaksItsShelfReason() {
        show(ALPHA)
        until { exists(ChannelsTags.reason(BRAVO)) }
        // Bravo's "nothing to air" line is spoken with its row too; Alpha has no reason line at all.
        compose
            .onNodeWithTag(ChannelsTags.row(BRAVO))
            .assertContentDescriptionContains("Nothing in your libraries fits this channel", substring = true)
        assertTrue(!exists(ChannelsTags.shelfReason(ALPHA)))
        // Charlie is shelved, further down: the band flags can push it past what is composed, so walk the list to it.
        // The row's tag is in the merged tree; the reason line inside it is not, so walk to the row.
        scrollListTo(hasTestTag(ChannelsTags.row(CHARLIE)))
        until { exists(ChannelsTags.shelfReason(CHARLIE)) }
        compose
            .onNodeWithTag(ChannelsTags.shelfReason(CHARLIE), useUnmergedTree = true)
            .assertTextContains(LINEUP_FULL)
        compose
            .onNodeWithTag(ChannelsTags.row(CHARLIE))
            .assertContentDescriptionContains("Charlie", substring = true)
            .assertContentDescriptionContains(LINEUP_FULL, substring = true)
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

    // The screen keeps one focus stop and moves its own focus, so its root says what is focused (polite live region).
    @Test
    fun theRootSpeaksTheFocusedRow_asTheFocusMoves() {
        show(ALPHA)
        until { exists(ChannelsTags.reason(BRAVO)) }
        val screen = compose.onNodeWithTag(ChannelsTags.SCREEN)
        screen.assertContentDescriptionContains("Alpha", substring = true)
        screen.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        press(Pad.Down)
        screen
            .assertContentDescriptionContains("Bravo", substring = true)
            .assertContentDescriptionContains("Nothing in your libraries fits this channel", substring = true)
        // Up from Bravo: Alpha, then New, then the "Use my collections" switch above it.
        press(Pad.Up, Pad.Up)
        screen.assertContentDescriptionContains("New channel", substring = true)
        press(Pad.Up)
        screen.assertContentDescriptionContains("Use my collections", substring = true)
        screen.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
    }

    @Test
    fun groupFlagsShowTheirCountsAndAreHeadings() {
        show(ALPHA)
        until { exists(ChannelsTags.reason(BRAVO)) }
        // On: Alpha and Bravo; More channels: Charlie and Night; Off: Delta.
        // A heading's text is under its own semantics (the merged tree has only the heading), so walk to the heading.
        mapOf(
            ChannelsTags.HEADING_ON to "ON THE AIR \u00B7 2",
            ChannelsTags.HEADING_SHELF to "MORE CHANNELS \u00B7 2",
            ChannelsTags.HEADING_OFF to "SWITCHED OFF \u00B7 1",
        ).forEach { (tag, flag) ->
            scrollListTo(hasTestTag(tag))
            assertTrue(flag, compose.runOnUiThread { nodes(hasText(flag)).isNotEmpty() })
        }
        val spoken =
            mapOf(
                ChannelsTags.HEADING_ON to "On the air, 2 channels",
                ChannelsTags.HEADING_SHELF to "More channels, 2 channels",
                ChannelsTags.HEADING_OFF to "Switched off, 1 channel",
            )
        spoken.forEach { (tag, words) ->
            scrollListTo(hasTestTag(tag))
            val node = compose.runOnUiThread { nodes(hasTestTag(tag)).single() }
            assertTrue("$tag is a heading", node.config.contains(SemanticsProperties.Heading))
            assertEquals(words, node.config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        }
    }

    @Test
    fun useMyCollectionsShowsItsStateAtTheEndOfTheRow() {
        show(ALPHA)
        until { exists(ChannelsTags.HOUSEHOLD_STATE) }
        compose.onNodeWithTag(ChannelsTags.HOUSEHOLD_STATE, useUnmergedTree = true).assertTextEquals("On")
        // Its label and its description line stay on the row, apart from the state.
        compose.onNodeWithTag(ChannelsTags.HOUSEHOLD).assertContentDescriptionContains("Use my collections", true)
    }

    @Test
    fun aChannelRowAndItsActionsPanelShowTheChannelsMark() {
        show(ALPHA)
        until { exists(ChannelsTags.reason(BRAVO)) }
        val row = compose.runOnUiThread { nodes(hasTestTag(ChannelsTags.rowMark(ALPHA))).single() }
        // No logo is bundled for the test channels, so the mark falls back to the initials, as the guide's does.
        assertEquals(MarkKind.Initials, row.config.getOrNull(MarkKindKey))
        press(Pad.Select)
        until { exists(ChannelsTags.PANEL_MARK) }
        val head = compose.runOnUiThread { nodes(hasTestTag(ChannelsTags.PANEL_MARK)).single() }
        assertEquals(MarkKind.Initials, head.config.getOrNull(MarkKindKey))
    }

    @Test
    fun theRootSpeaksTheFocusedChoice_whileThePanelIsOpen() {
        show(ALPHA)
        until { exists(ChannelsTags.reason(BRAVO)) }
        press(Pad.Select)
        until { exists(ChannelsTags.PANEL) }
        val screen = compose.onNodeWithTag(ChannelsTags.SCREEN)
        screen.assertContentDescriptionContains("Watch", substring = true)
        press(Pad.Down)
        screen.assertContentDescriptionContains("Switch off", substring = true)
        press(Pad.Back)
        until { !exists(ChannelsTags.PANEL) }
        screen.assertContentDescriptionContains("Alpha", substring = true)
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
    fun theToggleRowIsFocusableAndFlips() {
        show(null)
        // The list starts on New; the "Use my collections" switch is the stop above it.
        compose.onNodeWithTag(ChannelsTags.NEW_CHANNEL).assertIsSelected()
        press(Pad.Up)
        compose.onNodeWithTag(ChannelsTags.HOUSEHOLD).assertIsSelected()
        compose.onNodeWithTag(ChannelsTags.HOUSEHOLD).assertIsOn()
        press(Pad.Select)
        until { lineup.changes.isNotEmpty() && !lineup.household.value }
        assertEquals(listOf<ChannelChange>(ChannelChange.UseMyCollections(false)), lineup.changes.toList())
        until { nodes(hasTestTag(ChannelsTags.HOUSEHOLD) and isOff()).isNotEmpty() }
        press(Pad.Select)
        until { lineup.household.value }
        compose.onNodeWithTag(ChannelsTags.HOUSEHOLD).assertIsSelected()
        // Down leaves it for New again.
        press(Pad.Down)
        compose.onNodeWithTag(ChannelsTags.NEW_CHANNEL).assertIsSelected()
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
                tier = null,
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
        until { nodes(hasTestTag(BuilderTags.TITLE)).any { titleOf(it) == "Alpha (copy)" } }
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

    // The sheet over Alpha: Select opens the actions panel, and About is its last choice.
    private fun openSheetOnAlpha() {
        show(ALPHA)
        press(Pad.Select)
        until { exists(ChannelsTags.PANEL) }
        repeat(choicesOf(vm.ui.value).lastIndex) { press(Pad.Down) }
        press(Pad.Select)
        until { exists(ChannelsTags.ABOUT) }
    }

    @Test
    fun aboutOpensTheSheetWithTheChannelsPropertiesAndBackReturnsToTheRow() {
        openSheetOnAlpha()
        compose.onNodeWithTag(ChannelsTags.ABOUT_NAME, useUnmergedTree = true).assertTextContains("Alpha")
        until { exists(ChannelsTags.ABOUT_PLAYS) }
        compose
            .onNodeWithTag(ChannelsTags.ABOUT_PLAYS)
            .assertTextContains("Noir Classics", substring = true)
        compose.onNodeWithTag(ChannelsTags.ABOUT_RUNS).assertTextContains("Programmed", substring = true)
        compose.onNodeWithTag(ChannelsTags.ABOUT_CODE).assertTextContains("07EE-7E07", substring = true)
        compose.onNodeWithTag(ChannelsTags.ABOUT_STATUS).assertTextContains("in today's guide", substring = true)

        press(Pad.Back)

        until { !exists(ChannelsTags.ABOUT) }
        compose.onNodeWithTag(ChannelsTags.row(ALPHA)).assertIsSelected()
    }

    @Test
    fun theSheetShowsWhatTheChannelHoldsInTheWordsOfTheChannelBox() {
        openSheetOnAlpha()
        until { exists(ChannelsTags.ABOUT_HOLDS) }

        compose.onNodeWithTag(ChannelsTags.ABOUT_HOLDS).assertTextContains("WHAT IT HOLDS", substring = true)
        compose
            .onNodeWithTag(ChannelsTags.ABOUT_HOLDS_HOURS, useUnmergedTree = true)
            .assertTextEquals("412 hours of programmes (about 17 days) · 37 titles")
        compose.onNodeWithTag(ChannelsTags.ABOUT_HOLDS_LABEL, useUnmergedTree = true).assertTextEquals("Home to:")
        compose
            .onNodeWithTag(ChannelsTags.ABOUT_HOLDS_LIST, useUnmergedTree = true)
            .assertTextEquals("Show 1, Show 2, Show 3, Show 4, Show 5, Show 6 + 31 more")
    }

    @Test
    fun aChannelWithNothingTodayShowsNoHoldingsInTheSheet() {
        show(BRAVO)
        press(Pad.Select)
        until { exists(ChannelsTags.PANEL) }
        repeat(choicesOf(vm.ui.value).lastIndex) { press(Pad.Down) }
        press(Pad.Select)
        until { exists(ChannelsTags.ABOUT_PLAYS) }

        assertTrue(!exists(ChannelsTags.ABOUT_HOLDS))
    }

    @Test
    fun anActionOnTheSheetChangesTheStatusWithoutClosingIt() {
        openSheetOnAlpha()
        until { exists(ChannelsTags.ABOUT_PLAYS) }
        val at = choicesOf(vm.ui.value).indexOf(Choice.SwitchOff)
        repeat(at) { press(Pad.Down) }

        press(Pad.Select)

        until {
            vm.ui.value.rows
                .first { it.id == ALPHA }
                .group == ChannelGroup.Off
        }
        until { exists(ChannelsTags.ABOUT) }
        compose.onNodeWithTag(ChannelsTags.ABOUT_STATUS).assertTextContains("you switched it off", substring = true)
    }

    // The sheet as drawn, for the PR: written to the test package's external files, as the other screenshots are.
    @Test
    fun theSheetScreenshot() {
        openSheetOnAlpha()
        until { exists(ChannelsTags.ABOUT_PLAYS) }
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("no screenshot", bitmap)
        val context = InstrumentationRegistry.getInstrumentation().context
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "screenshots")
        assertTrue("could not create $directory", directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "channel-properties-sheet.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("the screenshot is empty", file.length() > 0)
    }

    private companion object {
        // A 1080p TV at xhdpi, whatever the device is, as the guide's tests host it.
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
        const val TIMEOUT_MS = 5_000L

        // More than any panel lists, so a choice that is not there fails the assertion rather than looping.
        const val MAX_CHOICES = 12
    }

    // #811, #1071: the free tier on a real screen, with the entitlement closed, then opened while the screen is open.
    // Every Pro choice is listed and answers with the Pro note (no store here, so no See Pro): never a silent no-op, a
    // Pro mark on the list or a "coming soon" note.

    // The word Pro on its own, not a longer word that starts with it.
    private val hasBareWordPro =
        SemanticsMatcher("has the word Pro") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { Regex("\\bPro\\b").containsMatchIn(it.text) } == true
        }

    private fun noProWords() =
        nodes(hasText("(Pro)", substring = true)).isEmpty() &&
            nodes(hasBareWordPro).isEmpty() &&
            nodes(hasText("coming soon", substring = true)).isEmpty()

    private fun panelSays(text: String) =
        until { nodes(hasTestTag(ChannelsTags.PANEL_TEXT)).any { node -> titleOf(node)?.contains(text) == true } }

    private fun messageIs(text: String) =
        until { nodes(hasTestTag(ChannelsTags.MESSAGE)).any { node -> titleOf(node)?.contains(text) == true } }

    @Test
    fun free_aStartersSwitchOffAnswersWithTheProNote_thatOnlyCloses() {
        pro.set(false)
        show(ALPHA)
        press(Pad.Select)
        until { exists(ChannelsTags.ACTION_COPY) }
        assertTrue("a Pro word is shown before a gate is chosen", compose.runOnUiThread { noProWords() })
        toChoice(ChannelsTags.ACTION_SWITCH_OFF)

        press(Pad.Select)
        panelSays("part of StationBreak Pro")
        assertTrue("no purchase without a store", !exists(ChannelsTags.ACTION_SEE_PRO))
        compose.onNodeWithTag(ChannelsTags.ACTION_CANCEL).assertIsSelected()
        assertTrue("nothing is sent", lineup.changes.isEmpty())
        press(Pad.Select)
        until { !exists(ChannelsTags.PANEL) }
        assertEquals(
            ChannelStatus.On,
            lineup.list.value
                .first { it.id == ALPHA }
                .state,
        )
    }

    @Test
    fun free_aShelvedStartersSwitchOnAnswersWithTheProNote() {
        pro.set(false)
        show(CHARLIE)
        press(Pad.Select)
        until { exists(ChannelsTags.ACTION_COPY) }
        toChoice(ChannelsTags.ACTION_SWITCH_ON)

        press(Pad.Select)
        panelSays("part of StationBreak Pro")
        assertTrue("nothing is sent", lineup.changes.isEmpty())
    }

    @Test
    fun free_aStartersSheetListsSwitchOffAndAnswersWithTheProNote() {
        pro.set(false)
        show(ALPHA)
        press(Pad.Select)
        until { exists(ChannelsTags.ACTION_ABOUT) }
        toChoice(ChannelsTags.ACTION_ABOUT)
        press(Pad.Select)
        until { exists(ChannelsTags.ABOUT_PLAYS) }
        toChoice(ChannelsTags.ACTION_SWITCH_OFF)

        press(Pad.Select)
        panelSays("part of StationBreak Pro")
        assertTrue("nothing is sent", lineup.changes.isEmpty())
    }

    @Test
    fun free_theViewersOwnCustomChannelStillSwitchesOff() {
        pro.set(false)
        show(ECHO, extra = listOf(summary(ECHO, 5, "Echo", kind = ChannelKind.Custom)))
        press(Pad.Select)
        until { exists(ChannelsTags.ACTION_SWITCH_OFF) }

        press(Pad.Down, Pad.Select)
        until { lineup.changes.isNotEmpty() }
        assertEquals(listOf<ChannelChange>(ChannelChange.SwitchOff(ECHO)), lineup.changes.toList())
    }

    @Test
    fun free_aNumberAStarterHoldsIsOnlyTaken_withNoSwapOffered() {
        pro.set(false)
        show(ECHO, extra = listOf(summary(ECHO, 5, "Echo", kind = ChannelKind.Custom)))
        press(Pad.Select, Pad.Down, Pad.Down, Pad.Down, Pad.Select)
        until { exists(ChannelsTags.PICKER) }
        // 005 to 006: the starter Alpha has it, and a free viewer is not offered a swap with a built-in channel.
        press(Pad.Right, Pad.Right, Pad.Up, Pad.Select)
        messageIs("That number is taken.")
        assertTrue("no swap is offered", !exists(ChannelsTags.ACTION_CONFIRM))
        // Only the picker's first try, refused as taken; no swap is sent.
        assertEquals(listOf<ChannelChange>(ChannelChange.SetNumber(ECHO, 6)), lineup.changes.toList())
        val numbers = lineup.list.value.associate { it.id to it.number }
        assertEquals(5, numbers[ECHO])
        assertEquals(6, numbers[ALPHA])
    }

    @Test
    fun free_atTheCustomChannelLimit_newShowsTheLimitNote() {
        pro.set(false)
        builder.canAdd.value = false
        show(null)
        compose.onNodeWithTag(ChannelsTags.NEW_CHANNEL).assertIsSelected()
        assertTrue("a Pro word is shown", compose.runOnUiThread { noProWords() })

        press(Pad.Select)
        panelSays(
            "Free includes 5 custom channels. Delete some of yours to make room. More is part of StationBreak Pro.",
        )
        assertTrue("no purchase without a store", !exists(ChannelsTags.ACTION_SEE_PRO))
        assertTrue("no builder opens at the limit", !exists(BuilderTags.SCREEN))
        assertTrue("no channel is made", lineup.changes.isEmpty())
    }

    // Down the open panel until the choice tagged [tag] has the D-pad: by what is focused, not by counting presses,
    // as the choices a panel lists depend on the channel and on whether its reasons have been read.
    private fun toChoice(tag: String) {
        repeat(MAX_CHOICES) {
            if (nodes(hasTestTag(tag) and isSelected()).isNotEmpty()) return
            press(Pad.Down)
        }
        compose.onNodeWithTag(tag).assertIsSelected()
    }

    @Test
    fun free_resetIsListed_andAnswersWithTheProNote() {
        pro.set(false)
        show(ALPHA)
        until { exists(ChannelsTags.reason(BRAVO)) }
        assertTrue("a Pro word is shown", compose.runOnUiThread { noProWords() })
        repeat(MAX_CHOICES * 2) {
            if (nodes(hasTestTag(ChannelsTags.RESET) and isSelected()).isEmpty()) press(Pad.Down)
        }
        press(Pad.Select)
        panelSays("part of StationBreak Pro")
        assertEquals(0, lineup.resets.get())
    }

    @Test
    fun free_theViewersOwnCustomChannelRenumbers() {
        pro.set(false)
        show(ECHO, extra = listOf(summary(ECHO, 5, "Echo", kind = ChannelKind.Custom, viewerMade = true)))
        press(Pad.Select)
        until { exists(ChannelsTags.ACTION_CHANGE_NUMBER) }
        toChoice(ChannelsTags.ACTION_CHANGE_NUMBER)

        press(Pad.Select)
        until { exists(ChannelsTags.PICKER) }
        // 005: the middle digit up once is 015, a number nobody holds.
        press(Pad.Right, Pad.Up, Pad.Select)
        until { lineup.changes.isNotEmpty() && !exists(ChannelsTags.PANEL) }
        assertEquals(listOf<ChannelChange>(ChannelChange.SetNumber(ECHO, 15)), lineup.changes.toList())
    }

    // #876 / #805: a starter whose recipe left the catalog is listed as Custom but is still the planner's, so a free
    // viewer who switches it gets the Pro note.
    @Test
    fun free_aRetiredStarterIsTreatedAsThePlannersAndAnswersWithTheProNote() {
        pro.set(false)
        show(ECHO, extra = listOf(summary(ECHO, 5, "Echo", kind = ChannelKind.Custom, viewerMade = false)))
        press(Pad.Select)
        until { exists(ChannelsTags.ACTION_EDIT) }
        toChoice(ChannelsTags.ACTION_SWITCH_OFF)

        press(Pad.Select)
        panelSays("part of StationBreak Pro")
        assertTrue("nothing is sent", lineup.changes.isEmpty())
    }

    @Test
    fun free_atTheCustomChannelLimit_copyToCustomiseShowsTheLimitNote() {
        pro.set(false)
        builder.canAdd.value = false
        show(ALPHA)
        until { exists(ChannelsTags.reason(BRAVO)) }
        press(Pad.Select)
        until { exists(ChannelsTags.ACTION_COPY) }
        toChoice(ChannelsTags.ACTION_COPY)

        press(Pad.Select)
        panelSays(
            "Free includes 5 custom channels. Delete some of yours to make room. More is part of StationBreak Pro.",
        )
        assertTrue("no builder opens at the limit", !exists(BuilderTags.SCREEN))
        assertTrue("no channel is made", lineup.changes.isEmpty())
    }

    @Test
    fun unlockingWhileTheScreenIsOpen_theSameActionWorks() {
        pro.set(false)
        show(ALPHA)
        press(Pad.Select)
        until { exists(ChannelsTags.ACTION_SWITCH_OFF) }

        // Whatever billing reports, flipping the entitlement takes effect on the open screen.
        compose.runOnUiThread { pro.set(true) }
        compose.waitForIdle()

        toChoice(ChannelsTags.ACTION_SWITCH_OFF)
        press(Pad.Select)
        until { lineup.changes.isNotEmpty() }
        assertEquals(listOf<ChannelChange>(ChannelChange.SwitchOff(ALPHA)), lineup.changes.toList())
    }
}
