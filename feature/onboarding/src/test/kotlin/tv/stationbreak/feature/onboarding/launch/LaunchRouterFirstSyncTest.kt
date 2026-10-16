// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.launch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemoryFirstSync
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.feature.onboarding.server.ServerHandOff
import tv.stationbreak.feature.onboarding.signin.SignedInHandOff
import tv.stationbreak.feature.onboarding.trust.CertificateTrust
import kotlin.time.Instant

/**
 * Spec 06 task 3.9: the first-sync route beside the launch routes of `LaunchRouterTest`, which are unchanged. A
 * restored session with a library choice goes to the progress screen while its first sync has not ended, and straight
 * to "Setup complete" once it has. Everything else routes as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchRouterFirstSyncTest {
    private val fake = FakeMediaServer()
    private val store = InMemorySecretStore()
    private val sessions = SessionRepository(store, fake)

    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)
    private val done = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private val never = LibraryStatus(SyncState.NeverSynced, null)

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun route(
        firstSync: InMemoryFirstSync,
        chosen: Set<ItemId>? = setOf(ItemId("movies")),
    ): LaunchRoute {
        runBlocking { sessions.save(session) }
        fake.resumeResult = ServerResult.Success(session)
        val settings = InMemoryServerSettings(ServerRecord(https), chosen)
        return runBlocking { LaunchRouter(settings, sessions, fake, firstSync).route() }
    }

    @Test
    fun aRestoredSessionWhoseFirstSyncHasNotEndedGoesToTheProgressScreen() {
        assertEquals(LaunchRoute.FirstSync, route(InMemoryFirstSync(never)))
    }

    @Test
    fun aFirstSyncStillRunningGoesToTheProgressScreen() {
        val running = LibraryStatus(SyncState.FirstSyncRunning(400, 1000), null)
        assertEquals(LaunchRoute.FirstSync, route(InMemoryFirstSync(running)))
    }

    @Test
    fun aFirstSyncThatFailedGoesToTheProgressScreenWhereRetryIs() {
        val failed = LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Network), null)
        assertEquals(LaunchRoute.FirstSync, route(InMemoryFirstSync(failed)))
    }

    @Test
    fun aFinishedFirstSyncGoesStraightToSetupComplete() {
        assertEquals(LaunchRoute.SetupComplete, route(InMemoryFirstSync(LibraryStatus(SyncState.Idle, done))))
    }

    @Test
    fun aLaterSyncThatFailedStillGoesToSetupComplete() {
        val failedLater = LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Server), done)
        assertEquals(LaunchRoute.SetupComplete, route(InMemoryFirstSync(failedLater)))
    }

    @Test
    fun nobodyActiveInTheLibraryMeansSetupCompleteAsBefore() {
        assertEquals(LaunchRoute.SetupComplete, route(InMemoryFirstSync(null)))
    }

    @Test
    fun noChoiceYetStillGoesToThePickerBeforeAnySync() {
        val route = route(InMemoryFirstSync(never), chosen = null)

        assertEquals(session, (route as LaunchRoute.Libraries).session)
    }

    @Test
    fun theViewModelSendsTheAppToTheProgressScreen() {
        runBlocking { sessions.save(session) }
        fake.resumeResult = ServerResult.Success(session)
        val settings = InMemoryServerSettings(ServerRecord(https), setOf(ItemId("movies")))
        val router = LaunchRouter(settings, sessions, fake, InMemoryFirstSync(never))
        val vm =
            LaunchViewModel(
                router,
                ServerHandOff(),
                SignedInHandOff(),
                CertificateTrust(fake, settings, SessionRepository(store, fake)),
            )

        val target = runBlocking { vm.targets.first() }

        assertEquals(LaunchTarget.FirstSync, target)
        assertTrue(vm.state.value is LaunchPhase.Routing)
    }
}
