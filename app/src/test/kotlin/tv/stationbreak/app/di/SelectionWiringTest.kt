// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemoryFirstSync
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StayCurrentRequests
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.repair.TodayRepairer
import tv.stationbreak.core.domain.selection.LibrarySelection
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import kotlin.time.Clock

/**
 * The selection's bindings (spec 26, Part 4): one writer behind the features' ports, and the seam every worker waits
 * on is its resume at start, no longer an already-complete placeholder.
 */
class SelectionWiringTest {
    private val gate = LineupModule.resumeGate()

    // The add pass's restarts the selection asked for.
    private var restarts = 0

    private fun selection(settings: InMemoryServerSettings = InMemoryServerSettings()): LibrarySelection {
        val library = InMemoryLibraryRepository()
        val server = FakeMediaServer()
        val service = LineupService(InMemoryLineupStore(), library, Clock.System, { 0 }, {})
        val source = BuiltInScheduleSource(service, library) { null }
        val repairer = TodayRepairer(service, source, library) { settings.loadSelectionSeq() }
        return LineupModule.librarySelection(
            settings,
            library,
            server,
            SessionRepository(InMemorySecretStore(), server),
            repairer,
            service,
            source,
            { restarts++ },
            InMemoryFirstSync(),
            StayCurrentRequests {},
            gate,
        )
    }

    @Test
    fun theFeaturesPorts_areTheOneSelection() {
        val selection = selection()

        assertSame(selection, LineupModule.libraryChoices(selection))
        assertSame(selection, LineupModule.librarySetup(selection))
        assertSame(selection, LineupModule.addLandings(selection))
        assertSame(selection, LineupModule.firstSyncLibraries(selection))
        assertSame(selection, LineupModule.serverListings(selection))
        assertSame(selection, LineupModule.selectionResume(selection))
    }

    // Task 4.4b: the restart is the add pass the graph binds, no longer one that asks for nothing. A clear asks for it
    // when an add was under way (Part 4A final fix F7).
    @Test
    fun theSelectionsRestart_isTheBoundAddPass() =
        runBlocking {
            // A server is stored: a selection is saved only beside one.
            val address = ServerAddress(Scheme.Https, "server.example.test", 8920)
            val settings = InMemoryServerSettings(ServerRecord(address))
            val adding = StoredSelection(setOf(ItemId("a1")), seq = 1, adding = mapOf(ItemId("b2") to 1L))
            assertTrue(settings.saveSelection(adding, setup = true))
            val selection = selection(settings)
            selection.resumeAtStart()

            assertTrue(selection.clear())

            assertEquals(1, restarts)
        }

    @Test
    fun theSeamTheWorkersWaitOn_completesOnlyWithTheResume() =
        runBlocking {
            val selection = selection()
            val ready = LineupModule.selectionReady(gate)
            val waiting = async { ready.await() }
            repeat(5) { yield() }
            assertFalse("not before the resume", waiting.isCompleted)

            selection.resumeAtStart()

            withTimeout(5_000) { waiting.await() }
        }
}
