// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.ListLibrarySource
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.selection.FirstSyncLibraries
import tv.stationbreak.core.domain.selection.SelectionReady
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

/** Placeholder libraries and session for the sync tests. The token is a marker the secret scan looks for. */
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

/**
 * The first sync's library set as the selection writes it (spec 26, Part 4 §2 `firstSyncLibraries`), for the tests
 * that run the first sync with no selection: the libraries [chosen] gives are kept, and so is every add under way (a
 * library stored with a stamp and no hold), each with its stamp; the rest go. It gives the chosen libraries.
 */
internal class StoredChoice(
    private val repository: RoomLibraryRepository,
    private val clock: Clock,
    private val chosen: suspend () -> Set<ItemId>,
) : FirstSyncLibraries {
    /** How many times the first sync asked for its library set. */
    var reads = 0
        private set

    override suspend fun firstSyncLibraries(account: LibraryAccount): Set<ItemId> {
        reads++
        val ids = chosen()
        val adding = repository.storedLibraries(account).filter { it.addStamp != null && it.heldUntilMs == null }
        repository.applySelection(account, ids + adding.map { it.id }, nowMs = clock.now().toEpochMilliseconds())
        return ids
    }
}

/** Runs the first sync over [chosen] for [account], as the selection would give it (see [StoredChoice]). */
internal suspend fun FirstSyncEngine.run(
    session: Session,
    account: LibraryAccount,
    chosen: Set<ItemId>,
): SyncOutcome = run(session, account, StoredChoice(repository, clock) { chosen })

/**
 * The first-sync runner as the app builds it, with the selection already resumed and its library set the one the
 * settings hold (see [StoredChoice]).
 */
@Suppress("LongParameterList") // The runner's stores, one for one.
internal fun firstSyncRunner(
    settings: ServerSettings,
    sessions: SessionRepository,
    server: MediaServer,
    repository: RoomLibraryRepository,
    clock: Clock,
    ready: SelectionReady = SelectionReady.Complete,
): FirstSyncRunner {
    val libraries = StoredChoice(repository, clock) { settings.loadLibraries().orEmpty() }
    return FirstSyncRunner(settings, sessions, server, repository, libraries, ready, clock)
}
