// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.ListLibrarySource
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** Placeholder libraries and a placeholder session for the sync tests. The token is a marker the secret scan looks for. */
internal object SyncWorld {
    const val TOKEN = "SECRET-TOKEN-MARKER-0001"

    val movies = Library(MOVIES, "Movies", LibraryKind.Movies)
    val shows = Library(SHOWS, "Shows", LibraryKind.TvShows)
    val home = Library(ItemId("lib-home"), "Home", LibraryKind.HomeVideos)

    val session =
        Session(
            userId = ALICE.user,
            token = AccessToken(TOKEN),
            server = ServerInfo("Placeholder", ALICE.server, ServerVersion(12, 2, 0)),
            address = ServerAddress(Scheme.Https, "jellyfin.example.test", 443),
            acknowledgement = null,
            isAdministrator = false,
        )

    /** An item made a day before [EPOCH], 90 minutes long, in [library] (zero-padded so IDs sort as numbers). */
    fun item(
        prefix: String,
        number: Int,
        library: ItemId = MOVIES,
        runtime: Duration? = 90.minutes,
    ) = MediaItem(
        id = ItemId("$prefix-${number.toString().padStart(5, '0')}"),
        name = "Name of $prefix $number",
        kind = ItemKind.Movie,
        runtime = runtime,
        parentId = library,
        dateCreated = EPOCH - 1.days,
        genres = listOf("g${number % 3}"),
        tags = if (number % 5 == 0) listOf("t") else emptyList(),
    )

    fun source(
        prefix: String,
        count: Int,
        library: ItemId = MOVIES,
    ) = ListLibrarySource((0 until count).map { item(prefix, it, library) }.toMutableList())

    /** A server with the movies library (and any others given), each filled from its source. */
    fun server(vararg libraries: Pair<Library, ListLibrarySource>) =
        PagedMediaServer(libraries.map { it.first }, libraries.associate { it.first.id to it.second })

    fun engine(
        repository: RoomLibraryRepository,
        server: PagedMediaServer,
        clock: Clock,
        pageSize: Int = 100,
        maxSkipped: Int = FirstSyncEngine.MAX_SKIPPED_ITEMS,
    ) = FirstSyncEngine(repository, server, clock, pageSize = pageSize, maxSkippedItems = maxSkipped)
}
