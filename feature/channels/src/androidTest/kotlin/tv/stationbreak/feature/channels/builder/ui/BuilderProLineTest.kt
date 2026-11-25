// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.FamilyTier
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.builder.BuildRefusal
import tv.stationbreak.core.domain.lineup.builder.BuildResult
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.DraftFilters
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.FakeCatalogPicker
import tv.stationbreak.core.domain.lineup.builder.FakeChannelBuilder
import tv.stationbreak.core.domain.lineup.builder.FakeChannelPreviews
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.core.domain.lineup.builder.OpenResult
import tv.stationbreak.core.domain.lineup.builder.PickerEntry
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.channels.builder.BuilderEvent
import tv.stationbreak.feature.channels.builder.BuilderFocus
import tv.stationbreak.feature.channels.builder.BuilderViewModel
import tv.stationbreak.feature.channels.builder.SheetRow
import tv.stationbreak.feature.channels.ui.Pad
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Instant

private val MINE = ChannelId("custom:mine")

private object NoProChannels : ChannelRepository {
    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = flowOf(emptyList())

    override fun activeChannels(): Flow<List<ChannelSummary>> = flowOf(emptyList())
}

/**
 * See Pro on the builder's own Pro lines (#1146), on screen: with a store the line offers See Pro and OK with the focus
 * on See Pro; Select sends the event, and Back (or OK) puts the line away with the focus back on the row it came from.
 * Without a store the line is text alone.
 */
@RunWith(AndroidJUnit4::class)
class BuilderProLineTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = LibraryAccount(ServerId("srv1"), UserId("u1"))
    private val library = InMemoryLibraryRepository()
    private val builder = FakeChannelBuilder()
    private val picker = FakeCatalogPicker()
    private val events = CopyOnWriteArrayList<BuilderEvent>()
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

    private fun draft() =
        ChannelDraft(
            "Mine",
            listOf(DraftSource.Library("lib1", "Library 1")),
            emptyList(),
            DraftFilters(),
            PlayOrder.Shuffle,
            3,
            1,
            tier = null,
            number = 600,
            kept = null,
            numberChosen = false,
            origin = MINE,
            seed = Seed(1uL),
        )

    // The sheet of an edit, the store available or not.
    private fun show(store: Boolean) {
        picker.libraries = Listed.Ok(listOf(PickerEntry("lib1", "Library 1")))
        runBlocking { library.useAccount(account) }
        builder.openResult = OpenResult.Opened(draft(), null)
        vm =
            BuilderViewModel(
                library,
                builder,
                picker,
                FakeChannelPreviews(),
                NoProChannels,
                clock,
                work,
                MutableStateFlow(store),
            )
        vm.openEdit(MINE)
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).focusable()) {
                    BuilderRoot(vm, HOLE, onEvent = { events += it })
                }
            }
        }
        until { vm.ui.value.ready && exists(BuilderTags.sheetRow(SheetRow.Name)) }
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

    private fun exists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    // Down the sheet to Save, then Save, which the builder refuses for Pro.
    private fun saveRefusedForPro() {
        repeat(MAX_ROWS) { if (vm.ui.value.focus != BuilderFocus.Sheet(SheetRow.Save)) press(Pad.Down) }
        builder.buildResult = BuildResult.Refused(BuildRefusal.NeedsPro)
        press(Pad.Select)
    }

    @Test
    fun withAStore_theProLineOffersSeeProWithTheFocusOnIt_andSelectSendsTheEvent() {
        show(store = true)
        saveRefusedForPro()
        until { exists(BuilderTags.PRO_SEE) }

        compose.onNodeWithTag(BuilderTags.CONFIRM_TEXT).assertTextContains("StationBreak Pro", substring = true)
        compose.onNodeWithTag(BuilderTags.PRO_SEE).assertTextContains("See Pro")
        compose.onNodeWithTag(BuilderTags.PRO_OK).assertTextContains("OK")
        assertEquals(BuilderFocus.Choice(0), vm.ui.value.focus)

        press(Pad.Select)
        until { events.contains(BuilderEvent.SeePro) }
        assertTrue("the line is still up under the purchase screen", exists(BuilderTags.PRO_SEE))
    }

    @Test
    fun backOnTheProLine_putsItAwayAndTheFocusBackOnTheSaveRow() {
        show(store = true)
        saveRefusedForPro()
        until { exists(BuilderTags.PRO_SEE) }

        press(Pad.Back)
        until { !exists(BuilderTags.PRO_SEE) }

        assertEquals(BuilderFocus.Sheet(SheetRow.Save), vm.ui.value.focus)
        assertTrue("Back dismissed the line and did not leave the builder", events.isEmpty())
    }

    @Test
    fun backFromThePurchaseScreen_findsSeeProAgain_andOkPutsTheLineAway() {
        show(store = true)
        saveRefusedForPro()
        until { exists(BuilderTags.PRO_SEE) }
        press(Pad.Select)
        until { events.contains(BuilderEvent.SeePro) }

        // What the host does when the purchase screen is left with Pro not bought.
        vm.proReturned(owned = false)
        compose.waitForIdle()
        assertEquals(BuilderFocus.Choice(0), vm.ui.value.focus)

        press(Pad.Down, Pad.Select)
        until { !exists(BuilderTags.PRO_SEE) }
        assertEquals(BuilderFocus.Sheet(SheetRow.Save), vm.ui.value.focus)
    }

    @Test
    fun withNoStore_theProLineIsTextAloneAndTheFocusStaysOnTheRow() {
        show(store = false)
        saveRefusedForPro()
        until { exists(BuilderTags.MESSAGE) }

        compose.onNodeWithTag(BuilderTags.MESSAGE).assertTextContains("StationBreak Pro", substring = true)
        assertFalse(exists(BuilderTags.PRO_SEE))
        assertFalse(exists(BuilderTags.PRO_OK))
        assertEquals(BuilderFocus.Sheet(SheetRow.Save), vm.ui.value.focus)
    }

    private companion object {
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
        const val TIMEOUT_MS = 15_000L
        const val MAX_ROWS = 20
    }
}
