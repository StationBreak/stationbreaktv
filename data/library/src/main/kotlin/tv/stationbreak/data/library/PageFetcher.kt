// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.map
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.core.model.Session
import kotlin.time.Instant

/** The page size in force for one library: only a response-size error makes it smaller. */
internal class PageLimit(
    var size: Int,
)

/** What asking for one page came to. */
internal sealed interface Fetched<out T> {
    class Page<out T>(
        val startIndex: Int,
        val totalCount: Int?,
        val items: List<T>,
    ) : Fetched<T>

    /** One item that will not decode, found by halving the page down to it. */
    data object Malformed : Fetched<Nothing>

    class Failed(
        val error: MediaServerError,
    ) : Fetched<Nothing>
}

/**
 * Asks for one page of [library]'s items, the way the first sync and the stay-current passes both need it. A page the
 * server refuses as too large is asked for again at half the size (and the smaller size is kept in [limit] for the rest
 * of the library). A page that will not decode is halved until it is one item, which comes back as
 * [Fetched.Malformed]: decoding is strict by design, so one malformed item must not stop a library.
 */
internal suspend fun MediaServer.fetchPage(
    session: Session,
    library: Library,
    startIndex: Int,
    changedSince: Instant?,
    limit: PageLimit,
): Fetched<MediaItem> =
    fetchHalving(limit) { asking ->
        libraryItems(session, library, startIndex, asking, changedSince).map {
            Fetched.Page(startIndex, it.totalCount, it.items)
        }
    }

/** [fetchPage] for the series of [library] (spec 10): the same page loop, the same halving, the same cursor. */
internal suspend fun MediaServer.fetchSeriesPage(
    session: Session,
    library: Library,
    startIndex: Int,
    changedSince: Instant?,
    limit: PageLimit,
): Fetched<SeriesInfo> =
    fetchHalving(limit) { asking ->
        librarySeries(session, library, startIndex, asking, changedSince).map {
            Fetched.Page(startIndex, it.totalCount, it.series)
        }
    }

// Inline, so the two callers' lambdas are not two classes behind one call in a loop (ART 7.1, #264).
@Suppress("ReturnCount") // Each return is one way a page can come back.
private suspend inline fun <T> fetchHalving(
    limit: PageLimit,
    ask: suspend (asking: Int) -> ServerResult<Fetched.Page<T>>,
): Fetched<T> {
    var asking = limit.size
    while (true) {
        when (val result = ask(asking)) {
            is ServerResult.Success -> {
                return result.value
            }

            is ServerResult.Failure -> {
                val error = result.error
                when {
                    // Too big for the response limit: half the size, and keep it for the rest of the library.
                    error is MediaServerError.ServerError && asking > 1 -> {
                        asking /= 2
                        limit.size = asking
                    }

                    // Will not decode: halve to find the item, for this page only.
                    error is MediaServerError.NotJellyfin && asking > 1 -> {
                        asking /= 2
                    }

                    error is MediaServerError.NotJellyfin -> {
                        return Fetched.Malformed
                    }

                    else -> {
                        return Fetched.Failed(error)
                    }
                }
            }
        }
    }
}
