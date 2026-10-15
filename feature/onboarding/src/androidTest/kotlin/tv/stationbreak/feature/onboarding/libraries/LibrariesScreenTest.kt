// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.signin.SignInResult
import tv.stationbreak.feature.onboarding.signin.SignedInHandOff

/**
 * The library picker's D-pad behaviour on the API 25 emulator, driven with real key events (spec 05, Unit 4): the
 * first row has default focus, Select toggles a row, Done is reached by Down from the last row and is off with a
 * visible reason when nothing is ticked, and Retry has focus after a zero-library answer or a failed Done. Every key
 * waits for the node that should have focus, because a cold emulator lags (and a click would enter touch mode).
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // One function per behaviour.
class LibrariesScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = FakeMediaServer()
    private val handOff = SignedInHandOff()
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val settings = InMemoryServerSettings(ServerRecord(https))
    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)

    private val movies = Library(ItemId("movies"), "Movies", LibraryKind.Movies)
    private val shows = Library(ItemId("shows"), "TV Shows", LibraryKind.TvShows)
    private val home = Library(ItemId("home"), "Home Videos", LibraryKind.HomeVideos)
    private val musicVideos = Library(ItemId("mv"), "Music Videos", LibraryKind.MusicVideos)

    private var backs = 0
    private var finished = 0
    private var lost = 0
    private var missing = 0

    private fun launch(libraries: List<Library> = listOf(movies, shows, home, musicVideos)) {
        server.librariesResult = ServerResult.Success(libraries)
        handOff.put(SignInResult(session, persisted = true, username = "sam"))
        val viewModel = LibrariesViewModel(server, SessionRepository(InMemorySecretStore(), server), settings, handOff)
        compose.setContent {
            StationBreakTheme {
                LibrariesRoot(
                    viewModel = viewModel,
                    onBack = { backs++ },
                    onDone = { finished++ },
                    onSessionLost = { lost++ },
                    onMissingSession = { missing++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun awaitFocused(tag: String) {
        compose.waitUntil(FOCUS_TIMEOUT_MS) { runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess }
    }

    /** A key goes to the node that should have focus by then, never to whatever focus has reached so far. */
    private fun press(
        keyCode: Int,
        expectedFocus: String,
    ) {
        awaitFocused(expectedFocus)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun row(library: Library) = LibrariesTags.row(library.id.value)

    /** Down from the first row to Done. */
    private fun goToDone() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, row(movies))
        press(KeyEvent.KEYCODE_DPAD_DOWN, row(shows))
        press(KeyEvent.KEYCODE_DPAD_DOWN, row(home))
        press(KeyEvent.KEYCODE_DPAD_DOWN, row(musicVideos))
    }

    @Test
    fun theFirstRowHasDefaultFocus() {
        launch()
        awaitFocused(row(movies))
    }

    @Test
    fun everyRowStartsTickedExceptHomeVideos() {
        launch()
        awaitFocused(row(movies))
        compose.onNodeWithTag(row(movies)).assertIsOn()
        compose.onNodeWithTag(row(shows)).assertIsOn()
        compose.onNodeWithTag(row(home)).assertIsOff()
        compose.onNodeWithTag(row(musicVideos)).assertIsOn()
    }

    @Test
    fun selectTogglesTheFocusedRowBothWays() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_CENTER, row(movies))
        compose.onNodeWithTag(row(movies)).assertIsOff()
        press(KeyEvent.KEYCODE_DPAD_CENTER, row(movies))
        compose.onNodeWithTag(row(movies)).assertIsOn()
        assertEquals(0, finished)
    }

    @Test
    fun downFromTheLastRowReachesDoneAndUpComesBack() {
        launch()
        goToDone()
        awaitFocused(LibrariesTags.DONE)
        compose.onNodeWithTag(LibrariesTags.DONE).assertIsEnabled()
        press(KeyEvent.KEYCODE_DPAD_UP, LibrariesTags.DONE)
        awaitFocused(row(musicVideos))
    }

    @Test
    fun doneSavesTheTickedLibrariesAndFinishes() {
        launch()
        goToDone()
        press(KeyEvent.KEYCODE_DPAD_CENTER, LibrariesTags.DONE)
        compose.waitUntil(FOCUS_TIMEOUT_MS) { finished == 1 }
        assertEquals(setOf(movies.id, shows.id, musicVideos.id), settings.currentLibraries)
    }

    @Test
    fun anEmptySelectionTurnsDoneOffAndSaysWhy() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_CENTER, row(movies))
        press(KeyEvent.KEYCODE_DPAD_DOWN, row(movies))
        press(KeyEvent.KEYCODE_DPAD_CENTER, row(shows))
        press(KeyEvent.KEYCODE_DPAD_DOWN, row(shows))
        press(KeyEvent.KEYCODE_DPAD_DOWN, row(home))
        press(KeyEvent.KEYCODE_DPAD_CENTER, row(musicVideos))

        compose.onNodeWithTag(LibrariesTags.REASON).assertExists()
        compose.onNodeWithTag(LibrariesTags.DONE).assertIsNotEnabled()
        assertTrue(settings.savedLibraries.isEmpty())
    }

    @Test
    fun theReasonIsNotThereWhileSomethingIsTicked() {
        launch()
        awaitFocused(row(movies))
        compose.onNodeWithTag(LibrariesTags.REASON).assertDoesNotExist()
    }

    @Test
    fun aServerWithNoLibrariesSaysSoAndRetryHasFocus() {
        launch(libraries = emptyList())
        awaitFocused(LibrariesTags.RETRY)
        compose.onNodeWithTag(LibrariesTags.ERROR_PANEL).assertExists()
        compose.onNodeWithTag(LibrariesTags.LIST).assertDoesNotExist()

        server.librariesResult = ServerResult.Success(listOf(movies))
        press(KeyEvent.KEYCODE_DPAD_CENTER, LibrariesTags.RETRY)
        awaitFocused(row(movies))
    }

    @Test
    fun aFailedDoneKeepsTheChoiceAndFocusStaysOnDoneWhichIsTheRetry() {
        launch()
        settings.failSave = true
        goToDone()
        press(KeyEvent.KEYCODE_DPAD_CENTER, LibrariesTags.DONE)
        compose.waitUntil(FOCUS_TIMEOUT_MS) { runCatching { compose.onNodeWithTag(LibrariesTags.ERROR_PANEL).assertExists() }.isSuccess }
        awaitFocused(LibrariesTags.DONE)
        // No second button: the message points at Done, which is right below it and still on.
        compose.onNodeWithTag(LibrariesTags.RETRY).assertDoesNotExist()
        compose.onNodeWithTag(LibrariesTags.DONE).assertIsEnabled()
        compose.onNodeWithTag(row(movies)).assertIsOn()
        assertEquals(0, finished)

        settings.failSave = false
        press(KeyEvent.KEYCODE_DPAD_CENTER, LibrariesTags.DONE)
        compose.waitUntil(FOCUS_TIMEOUT_MS) { finished == 1 }
    }

    @Test
    fun aLibraryNamedAfterItsKindShowsTheNameOnceAndAnotherShowsBoth() {
        val concerts = Library(ItemId("concerts"), "Concerts", LibraryKind.MusicVideos)
        launch(libraries = listOf(movies, concerts))
        awaitFocused(row(movies))
        compose.onAllNodesWithText("Movies", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Concerts", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Music videos", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun aRowCarriesItsStateAsTheToggleAloneWithNoSecondDescription() {
        launch()
        awaitFocused(row(movies))
        val config = compose.onNodeWithTag(row(movies)).fetchSemanticsNode().config
        assertTrue(!config.contains(androidx.compose.ui.semantics.SemanticsProperties.StateDescription))
    }

    @Test
    fun backIsHandedToTheCallerNotHandledHere() {
        launch()
        press(KeyEvent.KEYCODE_BACK, row(movies))
        compose.waitUntil(FOCUS_TIMEOUT_MS) { backs == 1 }
        assertEquals(0, finished)
    }

    @Test
    fun noSessionHeldHandsBackToLaunchRoutingAndShowsNoPicker() {
        val viewModel = LibrariesViewModel(server, SessionRepository(InMemorySecretStore(), server), settings, handOff)
        compose.setContent {
            StationBreakTheme {
                LibrariesRoot(viewModel, onBack = {}, onDone = {}, onSessionLost = {}, onMissingSession = { missing++ })
            }
        }
        compose.waitUntil(FOCUS_TIMEOUT_MS) { missing == 1 }
        compose.onNodeWithTag(LibrariesTags.LIST).assertDoesNotExist()
    }

    private companion object {
        const val FOCUS_TIMEOUT_MS = 5_000L
    }
}
