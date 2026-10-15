// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServerError
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
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.signin.SignInResult
import tv.stationbreak.feature.onboarding.signin.SignedInHandOff

@OptIn(ExperimentalCoroutinesApi::class)
class LibrariesViewModelTest {
    private val server = FakeMediaServer()
    private val store = InMemorySecretStore()
    private val sessions = SessionRepository(store, server)
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val settings = InMemoryServerSettings(ServerRecord(https))
    private val handOff = SignedInHandOff()

    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)

    private val movies = Library(ItemId("movies"), "Movies", LibraryKind.Movies)
    private val shows = Library(ItemId("shows"), "TV Shows", LibraryKind.TvShows)
    private val mixed = Library(ItemId("mixed"), "Everything", LibraryKind.Mixed)
    private val home = Library(ItemId("home"), "Home Videos", LibraryKind.HomeVideos)
    private val musicVideos = Library(ItemId("mv"), "Music Videos", LibraryKind.MusicVideos)

    @Before
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        handOff.put(SignInResult(session, persisted = true, username = "sam"))
        server.librariesResult = ServerResult.Success(listOf(movies, shows, home, musicVideos))
    }

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun viewModel() = LibrariesViewModel(server, sessions, settings, handOff)

    private fun CoroutineScope.collect(
        vm: LibrariesViewModel,
        into: MutableList<LibrariesEvent>,
    ): Job = launch(UnconfinedTestDispatcher()) { vm.events.collect { into += it } }

    // Listing

    @Test
    fun listsTheLibrariesOfTheSignedInSession() {
        val vm = viewModel()

        assertEquals(listOf(session), server.librariesCalls)
        assertEquals(listOf(movies, shows, home, musicVideos), vm.state.value.libraries)
        assertEquals(LibrariesPhase.Choosing, vm.state.value.phase)
    }

    @Test
    fun everyLibraryStartsTickedExceptHomeVideos() {
        server.librariesResult = ServerResult.Success(listOf(movies, shows, mixed, home, musicVideos))

        val vm = viewModel()

        assertEquals(setOf(movies.id, shows.id, mixed.id, musicVideos.id), vm.state.value.selected)
        assertFalse(home.id in vm.state.value.selected)
    }

    @Test
    fun aServerWithOnlyHomeVideosStartsWithNothingTickedAndDoneOff() {
        server.librariesResult = ServerResult.Success(listOf(home))

        val vm = viewModel()

        assertTrue(
            vm.state.value.selected
                .isEmpty(),
        )
        assertFalse(vm.state.value.canFinish)
    }

    @Test
    fun noSessionHeldMeansNothingIsAskedAndTheScreenSaysSo() {
        handOff.clear()

        val vm = viewModel()

        assertTrue(vm.missingSession)
        assertTrue(server.librariesCalls.isEmpty())
    }

    @Test
    fun theNameOfTheServerIsKeptForTheHeadlineAndTheUserForNothingElse() {
        assertEquals("Home Server", viewModel().serverName)
    }

    // Toggling and Done

    @Test
    fun selectingARowTogglesItBothWays() {
        val vm = viewModel()

        vm.onToggle(home.id)
        assertTrue(home.id in vm.state.value.selected)
        vm.onToggle(home.id)
        assertFalse(home.id in vm.state.value.selected)
        vm.onToggle(movies.id)
        assertFalse(movies.id in vm.state.value.selected)
    }

    @Test
    fun anIdTheServerNeverListedCannotBeTicked() {
        val vm = viewModel()

        vm.onToggle(ItemId("invented"))

        assertFalse(ItemId("invented") in vm.state.value.selected)
    }

    @Test
    fun unTickingEverythingTurnsDoneOffAndNeverSavesAnEmptyChoice() {
        val vm = viewModel()
        listOf(movies, shows, musicVideos).forEach { vm.onToggle(it.id) }

        assertTrue(
            vm.state.value.selected
                .isEmpty(),
        )
        assertFalse(vm.state.value.canFinish)
        vm.onDone()
        assertTrue(settings.savedLibraries.isEmpty())
        assertEquals(LibrariesPhase.Choosing, vm.state.value.phase)
    }

    @Test
    fun tickingOneAgainTurnsDoneBackOn() {
        val vm = viewModel()
        listOf(movies, shows, musicVideos).forEach { vm.onToggle(it.id) }

        vm.onToggle(shows.id)

        assertTrue(vm.state.value.canFinish)
    }

    @Test
    fun doneSavesTheTickedLibrariesWithTheRecordThenFinishesAndDropsTheHeldSession() =
        runTest {
            val vm = viewModel()
            val events = mutableListOf<LibrariesEvent>()
            val job = backgroundScope.collect(vm, events)

            vm.onDone()

            assertEquals(setOf(movies.id, shows.id, musicVideos.id), settings.currentLibraries)
            assertEquals(listOf(LibrariesEvent.Finished), events)
            assertNull(handOff.peek())
            assertEquals(ServerRecord(https), settings.current)
            job.cancel()
        }

    @Test
    fun aSecondDoneWhileSavingSavesOnce() {
        val vm = viewModel()

        vm.onDone()
        vm.onDone()

        assertEquals(1, settings.savedLibraries.size)
    }

    // Zero libraries

    @Test
    fun aServerWithNoUsableLibrariesSaysSoAndRetryAsksAgain() {
        server.librariesResult = ServerResult.Success(emptyList())
        val vm = viewModel()
        assertEquals(LibrariesPhase.NoLibraries, vm.state.value.phase)

        server.librariesResult = ServerResult.Success(listOf(movies))
        vm.onRetry()

        assertEquals(LibrariesPhase.Choosing, vm.state.value.phase)
        assertEquals(setOf(movies.id), vm.state.value.selected)
        assertEquals(2, server.librariesCalls.size)
    }

    // Listing failed

    @Test
    fun aListingThatFailsShowsItsPlainLanguageErrorAndRetryAsksAgain() {
        server.librariesResult = ServerResult.Failure(MediaServerError.Timeout())
        val vm = viewModel()

        val phase = vm.state.value.phase
        assertTrue(phase is LibrariesPhase.LoadFailed)
        assertEquals(R.string.error_timeout_message, (phase as LibrariesPhase.LoadFailed).copy.message)

        server.librariesResult = ServerResult.Success(listOf(movies))
        vm.onRetry()
        assertEquals(LibrariesPhase.Choosing, vm.state.value.phase)
    }

    @Test
    fun aSessionTheServerRefusesIsClearedAndLaunchRoutingTakesOver() =
        runTest {
            runBlocking { sessions.save(session) }
            server.librariesResult = ServerResult.Failure(MediaServerError.SessionExpired())
            val events = mutableListOf<LibrariesEvent>()

            val vm = viewModel()
            val job = backgroundScope.collect(vm, events)

            assertEquals(listOf(LibrariesEvent.SessionLost), events)
            assertTrue(store.snapshot().isEmpty())
            assertNull(handOff.peek())
            job.cancel()
        }

    @Test
    fun aFailureThatIsNotTheSessionLeavesTheStoredSessionAlone() {
        runBlocking { sessions.save(session) }
        server.librariesResult = ServerResult.Failure(MediaServerError.ServerError())

        viewModel()

        assertEquals(setOf(SessionRepository.SESSION_KEY), store.snapshot().keys)
        assertNotNull(handOff.peek())
    }

    // Done failed

    @Test
    fun aSaveThatFailsKeepsTheChoiceAndOffersRetry() =
        runTest {
            settings.failSave = true
            val vm = viewModel()
            val events = mutableListOf<LibrariesEvent>()
            val job = backgroundScope.collect(vm, events)
            vm.onToggle(home.id)

            vm.onDone()

            assertEquals(LibrariesPhase.SaveFailed, vm.state.value.phase)
            assertTrue(home.id in vm.state.value.selected)
            assertTrue(events.isEmpty())
            assertNotNull(handOff.peek())

            settings.failSave = false
            vm.onRetry()

            assertEquals(setOf(movies.id, shows.id, home.id, musicVideos.id), settings.currentLibraries)
            assertEquals(listOf(LibrariesEvent.Finished), events)
            job.cancel()
        }

    @Test
    fun changingATickAfterAFailedSaveGoesBackToChoosing() {
        settings.failSave = true
        val vm = viewModel()
        vm.onDone()

        vm.onToggle(home.id)

        assertEquals(LibrariesPhase.Choosing, vm.state.value.phase)
    }

    @Test
    fun theStateAndTheModelShowNothingOfTheSession() {
        val vm = viewModel()

        assertFalse("dummy-token" in vm.state.value.toString())
        assertFalse("jellyfin.example.test" in vm.state.value.toString())
        assertSame(vm.state.value.libraries, vm.state.value.libraries)
    }

    @Test
    fun aSlowListingShowsLoadingUntilItAnswers() {
        val gate = CompletableDeferred<ServerResult<List<Library>>>()
        val slow = SlowLibraries(server, gate)

        val vm = LibrariesViewModel(slow, sessions, settings, handOff)
        assertEquals(LibrariesPhase.Loading, vm.state.value.phase)
        gate.complete(ServerResult.Success(listOf(movies)))

        assertEquals(LibrariesPhase.Choosing, vm.state.value.phase)
    }
}

/** A server whose library listing waits for [gate], to see the screen while it is still loading. */
private class SlowLibraries(
    private val fake: FakeMediaServer,
    private val gate: CompletableDeferred<ServerResult<List<Library>>>,
) : tv.stationbreak.core.domain.MediaServer by fake {
    override suspend fun libraries(session: Session): ServerResult<List<Library>> = gate.await()
}
