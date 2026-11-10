// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.Library

/**
 * The one listing of the server's libraries a stay-current run makes (#654). The run asks for it where its first
 * consumer needs it (so the order of its steps, and what each does on a failed listing, are as they were), and every
 * later consumer in the same run, whether a pass, the selection's follow or the studios re-read, gets that same answer
 * instead of asking the server again. A [read] that throws is not kept: the next ask reads again. A run is one
 * coroutine under the runner's gate, so nothing here is shared between threads.
 */
internal class RunListing(
    private val read: suspend () -> ServerResult<List<Library>>,
) {
    private var kept: ServerResult<List<Library>>? = null

    /** The run's listing: read on the first ask, the same answer after. */
    suspend fun get(): ServerResult<List<Library>> = kept ?: read().also { kept = it }
}
