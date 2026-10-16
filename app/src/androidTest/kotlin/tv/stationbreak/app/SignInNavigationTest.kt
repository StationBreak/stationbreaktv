// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.view.KeyEvent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
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
import tv.stationbreak.app.nav.AppNavGraph
import tv.stationbreak.app.nav.LibrariesRoute
import tv.stationbreak.app.nav.ServerRoute
import tv.stationbreak.app.nav.SignInRoute
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.SecretStore
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
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.libraries.LibrariesTags
import tv.stationbreak.feature.onboarding.server.ServerTags
import tv.stationbreak.feature.onboarding.signin.SignInTags
import javax.inject.Singleton

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
@UninstallModules(MediaServerModule::class)
@RunWith(AndroidJUnit4::class)
class SignInNavigationTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
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
        @Singleton
        fun sessions(
            store: SecretStore,
            server: MediaServer,
        ): SessionRepository = SessionRepository(store, server)
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

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun launchApp() {
        // The graph from the server screen, as a first launch reaches it. LaunchNavigationTest covers launch routing.
        compose.setContent { StationBreakTheme { AppNavGraph(startDestination = ServerRoute) } }
        compose.waitForIdle()
    }

    /** Types an address on the server screen with the keyboard's text input, tests it and presses Continue. */
    private fun testAddressAndContinue(address: String = "jellyfin.example.test") {
        // Each key goes to the node that should have focus by then; on a cold emulator focus can lag the first key.
        awaitFocused(ServerTags.FIELD)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        typeIntoField(address)
        compose.onNodeWithTag(ServerTags.FIELD_INPUT).performImeAction()
        compose.waitForIdle()
        awaitFocused(ServerTags.TEST)
        pressTestUntilItRuns()
        awaitFocused(ServerTags.CONTINUE)
    }

    /**
     * On a cold emulator the soft keyboard can still hold the window after the field's action and swallow the first
     * select key, leaving Test focused and never run. Press again only while that is still so: no Cancel (Test is not
     * running) and Continue not focused (it has not finished), so a retry can never land on Cancel. A click is no
     * substitute, because it puts the emulator in touch mode, where focus cannot move to Continue.
     */
    private fun pressTestUntilItRuns() {
        repeat(TYPE_ATTEMPTS) {
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            val ran =
                runCatching {
                    compose.waitUntil(TYPE_TIMEOUT_MS) {
                        isFocused(ServerTags.CONTINUE) ||
                            compose.onAllNodesWithTag(ServerTags.CANCEL).fetchSemanticsNodes().isNotEmpty()
                    }
                }.isSuccess
            if (ran || !isFocused(ServerTags.TEST)) return
        }
    }

    private fun isFocused(tag: String): Boolean = runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess

    /** Types the address with [typeWhenReady], which waits out a cold emulator's IME. */
    private fun typeIntoField(address: String) = compose.typeWhenReady(ServerTags.FIELD_INPUT, address)

    /** The Test runs off the main thread; on a cold emulator its result can land after the idle check. */
    private fun awaitFocused(tag: String) {
        compose.waitUntil(BACK_TIMEOUT_MS) { runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess }
    }

    @Test
    fun continueOpensSignInWithTheUsernameFocused() {
        launchApp()
        testAddressAndContinue()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(SignInTags.USERNAME).assertIsFocused()
        compose.onNodeWithTag(SignInTags.NOT_ENCRYPTED).assertDoesNotExist()
    }

    @Test
    fun backFromSignInReturnsToTheServerScreenWithTheAddressKept() {
        launchApp()
        testAddressAndContinue()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(SignInTags.USERNAME).assertIsFocused()

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
        compose.onNodeWithTag(SignInTags.USERNAME).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.typeWhenReady(SignInTags.USERNAME_INPUT, "sam")
        compose.onNodeWithTag(SignInTags.USERNAME_INPUT).performImeAction()
        compose.waitForIdle()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.typeWhenReady(SignInTags.PASSWORD_INPUT, "dummy-pass-0001")
        compose.onNodeWithTag(SignInTags.PASSWORD_INPUT).performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsFocused()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

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
        launchRestored(navController, ServerRoute)
        testAddressAndContinue()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(SignInTags.USERNAME).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.typeWhenReady(SignInTags.USERNAME_INPUT, "sam")
        compose.onNodeWithTag(SignInTags.USERNAME_INPUT).performImeAction()
        compose.waitForIdle()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.typeWhenReady(SignInTags.PASSWORD_INPUT, "dummy-pass-0001")
        compose.onNodeWithTag(SignInTags.PASSWORD_INPUT).performImeAction()
        compose.waitForIdle()
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        awaitFocused(LibrariesTags.row("movies"))
        // The same stack however the picker was reached: neither the server screen nor sign-in is left beneath it.
        assertTrue(navController.currentDestination.isRoute<LibrariesRoute>())
        assertNull(navController.previousBackStackEntry)
    }

    @Test
    fun anAcceptedCleartextServerShowsNotEncryptedOnSignIn() {
        Scripted.server.discoverResult = ServerResult.Success(ResolvedServer(http, info))
        launchApp()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.typeWhenReady(ServerTags.FIELD_INPUT, "http://jellyfin.example.test:8096")
        compose.onNodeWithTag(ServerTags.FIELD_INPUT).performImeAction()
        compose.waitForIdle()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // The cleartext dialog opens on Cancel; Right reaches "Connect anyway".
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
        launchRestored(navController, ServerRoute)
        // [Server, SignIn], as a restored stack is, but nothing was handed over.
        compose.runOnUiThread { navController.navigate(SignInRoute) }
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
        launchRestored(navController, SignInRoute)

        compose.onNodeWithTag(ServerTags.FIELD).assertExists()
        compose.onNodeWithTag(SignInTags.USERNAME).assertDoesNotExist()
        assertTrue(navController.currentDestination.isRoute<ServerRoute>())
        assertNull("sign-in must not be left under the server screen", navController.previousBackStackEntry)

        // The activity goes away, so nothing below may touch the compose rule again.
        val activity = compose.activity
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val deadline = System.currentTimeMillis() + BACK_TIMEOUT_MS
        while (!activity.isFinishing && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertTrue("Back from the server screen should leave the app", activity.isFinishing)
    }

    private inline fun <reified T : Any> NavDestination?.isRoute(): Boolean = this?.hasRoute<T>() == true

    private companion object {
        const val BACK_TIMEOUT_MS = 5_000L
        const val TYPE_ATTEMPTS = 3
        const val TYPE_TIMEOUT_MS = 2_000L
        const val POLL_MS = 50L
    }
}
