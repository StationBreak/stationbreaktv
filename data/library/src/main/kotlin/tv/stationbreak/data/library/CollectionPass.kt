// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.model.Session
import kotlin.time.Clock
import kotlin.time.Duration

/** What a collection pass did. Counts only: no ID and no name, so it is safe to log. */
internal data class CollectionSummary(
    val read: Int = 0,
    val inserted: Int = 0,
    val revived: Int = 0,
    val changed: Int = 0,
    val unchanged: Int = 0,
    val tombstoned: Int = 0,
    val skippedNested: Int = 0,
    val skippedOther: Int = 0,
    val skippedIds: Int = 0,
    val relisted: Boolean = false,
)

/** How a collection pass ended and what it did. */
internal data class CollectionResult(
    val outcome: SyncOutcome,
    val summary: CollectionSummary,
)

/** Red stub: the green commit writes the pass. */
@Suppress("LongParameterList", "UnusedPrivateProperty") // The same knobs as the engines; the stub of the next commit.
internal class CollectionPass(
    private val repository: RoomLibraryRepository,
    private val server: MediaServer,
    private val clock: Clock,
    private val pageSize: Int = MediaServer.MAX_PAGE_SIZE,
    private val margin: Duration = FirstSyncEngine.DEFAULT_MARGIN,
    private val relistInterval: Duration = CollectionRelist.DEFAULT_INTERVAL,
) {
    @Suppress("UnusedParameter") // The stub of the next commit.
    suspend fun run(
        session: Session,
        account: LibraryAccount,
    ): CollectionResult = CollectionResult(SyncOutcome.Cancelled, CollectionSummary())
}
