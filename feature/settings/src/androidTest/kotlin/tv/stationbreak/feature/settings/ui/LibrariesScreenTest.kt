// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.domain.selection.FakeLibraryChoices
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.settings.LibrariesSettingsViewModel
import java.util.concurrent.Executors

private val ALPHA = Library(ItemId("lib-alpha"), "Alpha", LibraryKind.Movies)
private val BRAVO = Library(ItemId("lib-bravo"), "Bravo", LibraryKind.TvShows)
private val CHARLIE = Library(ItemId("lib-charlie"), "Charlie", LibraryKind.Mixed)

@RunWith(AndroidJUnit4::class)
class LibrariesScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // The ViewModel's own thread, as Dispatchers.Default would be in the app.
    private val pool = Executors.newSingleThreadExecutor()
    private val work = pool.asCoroutineDispatcher()
    private var done = 0
    private lateinit var vm: LibrariesSettingsViewModel
    private lateinit var choices: FakeLibraryChoices

    private fun show(
        libraries: List<Library> = listOf(ALPHA, BRAVO),
        chosen: Set<ItemId> = setOf(ALPHA.id),
    ) {
        choices = FakeLibraryChoices(ChoiceView(chosen, emptySet(), gone = false), libraries)
        vm = LibrariesSettingsViewModel(choices, work)
        compose.setContent {
            StationBreakTheme {
                // As in the app: live TV's root is focusable and the screen takes the focus from it.
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).focusable()) {
                    LibrariesRoot(vm, HOLE, onDone = { done++ })
                }
            }
        }
        until { exists(SettingsTags.row(libraries[1].id)) }
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

    private fun until(condition: () -> Boolean) {
        compose.waitUntil(TIMEOUT_MS, condition)
        compose.waitForIdle()
    }

    private fun exists(tag: String): Boolean =
        compose
            .onAllNodes(hasTestTag(tag), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()

    @Test
    fun dPadSelect_ticksTheFocusedRow_andBackFinishes() {
        show()
        compose.onNodeWithTag(SettingsTags.row(ALPHA.id)).assertIsSelected().assertIsOn()
        compose.onNodeWithTag(SettingsTags.row(BRAVO.id)).assertIsOff()

        press(Key.DirectionDown)
        compose.onNodeWithTag(SettingsTags.row(BRAVO.id)).assertIsSelected()
        press(Key.DirectionCenter)

        until { exists(SettingsTags.adding(BRAVO.id)) }
        compose.onNodeWithTag(SettingsTags.row(BRAVO.id)).assertIsOn()
        assertEquals(listOf(setOf(ALPHA.id, BRAVO.id)), choices.changes.toList())
        until { exists(SettingsTags.NOTICE) }

        press(Key.Back)
        assertEquals(1, done)
    }

    // The owner's report: a press flips its row at once and shows it saving while a slow change is out; a press on
    // another row meanwhile is not dropped, and is sent after it.
    @Test
    fun dPadSelect_flipsAtOnce_whileTheChangeIsSlow_andAPressMeanwhileIsKept() {
        show(listOf(ALPHA, BRAVO, CHARLIE))
        val gate = CompletableDeferred<Unit>()
        choices.gate = gate

        press(Key.DirectionCenter)
        compose.onNodeWithTag(SettingsTags.row(ALPHA.id)).assertIsOn()
        until { exists(SettingsTags.NOTICE) }
        assertEquals("the last library stays: nothing sent", 0, choices.changes.size)

        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        compose.onNodeWithTag(SettingsTags.row(BRAVO.id)).assertIsOn()
        until { exists(SettingsTags.saving(BRAVO.id)) }
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        compose.onNodeWithTag(SettingsTags.row(CHARLIE.id)).assertIsOn()
        until { exists(SettingsTags.saving(CHARLIE.id)) }
        assertEquals("one change at a time", 1, choices.changes.size)

        choices.gate = null
        gate.complete(Unit)

        until { exists(SettingsTags.adding(BRAVO.id)) && exists(SettingsTags.adding(CHARLIE.id)) }
        compose.onNodeWithTag(SettingsTags.row(BRAVO.id)).assertIsOn()
        compose.onNodeWithTag(SettingsTags.row(CHARLIE.id)).assertIsOn()
        assertEquals(
            listOf(setOf(ALPHA.id, BRAVO.id), setOf(ALPHA.id, BRAVO.id, CHARLIE.id)),
            choices.changes.toList(),
        )
    }

    // The owner's note: an untick shows Removing at once, until it is stored, as a tick shows Saving.
    @Test
    fun dPadSelect_onATickedRow_showsRemoving_untilItIsStored() {
        show(chosen = setOf(ALPHA.id, BRAVO.id))
        val gate = CompletableDeferred<Unit>()
        choices.gate = gate

        press(Key.DirectionDown)
        press(Key.DirectionCenter)

        compose.onNodeWithTag(SettingsTags.row(BRAVO.id)).assertIsOff()
        until { exists(SettingsTags.removing(BRAVO.id)) }

        choices.gate = null
        gate.complete(Unit)

        until { !exists(SettingsTags.removing(BRAVO.id)) }
        compose.onNodeWithTag(SettingsTags.row(BRAVO.id)).assertIsOff()
        assertEquals(listOf(setOf(ALPHA.id)), choices.changes.toList())
    }

    // The owner's report: reopened, the screen starts on the row it closed on, scrolled into view, not at the top.
    @Test
    fun reopen_scrollsTheRowItClosedOnIntoView() {
        val many = (1..LONG_LIST).map { Library(ItemId("lib-$it"), "Library $it", LibraryKind.Movies) }
        show(many, chosen = setOf(many.first().id))
        repeat(LONG_LIST - 1) { press(Key.DirectionDown) }
        val last = many.last().id
        compose.onNodeWithTag(SettingsTags.row(last)).assertIsSelected().assertIsDisplayed()

        compose.runOnIdle { vm.close() }
        compose.runOnIdle { vm.open() }
        until { exists(SettingsTags.row(last)) }

        compose.onNodeWithTag(SettingsTags.row(last)).assertIsSelected().assertIsDisplayed()
    }

    // The owner's report: the Saved. line under the title pushes the list down, and the focused bottom row, with its
    // status line, was cut off by the panel's bottom edge until focus moved away and back.
    @Test
    fun theFocusedLastRow_staysInsideThePanel_whenTheSavedNoticeAppears() {
        val many = (1..LONG_LIST).map { Library(ItemId("lib-$it"), "Library $it", LibraryKind.Movies) }
        show(many, chosen = setOf(many.first().id))
        repeat(LONG_LIST - 1) { press(Key.DirectionDown) }
        val last = many.last().id
        val gate = CompletableDeferred<Unit>()
        choices.gate = gate

        press(Key.DirectionCenter)
        until { exists(SettingsTags.saving(last)) }
        assertInsideList(SettingsTags.row(last))
        assertInsideList(SettingsTags.saving(last))

        choices.gate = null
        gate.complete(Unit)
        until { exists(SettingsTags.NOTICE) && exists(SettingsTags.adding(last)) }

        compose.onNodeWithTag(SettingsTags.row(last)).assertIsSelected()
        assertInsideList(SettingsTags.row(last))
        assertInsideList(SettingsTags.adding(last))
    }

    private fun assertInsideList(tag: String) {
        val list = compose.onNodeWithTag(SettingsTags.LIST).fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag $node is inside the list $list", node.top >= list.top && node.bottom <= list.bottom)
    }

    private companion object {
        const val LONG_LIST = 30

        // A 1080p TV at xhdpi, whatever the device is, as the channel screen's tests host it.
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
        const val TIMEOUT_MS = 5_000L
    }
}
