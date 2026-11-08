// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemoryFirstSync
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StayCurrentRequests
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.repair.TodayRepairer
import tv.stationbreak.core.domain.selection.LibrarySelection
import kotlin.time.Clock

/**
 * The selection's bindings (spec 26, Part 4): one writer behind the features' ports, and the seam every worker waits
 * on is its resume at start, no longer an already-complete placeholder.
 */
class SelectionWiringTest {
    private val gate = LineupModule.resumeGate()

    private fun selection(): LibrarySelection {
        val library = InMemoryLibraryRepository()
        val settings = InMemoryServerSettings()
        val server = FakeMediaServer()
        val service = LineupService(InMemoryLineupStore(), library, Clock.System, { 0 }, {})
        val source = BuiltInScheduleSource(service, library) { null }
        val repairer = TodayRepairer(service, source, library) { settings.loadSelectionSeq() }
        return LineupModule.librarySelection(
            settings,
            settings,
            library,
            server,
            SessionRepository(InMemorySecretStore(), server),
            repairer,
            service,
            source,
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
