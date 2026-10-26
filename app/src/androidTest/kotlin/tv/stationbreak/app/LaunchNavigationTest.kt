// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.view.KeyEvent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.app.di.MediaServerModule
import tv.stationbreak.app.di.PlayerModule
import tv.stationbreak.app.nav.AppNavHost
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.core.model.StoredSession
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.guide.ui.GuideTags
import tv.stationbreak.feature.live.ui.LiveTags
import tv.stationbreak.feature.onboarding.launch.LaunchTags
import tv.stationbreak.feature.onboarding.libraries.LibrariesTags
import tv.stationbreak.feature.onboarding.libraries.SignedInTags
import tv.stationbreak.feature.onboarding.server.ServerTags
import tv.stationbreak.feature.onboarding.signin.SignInTags
import tv.stationbreak.feature.onboarding.trust.TrustTags
import javax.inject.Singleton
import kotlin.time.Clock

/** A scripted server whose restore can be held open, to see the app while launch routing is still working. */
private class ScriptedServer(
    val fake: FakeMediaServer = FakeMediaServer(),
) : MediaServer by fake {
    var resumeGate: CompletableDeferred<Unit>? = null

    override suspend fun resume(stored: StoredSession): ServerResult<Session> {
        resumeGate?.await()
        return fake.resume(stored)
    }
}

/** One scripted world for the whole graph, shared with the test through this holder. */
private object Launched {
    val server = ScriptedServer()
    val store = InMemorySecretStore()
    var settings = InMemoryServerSettings()
}

/**
 * Launch routing and the end of onboarding, on API 25 under the real Hilt graph (the adapter, the Keystore and the
 * settings file swapped for fakes), driven with real key events (spec 05, Unit 4): every route the app can open on, a
 * progress screen while the restore runs and no flash of onboarding, the library picker's Done, Back to "Signed in as"
 * with Continue and Switch user, and the live screen with Back leaving the app.
 */
@HiltAndroidTest
@UninstallModules(MediaServerModule::class, PlayerModule::class)
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // One function per route and behaviour.
class LaunchNavigationTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    /** Nested, so it applies to this test only. */
    @Module
    @InstallIn(SingletonComponent::class)
    object LaunchedModule {
        @Provides
        @Singleton
        fun mediaServer(): MediaServer = Launched.server

        @Provides
        @Singleton
        fun secretStore(): SecretStore = Launched.store

        @Provides
        @Singleton
        fun serverSettings(): ServerSettings = Launched.settings

        @Provides
        @Singleton
        fun sessions(
            store: SecretStore,
            server: MediaServer,
        ): SessionRepository = SessionRepository(store, server)

        // The live screen is in the graph: its clock, and a player that is never made (no real HTTP client here).
        @Provides
        fun clock(): Clock = Clock.System

        @Provides
        fun players(): VideoPlayerFactory = LiveTestBindings.players
    }

    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)
    private val movies = Library(ItemId("movies"), "Movies", LibraryKind.Movies)
    private val spki = SpkiPin(ByteArray(SpkiPin.SIZE) { 4 })
    private val selfSigned = CertificateEvidence(spki, selfSigned = true, notBeforeMillis = 0L, notAfterMillis = 1_000L)

    @Before
    fun script() {
        Launched.settings = InMemoryServerSettings()
        Launched.server.resumeGate = null
        with(Launched.server.fake) {
            resumeResult = ServerResult.Success(session)
            connectResult = ServerResult.Success(ResolvedServer(https, info))
            trustResult = ServerResult.Success(ResolvedServer(https, info))
            librariesResult = ServerResult.Success(listOf(movies))
            signInResult = ServerResult.Failure(MediaServerError.Unknown())
            revokeResult = ServerResult.Success(Unit)
            connectCalls.clear()
            resumeCalls.clear()
            revokeCalls.clear()
            trustCalls.clear()
            librariesCalls.clear()
        }
        runBlocking {
            Launched.store
                .snapshot()
                .keys
                .forEach { Launched.store.clear(it) }
        }
        Launched.store.writtenKeys.clear()
        hilt.inject()
    }

    private fun storeSession() = runBlocking { SessionRepository(Launched.store, Launched.server).save(session) }

    private fun launchApp() {
        compose.setContent { StationBreakTheme { AppNavHost() } }
        compose.waitForIdle()
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun awaitFocused(tag: String) {
        compose.waitUntil(TIMEOUT_MS) { runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess }
    }

    /** A key goes to the node that should have focus by then, never to whatever focus has reached so far. */
    private fun press(
        keyCode: Int,
        expectedFocus: String,
    ) {
        awaitFocused(expectedFocus)
        press(keyCode)
    }

    private fun count(tag: String): Int = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun awaitExists(tag: String) {
        compose.waitUntil(TIMEOUT_MS) { count(tag) > 0 }
    }

    /**
     * Back from live TV opens the guide (spec 17, owner decision 3), and Back from the guide leaves the app: two
     * presses where the rest of the app takes one, which is why the guide's hint row says so.
     */
    private fun assertBackOpensTheGuideThenLeavesTheApp() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitExists(GuideTags.SCREEN)
        assertFalse("the first Back only opens the guide", compose.activity.isFinishing)
        assertBackLeavesTheApp()
    }

    private fun assertBackLeavesTheApp() {
        // The activity goes away, so nothing below may touch the compose rule again.
        val activity = compose.activity
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!activity.isFinishing && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertTrue("Back should leave the app", activity.isFinishing)
    }

    // Routes

    @Test
    fun noStoredServerOpensTheServerScreen() {
        launchApp()
        awaitFocused(ServerTags.FIELD)
        compose.onNodeWithTag(LaunchTags.SCREEN).assertDoesNotExist()
        assertTrue(
            Launched.server.fake.connectCalls
                .isEmpty(),
        )
    }

    @Test
    fun aRestoredSessionWithAChoiceOpensTheLiveScreenAndBackLeavesThroughTheGuide() {
        storeSession()
        Launched.settings = InMemoryServerSettings(ServerRecord(https), setOf(movies.id))
        launchApp()

        awaitExists(LiveTags.NO_CHANNELS)
        assertEquals(0, count(ServerTags.FIELD))
        assertBackOpensTheGuideThenLeavesTheApp()
    }

    @Test
    fun aRestoredSessionWithNoChoiceOpensTheLibraryPicker() {
        storeSession()
        Launched.settings = InMemoryServerSettings(ServerRecord(https))
        launchApp()

        awaitFocused(LibrariesTags.row("movies"))
        assertEquals(listOf(session), Launched.server.fake.librariesCalls)
    }

    @Test
    fun signedOutOpensSignInWithTheUsernameFocusedAndNoLine() {
        Launched.settings = InMemoryServerSettings(ServerRecord(https))
        launchApp()

        awaitFocused(SignInTags.USERNAME)
        compose.onNodeWithTag(SignInTags.NOTICE).assertDoesNotExist()
        compose.onNodeWithTag(ServerTags.FIELD).assertDoesNotExist()
    }

    @Test
    fun aRejectedSessionOpensSignInWithOneLineSayingWhy() {
        storeSession()
        Launched.server.fake.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())
        Launched.settings = InMemoryServerSettings(ServerRecord(https))
        launchApp()

        awaitFocused(SignInTags.USERNAME)
        compose.onNodeWithTag(SignInTags.NOTICE).assertExists()
        compose.onNodeWithText("The server no longer accepts this sign-in.").assertExists()
        assertTrue(Launched.store.snapshot().isEmpty())
    }

    @Test
    fun backFromSignInThatLaunchOpenedGoesUpToTheServerScreenNotOutOfTheApp() {
        Launched.settings = InMemoryServerSettings(ServerRecord(https))
        launchApp()
        awaitFocused(SignInTags.USERNAME)

        press(KeyEvent.KEYCODE_BACK)

        awaitFocused(ServerTags.FIELD)
        // Never a blank form: the stored address is there to be changed.
        compose.onNodeWithTag(ServerTags.FIELD).assert(hasText(https.baseUrl))
    }

    @Test
    fun anUnavailableServerShowsTheErrorWithRetryAndRetryCarriesOnWhenItIsBack() {
        storeSession()
        Launched.server.fake.resumeResult = ServerResult.Failure(MediaServerError.Timeout())
        Launched.settings = InMemoryServerSettings(ServerRecord(https), setOf(movies.id))
        launchApp()

        awaitFocused(LaunchTags.RETRY)
        compose.onNodeWithTag(ServerTags.FIELD).assertDoesNotExist()
        assertEquals(setOf(SessionRepository.SESSION_KEY), Launched.store.snapshot().keys)

        Launched.server.fake.resumeResult = ServerResult.Success(session)
        press(KeyEvent.KEYCODE_DPAD_CENTER, LaunchTags.RETRY)

        awaitExists(LiveTags.NO_CHANNELS)
    }

    @Test
    fun aChangedCertificateOpensTheTrustScreenAndTrustingItSignsInAgain() {
        storeSession()
        Launched.server.fake.resumeResult =
            ServerResult.Failure(MediaServerError.TlsCertificateChanged(certificate = selfSigned))
        Launched.settings = InMemoryServerSettings(ServerRecord(https, SpkiPin(ByteArray(SpkiPin.SIZE) { 1 })))
        launchApp()

        awaitFocused(TrustTags.DONT_TRUST)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, TrustTags.DONT_TRUST)
        press(KeyEvent.KEYCODE_DPAD_CENTER, TrustTags.TRUST)

        awaitFocused(SignInTags.USERNAME)
        assertEquals(listOf(https.baseUrl to spki), Launched.server.fake.trustCalls)
        assertTrue("accepting a new certificate drops the session", Launched.store.snapshot().isEmpty())
        assertEquals(ServerRecord(https, spki), Launched.settings.current)
    }

    @Test
    fun dontTrustLeavesTheErrorWithRetryAndTheSessionAlone() {
        storeSession()
        Launched.server.fake.resumeResult =
            ServerResult.Failure(MediaServerError.TlsCertificateChanged(certificate = selfSigned))
        Launched.settings = InMemoryServerSettings(ServerRecord(https, spki))
        launchApp()

        press(KeyEvent.KEYCODE_BACK, TrustTags.DONT_TRUST)

        awaitFocused(LaunchTags.RETRY)
        assertTrue(
            Launched.server.fake.trustCalls
                .isEmpty(),
        )
        assertEquals(setOf(SessionRepository.SESSION_KEY), Launched.store.snapshot().keys)
    }

    @Test
    fun whileTheRestoreRunsOnlyProgressShowsAndOnboardingNeverFlashes() {
        storeSession()
        val gate = CompletableDeferred<Unit>()
        Launched.server.resumeGate = gate
        Launched.settings = InMemoryServerSettings(ServerRecord(https), setOf(movies.id))
        launchApp()

        awaitExists(LaunchTags.PROGRESS)
        assertEquals(0, count(ServerTags.FIELD))
        assertEquals(0, count(SignInTags.USERNAME))

        gate.complete(Unit)
        awaitExists(LiveTags.NO_CHANNELS)
        assertEquals(0, count(ServerTags.FIELD))
    }

    // The end of onboarding

    @Test
    fun doneSavesTheChoiceOpensTheLiveScreenAndBackLeavesThroughTheGuide() {
        storeSession()
        Launched.settings = InMemoryServerSettings(ServerRecord(https))
        launchApp()
        awaitFocused(LibrariesTags.row("movies"))
        press(KeyEvent.KEYCODE_DPAD_DOWN, LibrariesTags.row("movies"))
        press(KeyEvent.KEYCODE_DPAD_CENTER, LibrariesTags.DONE)

        awaitExists(LiveTags.NO_CHANNELS)
        assertEquals(setOf(movies.id), Launched.settings.currentLibraries)
        assertBackOpensTheGuideThenLeavesTheApp()
    }

    @Test
    fun backFromThePickerShowsSignedInAsAndContinueReturnsToIt() {
        storeSession()
        Launched.settings = InMemoryServerSettings(ServerRecord(https))
        launchApp()
        awaitFocused(LibrariesTags.row("movies"))

        press(KeyEvent.KEYCODE_BACK)

        awaitFocused(SignedInTags.CONTINUE)
        compose.onNodeWithTag(SignedInTags.HEADLINE).assert(hasText("Signed in to Home Server"))
        assertEquals("Back never signs out", setOf(SessionRepository.SESSION_KEY), Launched.store.snapshot().keys)

        press(KeyEvent.KEYCODE_DPAD_CENTER, SignedInTags.CONTINUE)

        awaitFocused(LibrariesTags.row("movies"))
    }

    @Test
    fun backFromSignedInReturnsToThePicker() {
        storeSession()
        Launched.settings = InMemoryServerSettings(ServerRecord(https))
        launchApp()
        awaitFocused(LibrariesTags.row("movies"))
        press(KeyEvent.KEYCODE_BACK)
        awaitFocused(SignedInTags.CONTINUE)

        press(KeyEvent.KEYCODE_BACK)

        awaitFocused(LibrariesTags.row("movies"))
    }

    @Test
    fun switchUserSignsOutAndShowsTheSignInFormForTheSameServer() {
        storeSession()
        Launched.settings = InMemoryServerSettings(ServerRecord(https))
        launchApp()
        awaitFocused(LibrariesTags.row("movies"))
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, SignedInTags.CONTINUE)

        press(KeyEvent.KEYCODE_DPAD_CENTER, SignedInTags.SWITCH_USER)

        awaitFocused(SignInTags.USERNAME)
        assertTrue(Launched.store.snapshot().isEmpty())
        assertEquals(listOf(session), Launched.server.fake.revokeCalls)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }

    @Test
    fun changeServerOpensTheServerScreenWithTheStoredAddressAndKeepsTheSessionAndPin() {
        storeSession()
        Launched.server.fake.resumeResult = ServerResult.Failure(MediaServerError.Timeout())
        val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 2 })
        Launched.settings = InMemoryServerSettings(ServerRecord(https, pin), setOf(movies.id))
        launchApp()
        awaitFocused(LaunchTags.RETRY)

        press(KeyEvent.KEYCODE_DPAD_RIGHT, LaunchTags.RETRY)
        press(KeyEvent.KEYCODE_DPAD_CENTER, LaunchTags.CHANGE_SERVER)

        awaitFocused(ServerTags.FIELD)
        compose.onNodeWithTag(ServerTags.FIELD).assert(hasText(https.baseUrl))
        assertEquals(setOf(SessionRepository.SESSION_KEY), Launched.store.snapshot().keys)
        assertEquals(ServerRecord(https, pin), Launched.settings.current)
    }
}
