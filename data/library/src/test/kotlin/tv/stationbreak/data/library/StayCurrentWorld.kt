// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.PagedMediaServer
import kotlin.time.Clock

/** Builders for the stay-current tests (spec 06 task 4.0), beside [SyncWorld]. */
internal object StayCurrentWorld {
    fun engine(
        repository: RoomLibraryRepository,
        server: PagedMediaServer,
        clock: Clock,
        pageSize: Int = 100,
    ) = StayCurrentEngine(repository, server, clock, pageSize = pageSize)
}
