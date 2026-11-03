// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
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
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.builder.BuildResult
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.ChannelPreview
import tv.stationbreak.core.domain.lineup.builder.ChannelPreviews
import tv.stationbreak.core.domain.lineup.builder.DraftFilters
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.FakeCatalogPicker
import tv.stationbreak.core.domain.lineup.builder.FakeChannelBuilder
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.KeptSettings
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.core.domain.lineup.builder.OpenResult
import tv.stationbreak.core.domain.lineup.builder.PickerEntry
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.core.domain.lineup.builder.PreviewItem
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.channels.builder.BuilderFocus
import tv.stationbreak.feature.channels.builder.BuilderStep
import tv.stationbreak.feature.channels.builder.BuilderViewModel
import tv.stationbreak.feature.channels.builder.NameRowKind
import tv.stationbreak.feature.channels.builder.PlayRowKind
import tv.stationbreak.feature.channels.builder.PreviewView
import tv.stationbreak.feature.channels.builder.SourceKindTab
import tv.stationbreak.feature.channels.ui.Pad
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Instant

private val BUILT_IN = ChannelId("starter:movies")

/** A preview that waits for a gate a test opens, so "working" can be seen for as long as the test needs. */
private class GatedPreviews(
    val answer: ChannelPreview,
) : ChannelPreviews {
    val gate = CompletableDeferred<Unit>()

    override suspend fun preview(
        account: LibraryAccount,
        draft: ChannelDraft,
        at: Instant,
    ): ChannelPreview {
        gate.await()
        return answer
    }
}

private object NoChannels : ChannelRepository {
    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = flowOf(emptyList())

    override fun activeChannels(): Flow<List<ChannelSummary>> = flowOf(emptyList())
}

/** The channel builder's three steps on screen (spec 22): the pages, the D-pad that drives them and the text entry. */
@RunWith(AndroidJUnit4::class)
class BuilderStepsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = LibraryAccount(ServerId("srv1"), UserId("u1"))
    private val library = InMemoryLibraryRepository()
    private val builder = FakeChannelBuilder()
    private val picker = FakeCatalogPicker()
    private val exits = CopyOnWriteArrayList<ChannelId?>()

    // The ViewModel's own thread, as Dispatchers.Default would be in the app.
    private val pool = Executors.newSingleThreadExecutor()
    private val work = pool.asCoroutineDispatcher()
    private lateinit var vm: BuilderViewModel

    private val clock =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(T0)
        }

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun show(
        previews: ChannelPreviews = GatedPreviews(ChannelPreview.NothingFits(emptyList())),
        open: (BuilderViewModel) -> Unit = { it.openNew() },
    ) {
        picker.libraries = Listed.Ok(listOf(PickerEntry("lib1", "Library 1")))
        runBlocking { library.useAccount(account) }
        vm = BuilderViewModel(library, builder, picker, previews, NoChannels, clock, work)
        open(vm)
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).focusable()) {
                    BuilderRoot(vm, HOLE, onEvent = { exits += it.tune() })
                }
            }
        }
        until { vm.ui.value.ready && exists(BuilderTags.entry("lib1")) }
    }

    private fun press(vararg pads: Pad) {
        pads.forEach { pad ->
            compose.onRoot().performKeyInput { pressKey(keyOf(pad)) }
            compose.waitForIdle()
        }
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

    // From a fresh Sources page: onto the first library, tick it, down to Narrow down, right to Next, and on.
    private fun toPlay() {
        press(Pad.Right, Pad.Select, Pad.Down, Pad.Right, Pad.Select)
        until { vm.ui.value.step == BuilderStep.Play }
    }

    // Down the Play rows (Order, series, films, kids) and onto Next, then on.
    private fun toName() {
        toPlay()
        press(Pad.Down, Pad.Down, Pad.Down, Pad.Down, Pad.Select)
        until { vm.ui.value.step == BuilderStep.Name }
    }

    @Test
    fun sourcesStepShowsTabsAndTicksWithSelect() {
        show()
        SourceKindTab.entries.forEach { assertTrue(it.name, exists(BuilderTags.tab(it))) }
        press(Pad.Right, Pad.Select)
        compose.onNodeWithTag(BuilderTags.entry("lib1")).assertIsOn()
        assertEquals(
            listOf("Library 1"),
            vm.ui.value.draft.sources
                .map { it.label },
        )
        press(Pad.Select)
        assertTrue(
            vm.ui.value.draft.sources
                .isEmpty(),
        )
    }

    @Test
    fun nextIsDimmedUntilASourceIsTicked() {
        show()
        compose.onNodeWithTag(BuilderTags.NEXT).assertIsNotEnabled()
        press(Pad.Right, Pad.Select)
        compose.onNodeWithTag(BuilderTags.NEXT).assertIsEnabled()
    }

    @Test
    fun playStepShowsOrderHintsAndHidesCapsForInOrder() {
        show()
        toPlay()
        hint().assertTextContains("Random order", substring = true)
        assertTrue(exists(BuilderTags.playRow(PlayRowKind.SeriesCap)))
        press(Pad.Right)
        hint().assertTextContains("take turns", substring = true)
        press(Pad.Right)
        hint().assertTextContains("start to finish", substring = true)
        assertTrue(!exists(BuilderTags.playRow(PlayRowKind.SeriesCap)))
        assertTrue(!exists(BuilderTags.playRow(PlayRowKind.FilmCap)))
    }

    @Test
    fun leftRightChangeACap() {
        show()
        toPlay()
        press(Pad.Down, Pad.Right)
        compose.onNodeWithTag(BuilderTags.playRow(PlayRowKind.SeriesCap)).assertTextContains("4", substring = true)
        assertEquals(4, vm.ui.value.draft.seriesCap)
        press(Pad.Left, Pad.Left)
        compose.onNodeWithTag(BuilderTags.playRow(PlayRowKind.SeriesCap)).assertTextContains("2", substring = true)
        assertEquals(2, vm.ui.value.draft.seriesCap)
    }

    @Test
    fun nameStepShowsSuggestedNameNumberAndSummary() {
        show()
        toName()
        compose.onNodeWithTag(BuilderTags.nameRow(NameRowKind.Name)).assertTextContains("Library 1")
        compose.onNodeWithTag(BuilderTags.nameRow(NameRowKind.Number)).assertTextContains("600", substring = true)
        compose.onNodeWithTag(BuilderTags.SUMMARY).assertTextContains("Library 1", substring = true)
        compose.onNodeWithTag(BuilderTags.SUMMARY).assertTextContains("Shuffle", substring = true)
    }

    @Test
    fun saveAndWatchCallsOnExitWithTheNewChannel() {
        show()
        toName()
        press(Pad.Down, Pad.Down, Pad.Select)
        until { exits.isNotEmpty() }
        assertEquals(listOf<ChannelId?>(ChannelId("custom:fake")), exits.toList())
        assertEquals(1, builder.created.size)
    }

    @Test
    fun backWalksTheStepsAndAsksToLeave() {
        show()
        toName()
        press(Pad.Back)
        assertEquals(BuilderStep.Play, vm.ui.value.step)
        press(Pad.Back)
        assertEquals(BuilderStep.Sources, vm.ui.value.step)
        press(Pad.Back)
        until { exists(BuilderTags.CONFIRM) }
        compose.onNodeWithTag(BuilderTags.CONFIRM_TEXT).assertTextContains("Leave without saving?")
        compose.onNodeWithTag(BuilderTags.CONFIRM_NO).assertTextContains("Keep building")
        // Back puts the question away; the builder stays.
        press(Pad.Back)
        assertTrue(!exists(BuilderTags.CONFIRM))
        assertTrue(exits.isEmpty())
        press(Pad.Back, Pad.Up, Pad.Select)
        until { exits.isNotEmpty() }
        assertEquals(listOf<ChannelId?>(null), exits.toList())
    }

    @Test
    fun thePreviewLineShowsWorkingThenItems() {
        val film = PreviewItem(CanonicalId.parse("a".repeat(32)), "Film", null, null, null, 95)
        val previews = GatedPreviews(ready(film))
        show(previews)
        press(Pad.Right, Pad.Select)
        until { exists(BuilderTags.PREVIEW) }
        compose.onNodeWithTag(BuilderTags.PREVIEW).assertTextContains("Working it out")
        previews.gate.complete(Unit)
        until { vm.ui.value.preview is PreviewView.Ready }
        compose.onNodeWithTag(BuilderTags.PREVIEW).assertTextContains("Film (95 min)", substring = true)
        // The count carries the total runtime of the pool.
        compose.onNodeWithTag(BuilderTags.BAR).assertTextContains("1 fit, 1 h 35 min of programmes")
    }

    @Test
    fun anEpisodeItemIsFormattedWithItsSeasonAndEpisode() {
        val previews = GatedPreviews(ready(PreviewItem(CanonicalId.parse("b".repeat(32)), "Pilot", "Show A", 1, 3, 22)))
        previews.gate.complete(Unit)
        show(previews)
        press(Pad.Right, Pad.Select)
        until { vm.ui.value.preview is PreviewView.Ready }
        compose.onNodeWithTag(BuilderTags.PREVIEW).assertTextContains("Show A S1E3 (22 min)", substring = true)
    }

    @Test
    fun aKeptItemIsFormattedFromItsResource() {
        val source = DraftSource.Library("lib1", "Library 1")
        val kept = KeptSettings("Movies", listOf(KeptItem.BlockLength(4)))
        val draft =
            ChannelDraft(
                "Movies (copy)",
                listOf(source),
                emptyList(),
                DraftFilters(),
                PlayOrder.Shuffle,
                3,
                1,
                kids = false,
                number = 600,
                kept = kept,
                origin = BUILT_IN,
                seed = Seed(1uL),
            )
        builder.openResult = OpenResult.Opened(draft, "Movies")
        show { it.openCopy(BUILT_IN) { name -> "$name (copy)" } }
        press(Pad.Right, Pad.Down, Pad.Right, Pad.Select)
        until { vm.ui.value.step == BuilderStep.Play }
        compose.onNodeWithTag(BuilderTags.kept(0)).assertTextContains("4 in a row")
    }

    @Test
    fun theKeyboardTextReachesTheName() {
        show()
        toName()
        press(Pad.Select)
        until { exists(BuilderTags.NAME_INPUT) }
        compose.onNodeWithTag(BuilderTags.NAME_INPUT).performTextReplacement("Mine")
        compose.waitForIdle()
        assertEquals("Mine", vm.ui.value.draft.name)
    }

    @Test
    fun doneOnTheKeyboardReturnsTheDPad() {
        show()
        toName()
        press(Pad.Select)
        until { exists(BuilderTags.NAME_INPUT) }
        compose.onNodeWithTag(BuilderTags.NAME_INPUT).performImeAction()
        until { !exists(BuilderTags.NAME_INPUT) }
        press(Pad.Down)
        assertEquals(BuilderFocus.NameRow(NameRowKind.Number), vm.ui.value.focus)
    }

    @Test
    fun backWhileTypingKeepsTheBuilder() {
        show()
        toName()
        press(Pad.Select)
        until { exists(BuilderTags.NAME_INPUT) }
        press(Pad.Back)
        until { !exists(BuilderTags.NAME_INPUT) }
        assertTrue(!exists(BuilderTags.CONFIRM))
        assertTrue(exits.isEmpty())
        assertEquals(BuilderStep.Name, vm.ui.value.step)
        press(Pad.Down)
        assertEquals(BuilderFocus.NameRow(NameRowKind.Number), vm.ui.value.focus)
    }

    @Test
    fun theKeyboardTextReachesTheSearch() {
        show()
        press(Pad.Down, Pad.Down, Pad.Down, Pad.Down, Pad.Right, Pad.Select)
        until { exists(BuilderTags.SEARCH_INPUT) }
        compose.onNodeWithTag(BuilderTags.SEARCH_INPUT).performTextReplacement("Show")
        compose.waitForIdle()
        assertEquals("Show", vm.ui.value.searchText)
    }

    @Test
    fun aRefusedSaveShowsItsMessage() {
        builder.buildResult = BuildResult.SaveFailed
        show()
        toName()
        press(Pad.Down, Pad.Down, Pad.Select)
        until { exists(BuilderTags.MESSAGE) }
        compose.onNodeWithTag(BuilderTags.MESSAGE).assertTextContains("save that", substring = true)
    }

    @Test
    fun aProblemWithTheDraftIsShownOnItsPage() {
        show()
        toName()
        press(Pad.Select)
        until { exists(BuilderTags.NAME_INPUT) }
        compose.onNodeWithTag(BuilderTags.NAME_INPUT).performTextReplacement("")
        until { exists(BuilderTags.PROBLEM) }
        compose.onNodeWithTag(BuilderTags.PROBLEM).assertTextContains("name", substring = true)
    }

    @Test
    fun turningTheKidsSwitchOffAsksFirst() {
        show()
        toPlay()
        press(Pad.Down, Pad.Down, Pad.Down, Pad.Select)
        until { vm.ui.value.draft.kids }
        press(Pad.Select)
        until { exists(BuilderTags.CONFIRM) }
        compose.onNodeWithTag(BuilderTags.CONFIRM_TEXT).assertTextContains("no longer be held", substring = true)
        compose.onNodeWithTag(BuilderTags.CONFIRM_NO).assertTextContains("Keep it on")
    }

    @Test
    fun aPreviewThatCannotBeWorkedOutSaysSo() {
        val previews = GatedPreviews(ChannelPreview.Unavailable)
        previews.gate.complete(Unit)
        show(previews)
        press(Pad.Right, Pad.Select)
        until { vm.ui.value.preview is PreviewView.Unavailable }
        compose.onNodeWithTag(BuilderTags.PREVIEW).assertTextContains("not available", substring = true)
    }

    @Test
    fun aPoolWithNothingInItSaysSo() {
        val previews = GatedPreviews(ChannelPreview.NothingFits(emptyList()))
        previews.gate.complete(Unit)
        show(previews)
        press(Pad.Right, Pad.Select)
        until { vm.ui.value.preview is PreviewView.NothingFits }
        compose.onNodeWithTag(BuilderTags.PREVIEW).assertTextContains("Nothing in your libraries", substring = true)
    }

    private fun hint() = compose.onNodeWithTag(BuilderTags.ORDER_HINT, useUnmergedTree = true)

    private fun ready(item: PreviewItem) = ChannelPreview.Ready(1, item.minutes.toLong(), listOf(item), emptyList())

    private companion object {
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
        const val TIMEOUT_MS = 5_000L
    }
}
