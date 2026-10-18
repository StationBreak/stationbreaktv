// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemIdPage
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryItemsPage
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.core.model.SeriesPage
import tv.stationbreak.core.model.Session
import kotlin.time.Instant

/**
 * The items of one library as a server holds them, in the order it pages them (ADR-0004). A test builds it from a list
 * it can change while a sync runs, or from a generator so a big library is never held whole in memory.
 */
interface LibrarySource {
    /** How many items the library holds now. */
    val size: Int

    /** Up to [limit] items from [startIndex], empty past the end. */
    fun page(
        startIndex: Int,
        limit: Int,
    ): List<MediaItem>

    /** When the server last saved [item]: what a changed-since page compares with. */
    fun savedAt(item: MediaItem): Instant
}

/** A [LibrarySource] over a plain list a test can edit, with a save time per item (the creation date by default). */
class ListLibrarySource(
    val items: MutableList<MediaItem> = mutableListOf(),
) : LibrarySource {
    /** Items saved after they were created, by ID. */
    val saved = mutableMapOf<ItemId, Instant>()

    override val size: Int get() = items.size

    override fun page(
        startIndex: Int,
        limit: Int,
    ): List<MediaItem> = items.drop(startIndex).take(limit)

    override fun savedAt(item: MediaItem): Instant = saved[item.id] ?: item.dateCreated ?: Instant.fromEpochMilliseconds(0)
}

/**
 * The series of one library as a server holds them, in the order it pages them. A test edits [series] and [saved]
 * while a sync runs; a series with no entry in [saved] was saved at the epoch.
 */
class SeriesSource(
    val series: MutableList<SeriesInfo> = mutableListOf(),
) {
    /** When the server last saved a series: what a changed-since page compares with. */
    val saved = mutableMapOf<ItemId, Instant>()

    fun savedAt(series: SeriesInfo): Instant = saved[series.id] ?: Instant.fromEpochMilliseconds(0)
}

/** One call [PagedMediaServer] served, as it saw it. [series] is true for a series call. */
data class PagedCall(
    val number: Int,
    val library: Library,
    val startIndex: Int,
    val limit: Int,
    val changedSince: Instant?,
    val idsOnly: Boolean,
    val series: Boolean = false,
)

/**
 * A [MediaServer] that pages [LibrarySource]s the way the adapter does (the first page carries the total, a page past
 * the end is empty, a changed-since call returns only items saved at or after the moment), for tests of code that walks
 * a library. The rest of the port is [fake]'s. It can be told to misbehave: [respond] sees every call before it is
 * served and may change the library, fail the call or throw (a killed process); [maxItemsPerResponse] makes a larger
 * page fail as the adapter's 1 MiB limit does; [malformed] items fail the decode of any page that holds them.
 * Not thread-safe.
 */
class PagedMediaServer(
    var libraries: List<Library>,
    private val sources: Map<ItemId, LibrarySource>,
    val fake: FakeMediaServer = FakeMediaServer(),
) : MediaServer by fake {
    /** Every items or IDs call, in order. */
    val calls = mutableListOf<PagedCall>()

    /** Runs before each items or IDs call is served. Return an error to fail the call, or null to serve it. */
    var respond: (PagedCall) -> MediaServerError? = { null }

    /** A page asking for more than this many items fails with [MediaServerError.ServerError] (the size limit). */
    var maxItemsPerResponse: Int = Int.MAX_VALUE

    /** A page that includes one of these fails with [MediaServerError.NotJellyfin] (the strict decode). */
    var malformed: Set<ItemId> = emptySet()

    /** Items the ID listing names but the item pages never return (a listed ID that cannot be fetched). */
    var hiddenFromItems: Set<ItemId> = emptySet()

    /** The series of each library, for [librarySeries] and [librarySeriesIds]. A library with none has no series. */
    val seriesSources = mutableMapOf<ItemId, SeriesSource>()

    /** What [libraries] answers, when it should fail. */
    var librariesFailure: MediaServerError? = null

    val itemCalls: List<PagedCall> get() = calls.filter { !it.idsOnly && !it.series }

    override suspend fun libraries(session: Session): ServerResult<List<Library>> {
        librariesFailure?.let { return ServerResult.Failure(it) }
        return ServerResult.Success(libraries)
    }

    override suspend fun libraryItems(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
        changedSince: Instant?,
    ): ServerResult<LibraryItemsPage> {
        require(startIndex >= 0 && limit in 1..MediaServer.MAX_PAGE_SIZE) { "Bad page request" }
        val call = PagedCall(calls.size + 1, library, startIndex, limit, changedSince, idsOnly = false)
        calls += call
        respond(call)?.let { return ServerResult.Failure(it) }
        if (limit > maxItemsPerResponse) return ServerResult.Failure(MediaServerError.ServerError())
        val source = sources.getValue(library.id)
        val items = selected(source, startIndex, limit, changedSince).filter { it.id !in hiddenFromItems }
        if (items.any { it.id in malformed }) return ServerResult.Failure(MediaServerError.NotJellyfin())
        val total = if (startIndex == 0) totalOf(source, changedSince) else null
        return ServerResult.Success(LibraryItemsPage(startIndex, total, items))
    }

    override suspend fun libraryItemIds(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage> {
        require(startIndex >= 0 && limit in 1..MediaServer.MAX_PAGE_SIZE) { "Bad page request" }
        val call = PagedCall(calls.size + 1, library, startIndex, limit, null, idsOnly = true)
        calls += call
        respond(call)?.let { return ServerResult.Failure(it) }
        val source = sources.getValue(library.id)
        val ids = source.page(startIndex, limit).map { it.id }
        return ServerResult.Success(ItemIdPage(startIndex, if (startIndex == 0) source.size else null, ids))
    }

    override suspend fun librarySeries(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
        changedSince: Instant?,
    ): ServerResult<SeriesPage> {
        require(startIndex >= 0 && limit in 1..MediaServer.MAX_PAGE_SIZE) { "Bad page request" }
        val call = PagedCall(calls.size + 1, library, startIndex, limit, changedSince, idsOnly = false, series = true)
        calls += call
        respond(call)?.let { return ServerResult.Failure(it) }
        val source = seriesSources[library.id] ?: SeriesSource()
        val matching = source.series.filter { changedSince == null || source.savedAt(it) >= changedSince }
        val total = if (startIndex == 0) matching.size else null
        val page = matching.drop(startIndex).take(limit)
        if (page.any { it.id in malformed }) return ServerResult.Failure(MediaServerError.NotJellyfin())
        return ServerResult.Success(SeriesPage(startIndex, total, page))
    }

    override suspend fun librarySeriesIds(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage> {
        require(startIndex >= 0 && limit in 1..MediaServer.MAX_PAGE_SIZE) { "Bad page request" }
        val call = PagedCall(calls.size + 1, library, startIndex, limit, null, idsOnly = true, series = true)
        calls += call
        respond(call)?.let { return ServerResult.Failure(it) }
        val all = seriesSources[library.id]?.series.orEmpty()
        val ids = all.drop(startIndex).take(limit).map { it.id }
        return ServerResult.Success(ItemIdPage(startIndex, if (startIndex == 0) all.size else null, ids))
    }

    private fun selected(
        source: LibrarySource,
        startIndex: Int,
        limit: Int,
        changedSince: Instant?,
    ): List<MediaItem> =
        if (changedSince == null) {
            source.page(startIndex, limit)
        } else {
            changed(source, changedSince).drop(startIndex).take(limit).toList()
        }

    private fun totalOf(
        source: LibrarySource,
        changedSince: Instant?,
    ): Int = if (changedSince == null) source.size else changed(source, changedSince).count()

    // Read in chunks, so a generated library is never held whole.
    private fun changed(
        source: LibrarySource,
        since: Instant,
    ): Sequence<MediaItem> =
        sequence {
            var start = 0
            while (start < source.size) {
                yieldAll(source.page(start, CHUNK))
                start += CHUNK
            }
        }.filter { source.savedAt(it) >= since }

    private companion object {
        const val CHUNK = 500
    }
}
