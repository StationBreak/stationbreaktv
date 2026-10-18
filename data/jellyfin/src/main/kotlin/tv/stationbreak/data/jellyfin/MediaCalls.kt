// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.flatMap
import tv.stationbreak.core.domain.map
import tv.stationbreak.core.model.CollectionInfo
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.CollectionMemberPage
import tv.stationbreak.core.model.CollectionPage
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
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.core.model.SeriesPage
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.StreamUrls
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
                COLLAPSE_BOX_SETS_OFF,
            )
        return calls.send(session, Endpoint.get("Items", query)).flatMap { text ->
            Wire.parse(text, ItemsDto.serializer()).flatMap { page ->
                convert { ItemPage(page.totalRecordCount ?: 0, page.startIndex, page.items.map(::toMediaItem)) }
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
                firstPageTotal(startIndex, page.totalRecordCount).flatMap { total ->
                    convert { LibraryItemsPage(startIndex, total, page.items.map(::toMediaItem)) }
                }
            }
        }
    }

    suspend fun libraryItemIds(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage> = idListing(session, library, startIndex, limit, CACHED_TYPES)

    suspend fun librarySeries(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
        changedSince: Instant?,
    ): ServerResult<SeriesPage> {
        val query =
            recursiveQuery(library, startIndex, limit, SERIES_TYPE) +
                listOfNotNull(
                    "fields" to SERIES_FIELDS,
                    changedSince?.let { "minDateLastSaved" to utcMillis(it) },
                )
        return calls.send(session, Endpoint.get("Items", query)).flatMap { text ->
            Wire.parse(text, SeriesItemsDto.serializer()).flatMap { page ->
                firstPageTotal(startIndex, page.totalRecordCount).flatMap { total ->
                    convert { SeriesPage(startIndex, total, page.items.map(::toSeriesInfo)) }
                }
            }
        }
    }

    suspend fun librarySeriesIds(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage> = idListing(session, library, startIndex, limit, SERIES_TYPE)

    suspend fun collections(
        session: Session,
        startIndex: Int,
        limit: Int,
        changedSince: Instant?,
    ): ServerResult<CollectionPage> {
        val query = boxSetQuery(startIndex, limit) + listOfNotNull(changedSince?.let { "minDateLastSaved" to utcMillis(it) })
        return calls.send(session, Endpoint.get("Items", query)).flatMap { text ->
            Wire.parse(text, CollectionsDto.serializer()).flatMap { page ->
                firstPageTotal(startIndex, page.totalRecordCount).flatMap { total ->
                    convert { CollectionPage(startIndex, total, page.items.map(::toCollectionInfo)) }
                }
            }
        }
    }

    suspend fun collectionIds(
        session: Session,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage> = idPage(session, boxSetQuery(startIndex, limit), startIndex)

    suspend fun collectionMembers(
        session: Session,
        collection: ItemId,
        startIndex: Int,
        limit: Int,
    ): ServerResult<CollectionMemberPage> {
        // The direct children only: a recursive call would flatten nested BoxSets into the member list.
        val query = listOf("parentId" to collection.value) + pagedQuery(startIndex, limit) + NO_EXTRAS
        return calls.send(session, Endpoint.get("Items", query)).flatMap { text ->
            Wire.parse(text, CollectionMembersDto.serializer()).flatMap { page ->
                firstPageTotal(startIndex, page.totalRecordCount).flatMap { total ->
                    convert { CollectionMemberPage(startIndex, total, page.items.map(::toCollectionMember)) }
                }
            }
        }
    }

    /** Every BoxSet the account can see, in any place: no `parentId`, because a collection is under no library. */
    private fun boxSetQuery(
        startIndex: Int,
        limit: Int,
    ): List<Pair<String, String>> =
        listOf("recursive" to "true", "includeItemTypes" to BOX_SET_TYPE) + pagedQuery(startIndex, limit) + NO_EXTRAS

    private suspend fun idListing(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
        types: String,
    ): ServerResult<ItemIdPage> = idPage(session, recursiveQuery(library, startIndex, limit, types) + ID_ONLY, startIndex)

    private suspend fun idPage(
        session: Session,
        query: List<Pair<String, String>>,
        startIndex: Int,
    ): ServerResult<ItemIdPage> =
        calls.send(session, Endpoint.get("Items", query)).flatMap { text ->
            Wire.parse(text, ItemIdsDto.serializer()).flatMap { page ->
                firstPageTotal(startIndex, page.totalRecordCount).flatMap { total ->
                    convert { ItemIdPage(startIndex, total, page.items.map { ItemId(it.id) }) }
                }
            }
        }

    /**
     * The query the cache calls share: the library's items of [types] at any depth, in a stable order. The total
     * count is asked for on the first page only, because the server counts the whole library for it.
     */
    private fun recursiveQuery(
        library: Library,
        startIndex: Int,
        limit: Int,
        types: String = CACHED_TYPES,
    ): List<Pair<String, String>> =
        listOf(
            "parentId" to library.id.value,
            "recursive" to "true",
            "includeItemTypes" to types,
            COLLAPSE_BOX_SETS_OFF,
        ) + pagedQuery(startIndex, limit)

    /**
     * The paging every cache call shares, after the argument checks: a stable order and the total asked for on the
     * first page only. Jellyfin 12 has no sort by ID (an unknown key is ignored), but it breaks every tie on the ID, in
     * ascending order whatever the direction (contract test: tiedNamesPageStably), so this order is total.
     */
    private fun pagedQuery(
        startIndex: Int,
        limit: Int,
    ): List<Pair<String, String>> {
        require(startIndex >= 0) { "startIndex can't be negative" }
        require(limit in 1..MediaServer.MAX_PAGE_SIZE) { "limit must be 1 to ${MediaServer.MAX_PAGE_SIZE}" }
        return listOf(
            "sortBy" to "SortName",
            "sortOrder" to "Ascending",
            "startIndex" to startIndex.toString(),
            "limit" to limit.toString(),
            "enableTotalRecordCount" to (startIndex == 0).toString(),
        )
    }

    /**
     * The total for a page: asked for, and so required, on the first page; never asked for, and so null, after it.
     * A first page without one is not an empty library, it is an answer we can't trust, so the page fails.
     */
    private fun firstPageTotal(
        startIndex: Int,
        total: Int?,
    ): ServerResult<Int?> =
        when {
            startIndex != 0 -> ServerResult.Success(null)
            total == null -> ServerResult.Failure(MediaServerError.ServerError())
            else -> ServerResult.Success(total)
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

    private companion object {
        /**
         * With the user's "group movies into collections" setting on, the server hides a BoxSet's movies behind the
         * BoxSet unless an item call says otherwise. Every item call says so (spec 10, Vimes H5), so the cache lists
         * the movies whatever the setting.
         */
        val COLLAPSE_BOX_SETS_OFF = "collapseBoxSetItems" to "false"

        /** The video kinds the cache holds (spec 06): never series, seasons, folders or collections. */
        const val CACHED_TYPES = "Movie,Episode,Video,MusicVideo"

        /**
         * The optional fields the cache needs. `DateLastSaved` is not among them: neither 12.1 nor 12.2 returns it,
         * even when asked (contract test). The rest (production year, rating, series, season and episode numbers,
         * runtime) come with every item, so asking for more would only make the answer bigger.
         */
        const val ITEM_FIELDS = "DateCreated,PremiereDate,Genres,Tags,ParentId"

        /** The series calls (spec 10): only Series, never an episode, with the two fields a rule matches on. */
        const val SERIES_TYPE = "Series"

        /** `OfficialRating` and the name come with every item, so a series needs only these two asked for. */
        const val SERIES_FIELDS = "Genres,Tags"

        /** The collection calls (spec 10, Unit 2): BoxSets, listed and read by their members. */
        const val BOX_SET_TYPE = "BoxSet"

        /**
         * What the ID listing leaves out. Images and user data are most of an item's bytes and the ID check reads
         * neither: on 12.1 and 12.2 a 6-item page measured 7.5 kB with the cache fields, 5.9 kB bare, 4.0 kB without
         * images and 2.9 kB without images and user data.
         */
        val ID_ONLY = listOf("enableImages" to "false", "enableUserData" to "false")

        /**
         * The collection calls read IDs, names and types, so they ask for no images, no user data and no fields
         * (Jellyfin issue #18069: out-of-memory on a huge collection library on 12.1). They send the item calls' flag
         * too, so the "group movies into collections" setting can't hide a member behind its BoxSet.
         */
        val NO_EXTRAS = ID_ONLY + COLLAPSE_BOX_SETS_OFF

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

/** One server item as the domain model's item. Dates and runtimes follow rules format v1, "Scheduler inputs". */
internal fun toMediaItem(dto: ItemDto): MediaItem =
    MediaItem(
        id = ItemId(dto.id),
        name = dto.name,
        kind = ITEM_KINDS[dto.type] ?: ItemKind.Other,
        runtime = dto.runTimeTicks?.let(::duration),
        parentId = dto.parentId?.let(::ItemId),
        dateCreated = createdAtOf(dto.dateCreated),
        premiereDate = premiereDateOf(dto.premiereDate),
        productionYear = dto.productionYear,
        officialRating = dto.officialRating,
        genres = dto.genres.orEmpty(),
        tags = dto.tags.orEmpty(),
        seriesId = dto.seriesId?.let(::ItemId),
        seasonId = dto.seasonId?.let(::ItemId),
        seasonNumber = dto.seasonNumber,
        episodeNumber = dto.episodeNumber,
    )

/** One BoxSet of a page: its ID and the name a person gave it. */
internal fun toCollectionInfo(dto: CollectionDto): CollectionInfo = CollectionInfo(ItemId(dto.id), dto.name)

/** One member of a BoxSet: its ID and what the server says it is. Only the type is read, never the item. */
internal fun toCollectionMember(dto: CollectionMemberDto): CollectionMember =
    CollectionMember(ItemId(dto.id), MEMBER_KINDS[dto.type] ?: CollectionMemberKind.Other)

/** One series of a page. The rating is passed on exactly as the server sent it: never trimmed, never blank-to-null. */
internal fun toSeriesInfo(dto: SeriesDto): SeriesInfo =
    SeriesInfo(
        id = ItemId(dto.id),
        name = dto.name,
        officialRating = dto.officialRating,
        genres = dto.genres.orEmpty(),
        tags = dto.tags.orEmpty(),
    )

/**
 * `DateCreated` as the server's creation moment, in whole milliseconds: `floorDiv(T, 10000)` for `T` the time in
 * 100 ns ticks since the epoch, any UTC offset honoured, before 1970 too. Jellyfin writes seven fraction digits,
 * which `java.time` reads in full, and the seconds are floored first, so the result is exactly the format's
 * formula. A time with no offset or zone is read as UTC. Null when the text is missing, unreadable or out of range:
 * one odd date must not fail a page.
 */
internal fun createdAtOf(text: String?): Instant? =
    text?.let {
        try {
            val moment = momentOf(it)?.toInstant()
            if (moment == null) {
                null
            } else {
                // Instant keeps its nanoseconds in 0..999,999,999 also before 1970, so this is a floor.
                val wholeSeconds = Math.multiplyExact(moment.epochSecond, MS_PER_SECOND)
                // Any Long of milliseconds is inside kotlin's Instant range, so nothing is clamped; overflow throws.
                Instant.fromEpochMilliseconds(Math.addExact(wholeSeconds, (moment.nano / NANOS_PER_MS).toLong()))
            }
        } catch (_: DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

/** The text as a date and time with an offset, or null. A time with no offset or zone is read as UTC. */
private fun momentOf(text: String): OffsetDateTime? =
    when (val parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(text, OffsetDateTime::from, LocalDateTime::from)) {
        is OffsetDateTime -> parsed
        is LocalDateTime -> parsed.atOffset(ZoneOffset.UTC)
        else -> null
    }

/**
 * `RunTimeTicks` as the runtime, in exactly `floorDiv(ticks, 10000)` milliseconds. A missing, zero or negative value
 * is no runtime.
 */
internal fun duration(ticks: Long): Duration? = (ticks / TICKS_PER_MS).milliseconds.takeIf { ticks > 0 }

/**
 * The UTC calendar date (`YYYY-MM-DD`) of a `PremiereDate`, or null if it is missing, unreadable or its UTC year is
 * outside 1 to 9999, which the rules format does not allow. A time with no offset or zone is read as UTC.
 */
internal fun premiereDateOf(text: String?): String? =
    text?.let {
        try {
            val date = momentOf(it)?.withOffsetSameInstant(ZoneOffset.UTC)?.toLocalDate()
            date?.takeIf { day -> day.year in FIRST_YEAR..LAST_YEAR }?.toString()
        } catch (_: DateTimeException) {
            null
        }
    }

private const val FIRST_YEAR = 1
private const val LAST_YEAR = 9999
private const val MS_PER_SECOND = 1000L
private const val NANOS_PER_MS = 1_000_000
private const val TICKS_PER_MS = 10_000L

private val ITEM_KINDS =
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

// What a BoxSet's member can be to the cache; every other type, and a missing one, is Other.
private val MEMBER_KINDS =
    mapOf(
        "Movie" to CollectionMemberKind.Movie,
        "Episode" to CollectionMemberKind.Episode,
        "Series" to CollectionMemberKind.Series,
        "Season" to CollectionMemberKind.Season,
        "BoxSet" to CollectionMemberKind.Collection,
    )
