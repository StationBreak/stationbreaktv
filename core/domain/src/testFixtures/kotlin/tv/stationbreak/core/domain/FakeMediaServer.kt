// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.CollectionMemberPage
import tv.stationbreak.core.model.CollectionPage
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemIdPage
import tv.stationbreak.core.model.ItemPage
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryItemsPage
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.SeriesPage
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.core.model.StoredSession
import tv.stationbreak.core.model.UserItemState
import kotlin.time.Duration
import kotlin.time.Instant

/** The arguments of one [MediaServer.libraryItems] call, as the fake recorded it. */
data class LibraryItemsCall(
    val library: Library,
    val startIndex: Int,
    val limit: Int,
    val changedSince: Instant?,
)

/** A scripted [MediaServer] for tests of code that uses the port (ADR-0004). Not thread-safe. */
class FakeMediaServer : MediaServer {
    var discoverResult: ServerResult<ResolvedServer> = ServerResult.Failure(MediaServerError.Unknown())
    var connectResult: ServerResult<ResolvedServer> = ServerResult.Failure(MediaServerError.Unknown())
    var trustResult: ServerResult<ResolvedServer> = ServerResult.Failure(MediaServerError.Unknown())
    var signInResult: ServerResult<Session> = ServerResult.Failure(MediaServerError.Unknown())
    var resumeResult: ServerResult<Session> = ServerResult.Failure(MediaServerError.Unknown())
    var revokeResult: ServerResult<Unit> = ServerResult.Success(Unit)
    var quickConnectStates: List<QuickConnectState> = emptyList()
    var librariesResult: ServerResult<List<Library>> = ServerResult.Failure(MediaServerError.Unknown())
    var itemsResult: ServerResult<ItemPage> = ServerResult.Failure(MediaServerError.Unknown())
    var libraryItemsResult: ServerResult<LibraryItemsPage> = ServerResult.Failure(MediaServerError.Unknown())
    var libraryItemIdsResult: ServerResult<ItemIdPage> = ServerResult.Failure(MediaServerError.Unknown())
    var librarySeriesResult: ServerResult<SeriesPage> = ServerResult.Failure(MediaServerError.Unknown())
    var librarySeriesIdsResult: ServerResult<ItemIdPage> = ServerResult.Failure(MediaServerError.Unknown())
    var collectionsResult: ServerResult<CollectionPage> = ServerResult.Failure(MediaServerError.Unknown())
    var collectionIdsResult: ServerResult<ItemIdPage> = ServerResult.Failure(MediaServerError.Unknown())
    var collectionMembersResult: ServerResult<CollectionMemberPage> = ServerResult.Failure(MediaServerError.Unknown())
    var playbackInfoResult: ServerResult<PlaybackInfo> = ServerResult.Failure(MediaServerError.Unknown())
    var stopEncodingResult: ServerResult<Unit> = ServerResult.Success(Unit)

    /** How long [stopEncoding] takes before it answers (virtual time in a test); the stop is recorded when it ends. */
    var stopEncodingDelay: Duration = Duration.ZERO
    var reportResult: ServerResult<Unit> = ServerResult.Success(Unit)

    /** What [userData] answers. */
    var userDataResult: ServerResult<UserItemState> = ServerResult.Success(UserItemState(Duration.ZERO, false))

    /** What [itemDetails] answers; null answers a plain success carrying the asked-for ID. */
    var itemDetailsResult: ServerResult<ItemDetails>? = null

    /** When set, [itemDetails] records the call and then waits for it, to hold a request open. */
    var itemDetailsGate: CompletableDeferred<Unit>? = null
    var markPlayedResult: ServerResult<Unit> = ServerResult.Success(Unit)

    /** Runs when [revoke] is called, before it answers. */
    var onRevoke: () -> Unit = {}

    /** When set, [revoke] waits for it, to let a test cancel the caller in the middle of the call. */
    var revokeGate: CompletableDeferred<Unit>? = null

    /** Completed when [revoke] has returned. */
    val revokeFinished = CompletableDeferred<Unit>()

    val discoverCalls = mutableListOf<String>()
    val trustCalls = mutableListOf<Pair<String, SpkiPin>>()
    val connectCalls = mutableListOf<Pair<ServerAddress, CleartextAcknowledgement?>>()
    val resumeCalls = mutableListOf<StoredSession>()
    val revokeCalls = mutableListOf<Session>()
    val librariesCalls = mutableListOf<Session>()

    /** The library, start index and limit of each [items] call. */
    val itemsCalls = mutableListOf<Triple<Library, Int, Int>>()

    /** Each [libraryItems] call. */
    val libraryItemsCalls = mutableListOf<LibraryItemsCall>()

    /** The library, start index and limit of each [libraryItemIds] call. */
    val libraryItemIdsCalls = mutableListOf<Triple<Library, Int, Int>>()

    /** Each [librarySeries] call, as the arguments of a [libraryItems] call. */
    val librarySeriesCalls = mutableListOf<LibraryItemsCall>()

    /** The library, start index and limit of each [librarySeriesIds] call. */
    val librarySeriesIdsCalls = mutableListOf<Triple<Library, Int, Int>>()

    /** Each [collections] call: start index, limit and the changed-since moment. */
    val collectionsCalls = mutableListOf<Triple<Int, Int, Instant?>>()

    /** The start index and limit of each [collectionIds] call. */
    val collectionIdsCalls = mutableListOf<Pair<Int, Int>>()

    /** The collection, start index and limit of each [collectionMembers] call. */
    val collectionMembersCalls = mutableListOf<Triple<ItemId, Int, Int>>()
    val playbackInfoCalls = mutableListOf<Pair<ItemId, PlaybackRequest>>()
    val stoppedEncodes = mutableListOf<PlaySessionId>()

    /** The reports sent, in order, as the call name and the report. */
    val reports = mutableListOf<Pair<String, PlaybackReport>>()

    /** The `failed` flag of each [reportStopped] call, in order. */
    val stoppedFailed = mutableListOf<Boolean>()
    val userDataCalls = mutableListOf<ItemId>()

    /** Every [itemDetails] call's item, in order. */
    val itemDetailsCalls = mutableListOf<ItemId>()
    val markPlayedCalls = mutableListOf<ItemId>()
    val quickConnectCalls = mutableListOf<Pair<ServerAddress, CleartextAcknowledgement?>>()

    /** The addresses and usernames of password sign-ins. Passwords are not kept. */
    val signInCalls = mutableListOf<Pair<ServerAddress, String>>()

    override suspend fun discover(input: String): ServerResult<ResolvedServer> {
        discoverCalls += input
        return discoverResult
    }

    override suspend fun connect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): ServerResult<ResolvedServer> {
        connectCalls += address to acknowledgement
        return connectResult
    }

    override suspend fun trust(
        input: String,
        pin: SpkiPin,
    ): ServerResult<ResolvedServer> {
        trustCalls += input to pin
        return trustResult
    }

    override suspend fun signIn(request: SignInRequest): ServerResult<Session> {
        signInCalls += request.address to request.username
        return signInResult
    }

    override suspend fun resume(stored: StoredSession): ServerResult<Session> {
        resumeCalls += stored
        return resumeResult
    }

    override fun quickConnect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): Flow<QuickConnectState> {
        quickConnectCalls += address to acknowledgement
        return quickConnectStates.asFlow()
    }

    override suspend fun revoke(session: Session): ServerResult<Unit> {
        revokeCalls += session
        onRevoke()
        revokeGate?.await()
        revokeFinished.complete(Unit)
        return revokeResult
    }

    override suspend fun libraries(session: Session): ServerResult<List<Library>> {
        librariesCalls += session
        return librariesResult
    }

    override suspend fun items(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemPage> {
        require(startIndex >= 0 && limit in 1..MediaServer.MAX_PAGE_SIZE) { "Bad page request" }
        itemsCalls += Triple(library, startIndex, limit)
        return itemsResult
    }

    override suspend fun libraryItems(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
        changedSince: Instant?,
    ): ServerResult<LibraryItemsPage> {
        require(startIndex >= 0 && limit in 1..MediaServer.MAX_PAGE_SIZE) { "Bad page request" }
        libraryItemsCalls += LibraryItemsCall(library, startIndex, limit, changedSince)
        return libraryItemsResult
    }

    override suspend fun libraryItemIds(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage> {
        require(startIndex >= 0 && limit in 1..MediaServer.MAX_PAGE_SIZE) { "Bad page request" }
        libraryItemIdsCalls += Triple(library, startIndex, limit)
        return libraryItemIdsResult
    }

    override suspend fun librarySeries(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
        changedSince: Instant?,
    ): ServerResult<SeriesPage> {
        require(startIndex >= 0 && limit in 1..MediaServer.MAX_PAGE_SIZE) { "Bad page request" }
        librarySeriesCalls += LibraryItemsCall(library, startIndex, limit, changedSince)
        return librarySeriesResult
    }

    override suspend fun librarySeriesIds(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage> {
        require(startIndex >= 0 && limit in 1..MediaServer.MAX_PAGE_SIZE) { "Bad page request" }
        librarySeriesIdsCalls += Triple(library, startIndex, limit)
        return librarySeriesIdsResult
    }

    override suspend fun collections(
        session: Session,
        startIndex: Int,
        limit: Int,
        changedSince: Instant?,
    ): ServerResult<CollectionPage> {
        require(startIndex >= 0 && limit in 1..MediaServer.MAX_PAGE_SIZE) { "Bad page request" }
        collectionsCalls += Triple(startIndex, limit, changedSince)
        return collectionsResult
    }

    override suspend fun collectionIds(
        session: Session,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage> {
        require(startIndex >= 0 && limit in 1..MediaServer.MAX_PAGE_SIZE) { "Bad page request" }
        collectionIdsCalls += startIndex to limit
        return collectionIdsResult
    }

    override suspend fun collectionMembers(
        session: Session,
        collection: ItemId,
        startIndex: Int,
        limit: Int,
    ): ServerResult<CollectionMemberPage> {
        require(startIndex >= 0 && limit in 1..MediaServer.MAX_PAGE_SIZE) { "Bad page request" }
        collectionMembersCalls += Triple(collection, startIndex, limit)
        return collectionMembersResult
    }

    override suspend fun playbackInfo(
        session: Session,
        item: ItemId,
        request: PlaybackRequest,
    ): ServerResult<PlaybackInfo> {
        playbackInfoCalls += item to request
        return playbackInfoResult
    }

    override suspend fun stopEncoding(
        session: Session,
        playSession: PlaySessionId,
    ): ServerResult<Unit> {
        if (stopEncodingDelay > Duration.ZERO) delay(stopEncodingDelay)
        stoppedEncodes += playSession
        return stopEncodingResult
    }

    override suspend fun reportStart(
        session: Session,
        report: PlaybackReport,
    ): ServerResult<Unit> = record("start", report)

    override suspend fun reportProgress(
        session: Session,
        report: PlaybackReport,
    ): ServerResult<Unit> = record("progress", report)

    override suspend fun reportStopped(
        session: Session,
        report: PlaybackReport,
        failed: Boolean,
    ): ServerResult<Unit> {
        stoppedFailed += failed
        return record("stopped", report)
    }

    override suspend fun userData(
        session: Session,
        itemId: ItemId,
    ): ServerResult<UserItemState> {
        userDataCalls += itemId
        return userDataResult
    }

    override suspend fun itemDetails(
        session: Session,
        itemId: ItemId,
    ): ServerResult<ItemDetails> {
        itemDetailsCalls += itemId
        itemDetailsGate?.await()
        return itemDetailsResult
            ?: ServerResult.Success(ItemDetails(itemId, "About it.", 2001, listOf("Drama"), null))
    }

    override suspend fun markPlayed(
        session: Session,
        itemId: ItemId,
    ): ServerResult<Unit> {
        markPlayedCalls += itemId
        return markPlayedResult
    }

    private fun record(
        name: String,
        report: PlaybackReport,
    ): ServerResult<Unit> {
        reports += name to report
        return reportResult
    }
}
