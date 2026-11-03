// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
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
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.DraftFilters
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.FakeCatalogPicker
import tv.stationbreak.core.domain.lineup.builder.FakeChannelBuilder
import tv.stationbreak.core.domain.lineup.builder.FakeChannelPreviews
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.KeptSettings
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
import tv.stationbreak.feature.channels.builder.BuilderFocus
import tv.stationbreak.feature.channels.builder.BuilderStep
import tv.stationbreak.feature.channels.builder.BuilderViewModel
import tv.stationbreak.feature.channels.builder.SheetRow
import tv.stationbreak.feature.channels.ui.Pad
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Instant

private val MINE = ChannelId("custom:mine")

private object NoSheetChannels : ChannelRepository {
    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = flowOf(emptyList())

    override fun activeChannels(): Flow<List<ChannelSummary>> = flowOf(emptyList())
}

/** The edit sheet of a custom channel on screen (spec 22, F2.3): its rows, what OK does on them, and the questions. */
@RunWith(AndroidJUnit4::class)
class EditSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = LibraryAccount(ServerId("srv1"), UserId("u1"))
    private val library = InMemoryLibraryRepository()
    private val builder = FakeChannelBuilder()
    private val picker = FakeCatalogPicker()
    private val exits = CopyOnWriteArrayList<ChannelId?>()
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

    private fun draftOf(
        order: PlayOrder? = PlayOrder.Shuffle,
        kept: KeptSettings? = null,
    ) = ChannelDraft(
        "Mine",
        listOf(DraftSource.Library("lib1", "Library 1")),
        emptyList(),
        DraftFilters(),
        order,
        3,
        1,
        kids = false,
        number = 600,
        kept = kept,
        numberChosen = false,
        origin = MINE,
        seed = Seed(1uL),
    )

    private fun show(draft: ChannelDraft = draftOf()) {
        picker.libraries = Listed.Ok(listOf(PickerEntry("lib1", "Library 1")))
        runBlocking { library.useAccount(account) }
        builder.openResult = OpenResult.Opened(draft, null)
        vm = BuilderViewModel(library, builder, picker, FakeChannelPreviews(), NoSheetChannels, clock, work)
        vm.openEdit(MINE)
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).focusable()) {
                    BuilderRoot(vm, HOLE, onExit = { exits += it })
                }
            }
        }
        until { vm.ui.value.ready && exists(BuilderTags.sheetRow(SheetRow.Name)) }
    }

    // Opens the same sheet again on another draft, as the host does for the next channel.
    private fun reopen(draft: ChannelDraft) {
        builder.openResult = OpenResult.Opened(draft, null)
        vm.openEdit(MINE)
        until { vm.ui.value.draft == draft && vm.ui.value.onSheet }
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

    // Down the sheet until the D-pad is on [row].
    private fun toRow(row: SheetRow) {
        repeat(MAX_ROWS) { if (vm.ui.value.focus != BuilderFocus.Sheet(row)) press(Pad.Down) }
        assertEquals(BuilderFocus.Sheet(row), vm.ui.value.focus)
    }

    private fun top(row: SheetRow) = compose.onNodeWithTag(BuilderTags.sheetRow(row)).getBoundsInRoot().top

    @Test
    fun theSheetDrawsEveryRowOfSheetRowsInOrder() {
        show()
        val rows = vm.ui.value.sheetRows
        // Nothing but the rows: no kept lines for a draft that keeps nothing, and the sheet's own header.
        assertFalse(exists(BuilderTags.SHEET_KEPT_FROM))
        assertFalse(exists(BuilderTags.kept(0)))
        compose.onNodeWithTag(BuilderTags.EDIT).assertTextContains("Edit")
        assertFalse(exists(BuilderTags.step(BuilderStep.Sources)))
        assertEquals(SheetRow.entries - SheetRow.Kept, rows)
        var previous: SheetRow? = null
        rows.forEach { row ->
            toRow(row)
            assertTrue(row.name, exists(BuilderTags.sheetRow(row)))
            // The list scrolls, so the row above may have gone; when it is still drawn it is above this one.
            val above = previous
            if (above != null && exists(BuilderTags.sheetRow(above))) {
                assertTrue(row.name, top(above) < top(row))
            }
            previous = row
            // Whatever is drawn is a row of the list, never an extra one.
            val tags =
                compose
                    .onAllNodes(
                        SemanticsMatcher("sheet row") {
                            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("builder:sheet:") ==
                                true
                        },
                    ).fetchSemanticsNodes()
                    .map { it.config[SemanticsProperties.TestTag] }
            assertTrue(tags.toString(), tags.all { tag -> rows.any { BuilderTags.sheetRow(it) == tag } })
        }
    }

    @Test
    fun okOnSourcesReplacesTheSheetWithThePickerAndBackReturnsToTheRow() {
        show()
        toRow(SheetRow.Sources)
        assertTrue(vm.ui.value.onSheet)
        press(Pad.Select)
        until { exists(BuilderTags.entry("lib1")) }
        assertFalse(vm.ui.value.onSheet)
        assertFalse(exists(BuilderTags.sheetRow(SheetRow.Name)))
        // Back from the picker returns to the sheet, on the row it came from.
        press(Pad.Back)
        until { exists(BuilderTags.sheetRow(SheetRow.Sources)) }
        assertTrue(vm.ui.value.onSheet)
        assertEquals(BuilderFocus.Sheet(SheetRow.Sources), vm.ui.value.focus)
    }

    @Test
    fun reshuffleIsHiddenForRotationAndInOrderAndWhileDirty() {
        show()
        toRow(SheetRow.Reshuffle)
        assertTrue(exists(BuilderTags.sheetRow(SheetRow.Reshuffle)))
        // An unsaved change takes it away.
        toRow(SheetRow.Kids)
        press(Pad.Select)
        until { vm.ui.value.dirty }
        assertFalse(SheetRow.Reshuffle in vm.ui.value.sheetRows)
        toRow(SheetRow.Delete)
        assertFalse(exists(BuilderTags.sheetRow(SheetRow.Reshuffle)))
        reopen(draftOf(PlayOrder.Rotation))
        assertFalse(SheetRow.Reshuffle in vm.ui.value.sheetRows)
        assertFalse(exists(BuilderTags.sheetRow(SheetRow.Reshuffle)))
        reopen(draftOf(PlayOrder.InOrder))
        assertFalse(SheetRow.Reshuffle in vm.ui.value.sheetRows)
        assertFalse(SheetRow.SeriesCap in vm.ui.value.sheetRows)
        assertFalse(exists(BuilderTags.sheetRow(SheetRow.Reshuffle)))
    }

    @Test
    fun reshuffleSavesAndSaysSaved() {
        show()
        toRow(SheetRow.Reshuffle)
        press(Pad.Select)
        until { exists(BuilderTags.MESSAGE) }
        compose.onNodeWithTag(BuilderTags.MESSAGE).assertTextContains("Saved")
        assertTrue("reshuffle" in builder.calls)
    }

    @Test
    fun deleteAsksAndFocusesCancel() {
        show()
        toRow(SheetRow.Delete)
        press(Pad.Select)
        until { exists(BuilderTags.CONFIRM) }
        compose.onNodeWithTag(BuilderTags.CONFIRM_TEXT).assertTextContains("Delete Mine?")
        compose.onNodeWithTag(BuilderTags.CONFIRM_NO).assertTextContains("Cancel")
        assertEquals(BuilderFocus.Choice(1), vm.ui.value.focus)
        assertFalse("delete" in builder.calls)
    }

    @Test
    fun saveWritesAndClosesTheSheet() {
        show()
        toRow(SheetRow.Kids)
        press(Pad.Select)
        until { vm.ui.value.dirty }
        compose.onNodeWithTag(BuilderTags.sheetRow(SheetRow.Kids)).assertIsOn()
        toRow(SheetRow.Save)
        press(Pad.Select)
        until { exits.isNotEmpty() }
        assertEquals(1, builder.saved.size)
        assertTrue(
            builder.saved
                .single()
                .second.kids,
        )
        assertEquals(listOf<ChannelId?>(null), exits.toList())
    }

    @Test
    fun backWithChangesAsksKeepEditing() {
        show()
        toRow(SheetRow.Kids)
        press(Pad.Select)
        until { vm.ui.value.dirty }
        press(Pad.Back)
        until { exists(BuilderTags.CONFIRM) }
        compose.onNodeWithTag(BuilderTags.CONFIRM_TEXT).assertTextContains("Leave without saving?")
        compose.onNodeWithTag(BuilderTags.CONFIRM_NO).assertTextContains("Keep editing")
        assertEquals(BuilderFocus.Choice(1), vm.ui.value.focus)
        assertTrue(exits.isEmpty())
    }

    @Test
    fun theKeptLineAndItsActionShowOnlyForAKeptDraft() {
        show()
        assertFalse(SheetRow.Kept in vm.ui.value.sheetRows)
        assertFalse(exists(BuilderTags.sheetRow(SheetRow.Kept)))
        assertFalse(exists(BuilderTags.SHEET_KEPT_FROM))
        reopen(draftOf(kept = KeptSettings("Movies", listOf(KeptItem.BlockLength(4)))))
        toRow(SheetRow.Kept)
        compose.onNodeWithTag(BuilderTags.SHEET_KEPT_FROM).assertTextContains("Kept from Movies")
        compose.onNodeWithTag(BuilderTags.kept(0)).assertTextContains("4 in a row")
        compose
            .onNodeWithTag(BuilderTags.sheetRow(SheetRow.Kept))
            .assertTextContains("builder's settings", substring = true)
        press(Pad.Select)
        until { SheetRow.Kept !in vm.ui.value.sheetRows }
        assertFalse(exists(BuilderTags.sheetRow(SheetRow.Kept)))
        assertFalse(exists(BuilderTags.SHEET_KEPT_FROM))
    }

    private companion object {
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
        const val TIMEOUT_MS = 5_000L
        const val MAX_ROWS = 16
    }
}
