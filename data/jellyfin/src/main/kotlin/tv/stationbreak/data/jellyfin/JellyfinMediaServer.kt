// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import tv.stationbreak.core.domain.CleartextGate
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.flatMap
import tv.stationbreak.core.domain.logName
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ClientIdentity
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
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.SeriesPage
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.core.model.StoredSession
import tv.stationbreak.core.model.UserItemState
import tv.stationbreak.data.jellyfin.trust.PinRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Jellyfin implementation of [MediaServer] (ADR-0005). It takes the app's base client as an [HttpClientSource] and
 * derives its own variants from whatever that hands out, so one trust configuration covers everything and a pin change
 * reaches every client at once. No SDK type appears in a signature of this class.
 *
 * The probe is credential-free and follows the redirects [RedirectPolicy] allows. Every other call is on a client that
 * follows none, carries exactly one `Authorization` header, and puts no credential in a URL (the one exception is
 * Quick Connect's single-use secret, see [QuickConnectRequests]).
 *
 * One instance per process (`@Singleton`): it remembers which tokens it issued or confirmed, and the media call factory
 * sends `Authorization` only for those. The session repository, every `MediaServer` injection and the player's data
 * source must share it, or a session signed in on one is unbound on another (R24).
 */
@Suppress("TooManyFunctions") // The port's methods: one interface by decision (Granny's split is on hold).
@Singleton
class JellyfinMediaServer internal constructor(
    source: HttpClientSource,
    identity: ClientIdentity,
    internal val deviceIds: DeviceIdProvider,
    log: AdapterLog,
    tuning: AdapterTuning,
    private val pins: PinRegistry,
) : MediaServer {
    @Inject
    constructor(
        @BaseHttpClient source: HttpClientSource,
        pins: PinRegistry,
        identity: ClientIdentity,
        deviceIds: DeviceIdProvider,
        log: AdapterLog,
    ) : this(source, identity, deviceIds, log, AdapterTuning(), pins)

    /** An adapter on one fixed client with no pinning: for smoke tests and the contract suite. */
    constructor(
        baseClient: OkHttpClient,
        identity: ClientIdentity,
        deviceIds: DeviceIdProvider,
        log: AdapterLog,
    ) : this(HttpClientSource { baseClient }, identity, deviceIds, log, AdapterTuning(), PinRegistry.NONE)

    internal constructor(
        baseClient: OkHttpClient,
        identity: ClientIdentity,
        deviceIds: DeviceIdProvider,
        log: AdapterLog,
        tuning: AdapterTuning,
    ) : this(HttpClientSource { baseClient }, identity, deviceIds, log, tuning, PinRegistry.NONE)

    private val probe = ServerProbe(source, tuning.timeouts, tuning.testInterceptors)
    private val schemes = SchemeResolver(probe)
    internal val clients =
        ClientFactory(source, AuthHeaderBuilder(identity), tuning.timeouts, tuning.testInterceptors)
    private val transport = Transport(clients, deviceIds)
    internal val calls = AuthorizedCall(transport, probe, log)
    private val authenticator = Authenticator(probe, transport, calls, log)
    private val media = MediaCalls(calls, deviceIds)
    private val quickConnectFlow = QuickConnectFlow(authenticator, transport, tuning.quickConnect, log)
    private val adapterLog = log
    private val revokeTimeout = tuning.revokeTimeout

    /**
     * The client for calls that carry credentials but aren't API calls (images, subtitles, Media3): the one the API
     * uses, with its pinned trust, no redirect and the `Authorization` header added from the request's tag. It is
     * replaced whenever a pin changes, so ask again for every use and never keep it. A long-lived consumer (Media3,
     * Coil) must not call this once and keep the result: it takes [credentialedCallFactory] instead.
     */
    fun credentialedHttpClient(): OkHttpClient = clients.credentialed

    /**
     * The only handle a long-lived consumer (Media3's data source, Coil) may keep: it makes each call on the
     * [credentialedHttpClient] in force at that moment, so a pin change or clear reaches calls made later even though
     * the consumer was built before it.
     */
    val credentialedCallFactory: Call.Factory = Call.Factory { request -> clients.credentialed.newCall(request) }

    override suspend fun discover(input: String): ServerResult<ResolvedServer> =
        when (val parsed = AddressParser.parse(input)) {
            is ServerResult.Success -> schemes.discover(parsed.value)
            is ServerResult.Failure -> parsed
        }

    override suspend fun trust(
        input: String,
        pin: SpkiPin,
    ): ServerResult<ResolvedServer> =
        when (val parsed = AddressParser.parse(input)) {
            is ServerResult.Success -> trustAt(parsed.value.explicit ?: parsed.value.at(Scheme.Https), pin)
            is ServerResult.Failure -> parsed
        }

    private suspend fun trustAt(
        address: ServerAddress,
        pin: SpkiPin,
    ): ServerResult<ResolvedServer> {
        // A pin is never made over cleartext, and nothing is probed for an address that can't hold one.
        if (address.scheme != Scheme.Https) return ServerResult.Failure(MediaServerError.InvalidAddress())
        val previous = pins.pinFor(address)
        pins.pin(address, pin)
        var kept = false
        try {
            val result = probe.probe(address)
            kept = result is ServerResult.Success
            return result
        } finally {
            // A failure, a different certificate than the one confirmed, or a cancelled caller all put things back as
            // they were: the pin that was in force for this place, or none.
            if (!kept) previous?.let { pins.pin(address, it) } ?: pins.unpin(address)
        }
    }

    override suspend fun connect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): ServerResult<ResolvedServer> =
        CleartextGate.check(address, acknowledgement)?.let { ServerResult.Failure(it) } ?: probe.probe(address)

    override suspend fun signIn(request: SignInRequest): ServerResult<Session> =
        try {
            authenticator.signIn(request)
        } finally {
            request.password.fill('\u0000')
        }

    override suspend fun resume(stored: StoredSession): ServerResult<Session> = authenticator.resume(stored)

    override fun quickConnect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): Flow<QuickConnectState> = quickConnectFlow.run(address, acknowledgement)

    override suspend fun revoke(session: Session): ServerResult<Unit> {
        val result =
            withTimeoutOrNull(revokeTimeout) {
                calls.send(session, Endpoint.post("Sessions/Logout")).flatMap { ServerResult.Success(Unit) }
            } ?: ServerResult.Failure(MediaServerError.Timeout())
        // Whatever the server said, the user signed out: the token is not to be attached again.
        calls.forget(session)
        adapterLog.log(
            when (result) {
                is ServerResult.Success -> "revoke ok"
                is ServerResult.Failure -> "revoke failed: ${result.error.javaClass.logName()}"
            },
        )
        return result
    }

    override suspend fun libraries(session: Session): ServerResult<List<Library>> = media.libraries(session)

    override suspend fun libraryIdsForRemovalCheck(session: Session) = media.libraryIdsForRemovalCheck(session)

    override suspend fun items(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemPage> = media.items(session, library, startIndex, limit)

    override suspend fun libraryItems(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
        changedSince: Instant?,
    ): ServerResult<LibraryItemsPage> = media.libraryItems(session, library, startIndex, limit, changedSince)

    override suspend fun libraryItemIds(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage> = media.libraryItemIds(session, library, startIndex, limit)

    override suspend fun librarySeries(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
        changedSince: Instant?,
    ): ServerResult<SeriesPage> = media.librarySeries(session, library, startIndex, limit, changedSince)

    override suspend fun librarySeriesIds(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage> = media.librarySeriesIds(session, library, startIndex, limit)

    override suspend fun collections(
        session: Session,
        startIndex: Int,
        limit: Int,
        changedSince: Instant?,
    ): ServerResult<CollectionPage> = media.collections(session, startIndex, limit, changedSince)

    override suspend fun collectionIds(
        session: Session,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage> = media.collectionIds(session, startIndex, limit)

    override suspend fun collectionMembers(
        session: Session,
        collection: ItemId,
        startIndex: Int,
        limit: Int,
    ): ServerResult<CollectionMemberPage> = media.collectionMembers(session, collection, startIndex, limit)

    override suspend fun playbackInfo(
        session: Session,
        item: ItemId,
        request: PlaybackRequest,
    ): ServerResult<PlaybackInfo> = media.playbackInfo(session, item, request)

    override suspend fun stopEncoding(
        session: Session,
        playSession: PlaySessionId,
    ): ServerResult<Unit> = media.stopEncoding(session, playSession)

    override suspend fun reportStart(
        session: Session,
        report: PlaybackReport,
    ): ServerResult<Unit> = media.report(session, "Sessions/Playing", report, withPaused = true)

    override suspend fun reportProgress(
        session: Session,
        report: PlaybackReport,
    ): ServerResult<Unit> = media.report(session, "Sessions/Playing/Progress", report, withPaused = true)

    override suspend fun reportStopped(
        session: Session,
        report: PlaybackReport,
        failed: Boolean,
    ): ServerResult<Unit> {
        val path = "Sessions/Playing/Stopped"
        return media.report(session, path, report, withPaused = false, failed = failed)
    }

    override suspend fun userData(
        session: Session,
        itemId: ItemId,
    ): ServerResult<UserItemState> = media.userData(session, itemId)

    override suspend fun itemDetails(
        session: Session,
        itemId: ItemId,
    ): ServerResult<ItemDetails> = media.itemDetails(session, itemId)

    override suspend fun markPlayed(
        session: Session,
        itemId: ItemId,
    ): ServerResult<Unit> = media.markPlayed(session, itemId)
}

/** What a test may turn: timeouts, interceptors to watch or fail calls, and the Quick Connect and logout limits. */
internal data class AdapterTuning(
    val timeouts: ProbeTimeouts = ProbeTimeouts(),
    val testInterceptors: List<Interceptor> = emptyList(),
    val quickConnect: QuickConnectLimits = QuickConnectLimits(),
    val revokeTimeout: Duration = 5.seconds,
)
