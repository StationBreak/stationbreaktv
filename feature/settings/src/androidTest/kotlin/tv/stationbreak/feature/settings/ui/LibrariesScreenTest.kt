// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
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
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun show() {
        choices = FakeLibraryChoices(ChoiceView(setOf(ALPHA.id), emptySet(), gone = false), listOf(ALPHA, BRAVO))
        vm = LibrariesSettingsViewModel(choices, work)
        compose.setContent {
            StationBreakTheme {
                // As in the app: live TV's root is focusable and the screen takes the focus from it.
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).focusable()) {
                    LibrariesRoot(vm, HOLE, onDone = { done++ })
                }
            }
        }
        until { exists(SettingsTags.row(BRAVO.id)) }
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

    private companion object {
        // A 1080p TV at xhdpi, whatever the device is, as the channel screen's tests host it.
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
        const val TIMEOUT_MS = 5_000L
    }
}
