// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.view.KeyEvent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.app.di.MediaServerModule
import tv.stationbreak.app.di.PlayerModule
import tv.stationbreak.app.nav.AppNavGraph
import tv.stationbreak.app.nav.LibrariesRoute
import tv.stationbreak.app.nav.ServerRoute
import tv.stationbreak.app.nav.SignInRoute
import tv.stationbreak.app.nav.afterSignOut
import tv.stationbreak.core.domain.AccountCache
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.SelectionStore
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
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
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.libraries.LibrariesTags
import tv.stationbreak.feature.onboarding.server.ServerTags
import tv.stationbreak.feature.onboarding.signin.SignInTags
import javax.inject.Singleton
import kotlin.time.Clock

/** One scripted server for the whole graph, shared with the test through this holder. */
private object Scripted {
    val server = FakeMediaServer()
    val store = InMemorySecretStore()
    var settings = InMemoryServerSettings()
}

/**
 * The app's navigation between the server screen and sign-in, on API 25 under the real Hilt graph (the adapter and the
 * Keystore swapped for fakes): Continue opens sign-in, Back returns to the server screen with the address kept, a
 * finished sign-in hands the session to the next destination, and a sign-in restored without its server falls back to
 * the server screen with nothing left under it.
 */
@HiltAndroidTest
@UninstallModules(MediaServerModule::class, PlayerModule::class)
@RunWith(AndroidJUnit4::class)
class SignInNavigationTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    // The selection's resume, as the app's start runs it: where this test's app starts, after its seeds (spec 26).
    @get:Rule(order = 1)
    val selection = SelectionResumeRule(atStart = false)

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    /**
     * The graph with the scripted server and an in-memory store in place of the adapter and the Keystore. It is
     * nested, so it applies to this test only.
     */
    @Module
    @InstallIn(SingletonComponent::class)
    object ScriptedServerModule {
        @Provides
        @Singleton
        fun mediaServer(): MediaServer = Scripted.server

        @Provides
        @Singleton
        fun secretStore(): SecretStore = Scripted.store

        @Provides
        @Singleton
        fun serverSettings(): ServerSettings = Scripted.settings

        @Provides
        fun selectionStore(): SelectionStore = Scripted.settings

        @Provides
        @Singleton
        fun sessions(
            store: SecretStore,
            server: MediaServer,
            library: AccountCache,
        ): SessionRepository = SessionRepository(store, server, library)

        // The live screen is in the graph: its clock, and a player that is never made (no real HTTP client here).
        @Provides
        fun clock(): Clock = Clock.System

        @Provides
        fun players(): VideoPlayerFactory = LiveTestBindings.players
    }

    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val http = ServerAddress(Scheme.Http, "jellyfin.example.test", 8096)

    @Before
    fun script() {
        Scripted.server.discoverResult = ServerResult.Success(ResolvedServer(https, info))
        Scripted.server.signInResult =
            ServerResult.Failure(
                tv.stationbreak.core.domain.MediaServerError
                    .Unknown(),
            )
        Scripted.server.signInCalls.clear()
        val movies = Library(ItemId("movies"), "Movies", LibraryKind.Movies)
        Scripted.server.librariesResult = ServerResult.Success(listOf(movies))
        Scripted.store
            .snapshot()
            .keys
            .forEach { key -> kotlinx.coroutines.runBlocking { Scripted.store.clear(key) } }
        Scripted.store.writtenKeys.clear()
        Scripted.settings = InMemoryServerSettings()
        hilt.inject()
    }

    /**
     * A key for the screen, handed to the activity's window, so it does not pass the input method first. After the
     * programmatic Go (`performImeAction`) the keyboard still holds the field's session, and on a slow emulator it
     * took a Select sent right after (Continue's) before the app saw it: the key never reached Compose, and sign-in
     * never opened. The keyboard is not what these tests are about.
     */
    private fun press(keyCode: Int) {
        compose.awaitWindowFocus()
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        compose.waitForIdle()
    }

    private fun launchApp() {
        selection.resume()
        // The graph from the server screen, as a first launch reaches it. LaunchNavigationTest covers launch routing.
        compose.setContent { StationBreakTheme { AppNavGraph(startDestination = ServerRoute()) } }
        compose.waitForIdle()
    }

    /** Types an address on the server screen with the keyboard's text input, tests it and presses Continue. */
    private fun testAddressAndContinue(address: String = "jellyfin.example.test") {
        // Each key goes to the node that should have focus by then; on a cold emulator focus can lag the first key.
        awaitFocused(ServerTags.FIELD)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        typeIntoField(address)
        // Go on the keyboard runs Test.
        compose.onNodeWithTag(ServerTags.FIELD_INPUT).performImeAction()
        compose.waitForIdle()
        awaitFocused(ServerTags.CONTINUE)
    }

    /** Types the address with [typeWhenReady], which waits out a cold emulator's IME. */
    private fun typeIntoField(address: String) = compose.typeWhenReady(ServerTags.FIELD_INPUT, address)

    /** The Test runs off the main thread; on a cold emulator its result can land after the idle check. */
    private fun awaitFocused(tag: String) {
        try {
            compose.awaitFocusedForKeys(tag, BACK_TIMEOUT_MS)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("${e.message}; ${describeScreen()}", e)
        }
    }

    /** Which onboarding nodes exist, which is focused and which windows have input focus (tags only, no values). */
    private fun describeScreen(): String {
        val tags =
            listOf(
                ServerTags.FIELD,
                ServerTags.FIELD_INPUT,
                ServerTags.TEST,
                ServerTags.CANCEL,
                ServerTags.CONTINUE,
                SignInTags.USERNAME,
                SignInTags.USERNAME_INPUT,
                SignInTags.PASSWORD,
            )
        val present = tags.filter { compose.onAllNodesWithTag(it).fetchSemanticsNodes(false).isNotEmpty() }
        val focused =
            compose
                .onAllNodes(isFocused())
                .fetchSemanticsNodes(false)
                .map { it.config.getOrNull(SemanticsProperties.TestTag) ?: "(untagged)" }
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes(false)
        val windows = compose.runOnUiThread { roots.map { (it.root as? ViewRootForTest)?.view?.hasWindowFocus() } }
        return "screen has $present, focused $focused, windows with input focus $windows"
    }

    // #864: a sign-out from Settings, Libraries lands on the server screen, alone on the stack, not on sign-in.
    @Test
    fun afterASignOutTheServerScreenShowsAloneSoAServerCanBeChosenAgain() {
        selection.resume()
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            StationBreakTheme { AppNavGraph(startDestination = ServerRoute(), navController = nav) }
        }
        compose.waitForIdle()
        testAddressAndContinue()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Sign-in opens after a navigation; on a cold emulator its first focus can land after the key returns.
        awaitFocused(SignInTags.USERNAME)

        compose.runOnUiThread { nav.afterSignOut() }
        compose.waitForIdle()

        awaitFocused(ServerTags.FIELD)
        compose.onNodeWithTag(SignInTags.USERNAME).assertDoesNotExist()
        val alone = compose.runOnUiThread { nav.previousBackStackEntry == null }
        assertTrue("the server screen is alone on the stack", alone)
        assertTrue(compose.runOnUiThread { nav.currentDestination?.hasRoute<ServerRoute>() == true })
    }

    @Test
    fun continueOpensSignInWithTheUsernameFocused() {
        launchApp()
        testAddressAndContinue()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Sign-in opens after a navigation; on a cold emulator its first focus can land after the key returns.
        awaitFocused(SignInTags.USERNAME)
        compose.onNodeWithTag(SignInTags.NOT_ENCRYPTED).assertDoesNotExist()
    }

    @Test
    fun backFromSignInReturnsToTheServerScreenWithTheAddressKept() {
        launchApp()
        testAddressAndContinue()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Sign-in opens after a navigation; on a cold emulator its first focus can land after the key returns.
        awaitFocused(SignInTags.USERNAME)

        press(KeyEvent.KEYCODE_BACK)

        compose.onNodeWithTag(ServerTags.FIELD).assert(hasText("jellyfin.example.test"))
        awaitFocused(ServerTags.CONTINUE)
        compose.onNodeWithTag(SignInTags.USERNAME).assertDoesNotExist()
    }

    @Test
    fun aFinishedSignInOpensTheLibraryPickerAndStoresTheServer() {
        val token = AccessToken("dummy-token-0001")
        val session = Session(UserId("user1"), token, info, https, null, isAdministrator = true)
        Scripted.server.signInResult = ServerResult.Success(session)
        launchApp()
        testAddressAndContinue()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Sign-in opens after a navigation; on a cold emulator its first focus can land after the key returns.
        awaitFocused(SignInTags.USERNAME)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.typeWhenReady(SignInTags.USERNAME_INPUT, "sam")
        compose.onNodeWithTag(SignInTags.USERNAME_INPUT).performImeAction()
        compose.waitForIdle()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.typeWhenReady(SignInTags.PASSWORD_INPUT, "dummy-pass-0001")
        compose.onNodeWithTag(SignInTags.PASSWORD_INPUT).performImeAction()
        compose.waitForIdle()
        // Done on the password signs in.

        // The library picker asked the server about the session that signed in (the admin flag recorded, not acted on),
        // the session reached the store, and Continue stored the server as the configured one.
        awaitFocused(LibrariesTags.row("movies"))
        assertEquals(listOf(session), Scripted.server.librariesCalls)
        assertEquals(listOf(SessionRepository.SESSION_KEY), Scripted.store.writtenKeys)
        assertEquals(listOf(https to "sam"), Scripted.server.signInCalls)
        assertEquals(ServerRecord(https), Scripted.settings.current)
    }

    @Test
    fun theLibraryPickerReachedFromServerThenSignInHasNothingUnderIt() {
        val token = AccessToken("dummy-token-0001")
        val session = Session(UserId("user1"), token, info, https, null, isAdministrator = false)
        Scripted.server.signInResult = ServerResult.Success(session)
        val navController = newNavController()
        launchRestored(navController, ServerRoute())
        testAddressAndContinue()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Sign-in opens after a navigation; on a cold emulator its first focus can land after the key returns.
        awaitFocused(SignInTags.USERNAME)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.typeWhenReady(SignInTags.USERNAME_INPUT, "sam")
        compose.onNodeWithTag(SignInTags.USERNAME_INPUT).performImeAction()
        compose.waitForIdle()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.typeWhenReady(SignInTags.PASSWORD_INPUT, "dummy-pass-0001")
        compose.onNodeWithTag(SignInTags.PASSWORD_INPUT).performImeAction()
        compose.waitForIdle()

        awaitFocused(LibrariesTags.row("movies"))
        // The same stack however the picker was reached: neither the server screen nor sign-in is left beneath it.
        assertTrue(navController.currentDestination.isRoute<LibrariesRoute>())
        assertNull(navController.previousBackStackEntry)
    }

    @Test
    fun aFlowThatStartedAtWelcomeShowsStepsOneToThreeAndHeadlinesAreHeadings() {
        val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)
        Scripted.server.signInResult = ServerResult.Success(session)
        selection.resume()
        compose.setContent { StationBreakTheme { AppNavGraph(startDestination = ServerRoute(firstRun = true)) } }
        compose.waitForIdle()
        awaitFocused(ServerTags.FIELD)
        // The marker is spoken as part of the headline: one heading node holding both.
        assertHeading("Step 1 of 3", "Connect to your Jellyfin server")

        testAddressAndContinue()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Sign-in opens after a navigation; on a cold emulator its first focus can land after the key returns.
        awaitFocused(SignInTags.USERNAME)
        assertHeading("Step 2 of 3", "Sign in to")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.typeWhenReady(SignInTags.USERNAME_INPUT, "sam")
        compose.onNodeWithTag(SignInTags.USERNAME_INPUT).performImeAction()
        compose.waitForIdle()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.typeWhenReady(SignInTags.PASSWORD_INPUT, "dummy-pass-0001")
        compose.onNodeWithTag(SignInTags.PASSWORD_INPUT).performImeAction()
        compose.waitForIdle()

        awaitFocused(LibrariesTags.row("movies"))
        assertHeading("Step 3 of 3", "Choose your libraries")
    }

    @Test
    fun aFlowThatDidNotStartAtWelcomeShowsNoStepMarkers() {
        val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)
        Scripted.server.signInResult = ServerResult.Success(session)
        launchApp()
        awaitFocused(ServerTags.FIELD)
        compose.onNode(hasText("Step", substring = true)).assertDoesNotExist()
        testAddressAndContinue()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Sign-in opens after a navigation; on a cold emulator its first focus can land after the key returns.
        awaitFocused(SignInTags.USERNAME)
        compose.onNode(hasText("Step", substring = true)).assertDoesNotExist()
    }

    private fun assertHeading(
        marker: String,
        headline: String,
    ) {
        compose
            .onNode(keyIsDefined(SemanticsProperties.Heading) and hasText(marker))
            .assert(hasText(headline, substring = true))
    }

    @Test
    fun anAcceptedCleartextServerShowsNotEncryptedOnSignIn() {
        Scripted.server.discoverResult = ServerResult.Success(ResolvedServer(http, info))
        launchApp()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.typeWhenReady(ServerTags.FIELD_INPUT, "http://jellyfin.example.test:8096")
        compose.onNodeWithTag(ServerTags.FIELD_INPUT).performImeAction()
        compose.waitForIdle()
        // Go ran Test; the cleartext dialog opens on Cancel; Right reaches "Connect anyway".
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocused(ServerTags.CONTINUE)
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        compose.onNodeWithTag(SignInTags.NOT_ENCRYPTED).assertExists()
    }

    /** After process death the back stack comes back, but the in-memory hand-off does not. */
    private fun launchRestored(
        navController: NavHostController,
        startDestination: Any,
    ) {
        selection.resume()
        compose.setContent {
            StationBreakTheme {
                AppNavGraph(startDestination = startDestination, navController = navController)
            }
        }
        compose.waitForIdle()
    }

    private fun newNavController() =
        NavHostController(compose.activity).also {
            it.navigatorProvider.addNavigator(ComposeNavigator())
            it.navigatorProvider.addNavigator(DialogNavigator())
        }

    @Test
    fun aRestoredServerAndSignInStackWithoutItsServerFallsBackToTheServerScreenAlone() {
        val navController = newNavController()
        launchRestored(navController, ServerRoute())
        // [Server, SignIn], as a restored stack is, but nothing was handed over.
        compose.runOnUiThread { navController.navigate(SignInRoute()) }
        compose.waitForIdle()

        compose.onNodeWithTag(ServerTags.FIELD).assertExists()
        compose.onNodeWithTag(SignInTags.USERNAME).assertDoesNotExist()
        assertTrue(navController.currentDestination.isRoute<ServerRoute>())
        // Nothing is left under the server screen: Back from it leaves the app.
        assertNull(navController.previousBackStackEntry)
    }

    @Test
    fun aRestoredSignInStackAloneIsReplacedByTheServerScreenSoBackExits() {
        val navController = newNavController()
        launchRestored(navController, SignInRoute())

        compose.onNodeWithTag(ServerTags.FIELD).assertExists()
        compose.onNodeWithTag(SignInTags.USERNAME).assertDoesNotExist()
        assertTrue(navController.currentDestination.isRoute<ServerRoute>())
        assertNull("sign-in must not be left under the server screen", navController.previousBackStackEntry)

        // The activity goes away, so nothing below may touch the compose rule again.
        val activity = compose.activity
        compose.awaitWindowFocus()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val deadline = System.currentTimeMillis() + BACK_TIMEOUT_MS
        while (!activity.isFinishing && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertTrue("Back from the server screen should leave the app", activity.isFinishing)
    }

    private inline fun <reified T : Any> NavDestination?.isRoute(): Boolean = this?.hasRoute<T>() == true

    private companion object {
        const val BACK_TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
    }
}
