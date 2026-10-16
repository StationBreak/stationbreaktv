// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.flatMap
import tv.stationbreak.core.domain.map
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemIdPage
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.ItemPage
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryItemsPage
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.StreamUrls
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Instant

/**
 * The libraries, items, playback-info and reporting calls (S0.3.3, R2). All of them go through [AuthorizedCall], so the
 * cleartext gate, the token binding, the no-redirect client and the header rules apply as they do to sign-out. Answers
 * are read into the small lenient DTOs in `Wire.kt`, and anything that is not a plain value of the domain model (a URL,
 * a file path, a key) is dropped there.
 */
@Suppress("TooManyFunctions") // One class for the media surface (libraries, items, playback, reports).
internal class MediaCalls(
    private val calls: AuthorizedCall,
) {
    suspend fun libraries(session: Session): ServerResult<List<Library>> =
        calls.send(session, Endpoint.get("UserViews")).flatMap { text ->
            Wire.parse(text, ViewsDto.serializer()).flatMap { views ->
                convert { views.items.mapNotNull(::library) }
            }
        }

    suspend fun items(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemPage> {
        require(startIndex >= 0) { "startIndex can't be negative" }
        require(limit in 1..MediaServer.MAX_PAGE_SIZE) { "limit must be 1 to ${MediaServer.MAX_PAGE_SIZE}" }
        val query =
            listOf(
                "parentId" to library.id.value,
                "startIndex" to startIndex.toString(),
                "limit" to limit.toString(),
                "sortBy" to "SortName",
                "sortOrder" to "Ascending",
            )
        return calls.send(session, Endpoint.get("Items", query)).flatMap { text ->
            Wire.parse(text, ItemsDto.serializer()).flatMap { page ->
                convert { ItemPage(page.totalRecordCount, page.startIndex, page.items.map(::item)) }
            }
        }
    }

    suspend fun libraryItems(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
        changedSince: Instant?,
    ): ServerResult<LibraryItemsPage> {
        val query =
            recursiveQuery(library, startIndex, limit) +
                listOfNotNull(
                    "fields" to ITEM_FIELDS,
                    changedSince?.let { "minDateLastSaved" to utcMillis(it) },
                )
        return calls.send(session, Endpoint.get("Items", query)).flatMap { text ->
            Wire.parse(text, ItemsDto.serializer()).flatMap { page ->
                convert {
                    LibraryItemsPage(
                        startIndex = startIndex,
                        totalCount = if (startIndex == 0) page.totalRecordCount else null,
                        items = page.items.map(::item),
                    )
                }
            }
        }
    }

    suspend fun libraryItemIds(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage> =
        calls.send(session, Endpoint.get("Items", recursiveQuery(library, startIndex, limit))).flatMap { text ->
            Wire.parse(text, ItemIdsDto.serializer()).flatMap { page ->
                convert {
                    ItemIdPage(
                        startIndex = startIndex,
                        totalCount = if (startIndex == 0) page.totalRecordCount else null,
                        ids = page.items.map { ItemId(it.id) },
                    )
                }
            }
        }

    /**
     * The query both cache calls share: the library's video items at any depth, in a stable order. The total
     * count is asked for on the first page only, because the server counts the whole library for it.
     */
    private fun recursiveQuery(
        library: Library,
        startIndex: Int,
        limit: Int,
    ): List<Pair<String, String>> {
        require(startIndex >= 0) { "startIndex can't be negative" }
        require(limit in 1..MediaServer.MAX_PAGE_SIZE) { "limit must be 1 to ${MediaServer.MAX_PAGE_SIZE}" }
        return listOf(
            "parentId" to library.id.value,
            "recursive" to "true",
            "includeItemTypes" to CACHED_TYPES,
            // Jellyfin 12 has no sort by ID (an unknown key is ignored), but it breaks every tie on the ID, in
            // ascending order whatever the direction (contract test: tiedNamesPageStably), so this order is total.
            "sortBy" to "SortName",
            "sortOrder" to "Ascending",
            "startIndex" to startIndex.toString(),
            "limit" to limit.toString(),
            "enableTotalRecordCount" to (startIndex == 0).toString(),
        )
    }

    /** The moment as UTC text with millisecond precision, which is as fine as the server reads. */
    private fun utcMillis(at: Instant): String = Instant.fromEpochMilliseconds(at.toEpochMilliseconds()).toString()

    suspend fun playbackInfo(
        session: Session,
        item: ItemId,
    ): ServerResult<PlaybackInfo> =
        calls.send(session, Endpoint.post("Items/${item.value}/PlaybackInfo", PlaybackProfile.BODY)).flatMap { text ->
            Wire.parse(text, PlaybackInfoDto.serializer()).flatMap { info ->
                if (info.errorCode != null) {
                    ServerResult.Failure(MediaServerError.ServerError())
                } else {
                    convert { playbackInfo(session.address, item, info) }
                }
            }
        }

    suspend fun report(
        session: Session,
        path: String,
        report: PlaybackReport,
        withPaused: Boolean,
    ): ServerResult<Unit> {
        val body =
            PlaybackReportDto(
                itemId = report.itemId.value,
                mediaSourceId = report.sourceId.value,
                positionTicks = PlaybackTicks.of(report.position),
                playSessionId = report.playSessionId?.value,
                isPaused = if (withPaused) report.isPaused else null,
            )
        val endpoint = Endpoint.post(path, Wire.encode(PlaybackReportDto.serializer(), body))
        return calls.send(session, endpoint).map { }
    }

    private inline fun <T> convert(build: () -> T): ServerResult<T> =
        try {
            ServerResult.Success(build())
        } catch (_: IllegalArgumentException) {
            // A blank or unsafe ID in an otherwise well-formed answer.
            ServerResult.Failure(MediaServerError.NotJellyfin())
        }

    private fun library(view: ViewDto): Library? {
        val kind = kind(view.collectionType) ?: return null
        return Library(ItemId(view.id), view.name, kind)
    }

    private fun item(dto: ItemDto): MediaItem =
        MediaItem(
            id = ItemId(dto.id),
            name = dto.name,
            kind = ITEM_KINDS[dto.type] ?: ItemKind.Other,
            runtime = dto.runTimeTicks?.let(::duration),
            parentId = dto.parentId?.let(::ItemId),
            dateCreated = instant(dto.dateCreated),
            dateLastSaved = instant(dto.dateLastSaved),
            productionYear = dto.productionYear,
            officialRating = dto.officialRating,
            genres = dto.genres.orEmpty(),
            tags = dto.tags.orEmpty(),
            seriesId = dto.seriesId?.let(::ItemId),
            seasonNumber = dto.seasonNumber,
            episodeNumber = dto.episodeNumber,
        )

    /** A date the server sent, or null when it is missing or unreadable: one odd date must not fail a page. */
    private fun instant(text: String?): Instant? =
        text?.let {
            try {
                Instant.parse(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    private fun playbackInfo(
        address: ServerAddress,
        item: ItemId,
        info: PlaybackInfoDto,
    ): PlaybackInfo {
        val playSession = info.playSessionId?.takeIf { it.isNotBlank() }?.let(::PlaySessionId)
        return PlaybackInfo(
            itemId = item,
            playSessionId = playSession,
            sources = info.mediaSources.map(::source),
            streams = DirectStreamUrls(address, item, playSession),
        )
    }

    private fun source(dto: MediaSourceDto): MediaSource =
        MediaSource(
            id = MediaSourceId(dto.id),
            name = dto.name,
            container = dto.container,
            runtime = dto.runTimeTicks?.let(::duration),
            supportsDirectPlay = dto.supportsDirectPlay,
            supportsDirectStream = dto.supportsDirectStream,
        )

    /** Ticks are 100 ns; a negative runtime is nonsense, so it reads as unknown. */
    private fun duration(ticks: Long): Duration? = if (ticks < 0) null else (ticks / TICKS_PER_MICROSECOND).microseconds

    private companion object {
        const val TICKS_PER_MICROSECOND = 10L

        /** The video kinds the cache holds (spec 06): never series, seasons, folders or collections. */
        const val CACHED_TYPES = "Movie,Episode,Video,MusicVideo"

        /**
         * The optional fields the cache needs. `DateLastSaved` is not among them: neither 12.1 nor 12.2 returns it,
         * even when asked (contract test), so [MediaItem.dateLastSaved] stays null. The rest (production year,
         * rating, series, season and episode numbers, runtime) come with every item, so asking for more would only
         * make the answer bigger.
         */
        const val ITEM_FIELDS = "DateCreated,Genres,Tags,ParentId"

        val ITEM_KINDS =
            mapOf(
                "Movie" to ItemKind.Movie,
                "Series" to ItemKind.Series,
                "Season" to ItemKind.Season,
                "Episode" to ItemKind.Episode,
                "Video" to ItemKind.Video,
                "MusicVideo" to ItemKind.MusicVideo,
                "Folder" to ItemKind.Folder,
                "CollectionFolder" to ItemKind.Folder,
                "UserView" to ItemKind.Folder,
            )

        /**
         * The wire values of the collection types worth a channel. An absent type is mixed content (checked against
         * the real servers by the contract suite, OQ3). A type that is present but unknown is dropped, never read as
         * absent.
         */
        fun kind(collectionType: String?): LibraryKind? =
            if (collectionType == null) {
                LibraryKind.Mixed
            } else {
                when (collectionType) {
                    "movies" -> LibraryKind.Movies
                    "tvshows" -> LibraryKind.TvShows
                    "homevideos" -> LibraryKind.HomeVideos
                    "musicvideos" -> LibraryKind.MusicVideos
                    else -> null
                }
            }
    }
}

/** The 100 ns units the server counts playback in. */
internal object PlaybackTicks {
    private const val NANOS_PER_TICK = 100L

    /** [position] in ticks; negative is zero, and a huge one can't overflow because nanoseconds are divided down. */
    fun of(position: Duration): Long = if (position.isNegative()) 0L else position.inWholeNanoseconds / NANOS_PER_TICK
}

/**
 * Stream URLs made from the base address and IDs and nothing else: no server-supplied URL, no credential in the query
 * or userinfo (R2). `playSessionId` is a hint for the server, not a secret.
 */
private class DirectStreamUrls(
    private val address: ServerAddress,
    private val item: ItemId,
    private val playSession: PlaySessionId?,
) : StreamUrls {
    override fun directStream(source: MediaSourceId): String {
        val base = checkNotNull(address.baseUrl.toHttpUrlOrNull()) { "A stored address is always a URL" }
        return base
            .newBuilder()
            .addPathSegments("Videos/${item.value}/stream")
            .addQueryParameter("static", "true")
            .addQueryParameter("mediaSourceId", source.value)
            .apply { playSession?.let { addQueryParameter("playSessionId", it.value) } }
            .build()
            .toString()
    }
}
