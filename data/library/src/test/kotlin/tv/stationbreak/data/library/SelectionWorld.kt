// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.InMemoryFirstSync
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.SelectionStore
import tv.stationbreak.core.domain.StayCurrentRequests
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.repair.TodayRepairer
import tv.stationbreak.core.domain.lineup.savedSelectionOf
import tv.stationbreak.core.domain.selection.LibraryPasses
import tv.stationbreak.core.domain.selection.LibrarySelection
import tv.stationbreak.core.domain.selection.ResumeGate
import tv.stationbreak.data.library.SyncWorld.session
import kotlin.time.Clock

/**
 * The real selection over the real library cache (spec 26, Part 4), for the workers' tests: no lineup yet (so each
 * change is a plain save), the signed-in session of [SyncWorld], and [passes] and [firstSync] as the test gives them.
 */
@Suppress("LongParameterList") // The selection's stores and ports, one for one; most defaulted.
internal fun selectionOver(
    repository: RoomLibraryRepository,
    settings: SelectionStore,
    server: MediaServer,
    clock: Clock,
    passes: LibraryPasses = LibraryPasses.None,
    firstSync: FirstSync = InMemoryFirstSync(),
    stayCurrent: StayCurrentRequests = StayCurrentRequests {},
): LibrarySelection {
    val service = LineupService(InMemoryLineupStore(), repository, clock, { 0 }, {})
    val source = BuiltInScheduleSource(service, repository, saved = savedSelectionOf(settings))
    val repairer = TodayRepairer(service, source, repository) { settings.loadSelectionSeq() }
    return LibrarySelection(
        settings,
        repository,
        server,
        { session },
        repairer,
        service,
        source,
        passes,
        firstSync,
        stayCurrent,
        ResumeGate(),
        clock = clock,
    )
}
