// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.app.di.LibraryModule
import tv.stationbreak.app.di.MediaServerModule
import tv.stationbreak.app.nav.AppNavHost
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.InMemoryFirstSync
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
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
import tv.stationbreak.data.library.FirstSyncRunner
import tv.stationbreak.data.library.RoomLibraryRepository
import tv.stationbreak.feature.live.ui.LiveTags
import tv.stationbreak.feature.onboarding.firstsync.FirstSyncTags
import tv.stationbreak.feature.onboarding.libraries.LibrariesTags
import tv.stationbreak.feature.onboarding.signin.SignInTags
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/** One scripted world for the whole graph, shared with the test through this holder. */
private object Syncing {
    val server = FakeMediaServer()
    val store = InMemorySecretStore()
    var settings = InMemoryServerSettings()
    var firstSync = InMemoryFirstSync()
}

/**
 * The first-sync progress screen inside the whole app graph on API 25 (spec 06 task 3.9), beside
 * [LaunchNavigationTest], which is unchanged: the saved library's first-sync status is scripted, so the test decides
 * when the sync starts, runs, fails and ends. It checks the routes into the screen and out of it, that nothing takes
 * focus while it syncs, and that Back leaves the app with the sync still going.
 */
@HiltAndroidTest
@UninstallModules(MediaServerModule::class, LibraryModule::class)
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // One function per route and behaviour.
class FirstSyncNavigationTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    /** Nested, so it applies to this test only. The saved library is scripted; the rest is as in the launch test. */
    @Module
    @InstallIn(SingletonComponent::class)
    object SyncingModule {
        @Provides
        @Singleton
        fun mediaServer(): MediaServer = Syncing.server

        @Provides
        @Singleton
        fun secretStore(): SecretStore = Syncing.store

        @Provides
        @Singleton
        fun serverSettings(): ServerSettings = Syncing.settings

        @Provides
        @Singleton
        fun sessions(
            store: SecretStore,
            server: MediaServer,
        ): SessionRepository = SessionRepository(store, server)

        @Provides
        @Singleton
        fun firstSync(): FirstSync = Syncing.firstSync

        // The saved library itself is real; only its first-sync status is scripted.
        @Provides
        @Singleton
        fun roomLibraryRepository(
            @ApplicationContext context: Context,
        ): RoomLibraryRepository = RoomLibraryRepository.create(context)

        // The lineup refresher, started by the Application, reads the saved library (spec 12).
        @Provides
        fun libraryRepository(repository: RoomLibraryRepository): LibraryRepository = repository

        // The Application's entry point asks for it; the worker is not run in this test.
        @Provides
        @Singleton
        fun firstSyncRunner(
            settings: ServerSettings,
            sessions: SessionRepository,
            server: MediaServer,
            repository: RoomLibraryRepository,
        ): FirstSyncRunner = FirstSyncRunner(settings, sessions, server, repository, Clock.System)
    }

    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)
    private val movies = Library(ItemId("movies"), "Movies", LibraryKind.Movies)
    private val done = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun running(
        copied: Int,
        total: Int,
    ) = LibraryStatus(SyncState.FirstSyncRunning(copied, total), null)

    private val finished = LibraryStatus(SyncState.Idle, done)

    @Before
    fun script() {
        Syncing.settings = InMemoryServerSettings(ServerRecord(https), setOf(movies.id))
        // A fresh one for each test, so the calls it counts are this test's.
        Syncing.firstSync = InMemoryFirstSync()
        with(Syncing.server) {
            resumeResult = ServerResult.Success(session)
            connectResult = ServerResult.Success(ResolvedServer(https, info))
            librariesResult = ServerResult.Success(listOf(movies))
            signInResult = ServerResult.Failure(MediaServerError.Unknown())
            connectCalls.clear()
            resumeCalls.clear()
        }
        runBlocking {
            Syncing.store
                .snapshot()
                .keys
                .forEach { Syncing.store.clear(it) }
        }
        hilt.inject()
        runBlocking { SessionRepository(Syncing.store, Syncing.server).save(session) }
    }

    private fun launchApp() {
        compose.setContent { StationBreakTheme { AppNavHost() } }
        compose.waitForIdle()
    }

    private fun count(tag: String): Int = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun awaitExists(tag: String) {
        compose.waitUntil(TIMEOUT_MS) { count(tag) > 0 }
    }

    private fun awaitFocused(tag: String) {
        compose.waitUntil(TIMEOUT_MS) { runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess }
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun assertBackLeavesTheApp() {
        // The activity goes away, so nothing below may touch the compose rule again.
        val activity = compose.activity
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!activity.isFinishing && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertTrue("Back should leave the app", activity.isFinishing)
    }

    @Test
    fun anUnfinishedFirstSyncOpensTheProgressScreenAndStartsTheSync() {
        Syncing.firstSync.current = running(400, 1_000)
        launchApp()

        awaitExists(FirstSyncTags.HEADLINE)
        assertEquals(0, count(LiveTags.NO_CHANNELS))
        compose.onAllNodes(isFocusable()).assertCountEquals(0)
        assertEquals(1, Syncing.firstSync.ensureCalls)
    }

    @Test
    fun afterFirstSyncTheLiveScreenOpens() {
        Syncing.firstSync.current = running(900, 1_000)
        launchApp()
        awaitExists(FirstSyncTags.HEADLINE)

        Syncing.firstSync.current = finished

        awaitExists(LiveTags.NO_CHANNELS)
        assertEquals(0, count(FirstSyncTags.HEADLINE))
        assertBackLeavesTheApp()
    }

    @Test
    fun aFinishedFirstSyncNeverShowsTheProgressScreen() {
        Syncing.firstSync.current = finished
        launchApp()

        awaitExists(LiveTags.NO_CHANNELS)
        assertEquals(0, count(FirstSyncTags.HEADLINE))
        assertEquals(0, Syncing.firstSync.ensureCalls)
    }

    @Test
    fun backOnTheProgressScreenLeavesTheAppAndTheSyncAlone() {
        Syncing.firstSync.current = running(10, 100)
        launchApp()
        awaitExists(FirstSyncTags.HEADLINE)

        assertBackLeavesTheApp()

        assertEquals(0, Syncing.firstSync.retryCalls)
        assertEquals(1, Syncing.firstSync.ensureCalls)
    }

    @Test
    fun theLibraryPickersDoneOpensTheProgressScreen() {
        Syncing.settings = InMemoryServerSettings(ServerRecord(https))
        Syncing.firstSync.current = LibraryStatus(SyncState.NeverSynced, null)
        launchApp()
        awaitFocused(LibrariesTags.row("movies"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused(LibrariesTags.DONE)
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        awaitExists(FirstSyncTags.HEADLINE)
        assertEquals(setOf(movies.id), Syncing.settings.currentLibraries)
        assertEquals(1, Syncing.firstSync.ensureCalls)
    }

    @Test
    fun aFailureOffersRetryWithDefaultFocusAndRetryRunsTheSyncAgain() {
        Syncing.firstSync.current = LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Network), null)
        launchApp()

        awaitFocused(FirstSyncTags.RETRY)
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(1, Syncing.firstSync.retryCalls)
    }

    @Test
    fun aRejectedSignInSendsRetryBackToSigningIn() {
        Syncing.firstSync.current = LibraryStatus(SyncState.LastSyncFailed(SyncFailure.SessionExpired), null)
        launchApp()
        awaitFocused(FirstSyncTags.RETRY)
        // The server has refused the sign-in by the time launch routing looks again.
        Syncing.server.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())

        press(KeyEvent.KEYCODE_DPAD_CENTER)

        awaitFocused(SignInTags.USERNAME)
        assertEquals(0, Syncing.firstSync.retryCalls)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
