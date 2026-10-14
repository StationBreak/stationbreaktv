// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
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
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemPage
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.StoredSession
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Jellyfin implementation of [MediaServer] (ADR-0005). It takes the app's base [OkHttpClient] and derives its own
 * variants from it, so one trust configuration covers everything. No SDK type appears in a signature of this class.
 *
 * The probe is credential-free and follows the redirects [RedirectPolicy] allows. Every other call is on a client that
 * follows none, carries exactly one `Authorization` header, and puts no credential in a URL (the one exception is
 * Quick Connect's single-use secret, see [QuickConnectRequests]).
 */
@Suppress("TooManyFunctions") // The port's methods: one interface by decision (Granny's split is on hold).
class JellyfinMediaServer internal constructor(
    baseClient: OkHttpClient,
    identity: ClientIdentity,
    deviceIds: DeviceIdProvider,
    log: AdapterLog,
    tuning: AdapterTuning,
) : MediaServer {
    @Inject
    constructor(
        @BaseHttpClient baseClient: OkHttpClient,
        identity: ClientIdentity,
        deviceIds: DeviceIdProvider,
        log: AdapterLog,
    ) : this(baseClient, identity, deviceIds, log, AdapterTuning())

    private val probe = ServerProbe(baseClient, tuning.timeouts, tuning.testInterceptors)
    private val schemes = SchemeResolver(probe)
    private val clients =
        ClientFactory(baseClient, AuthHeaderBuilder(identity), tuning.timeouts, tuning.testInterceptors)
    private val transport = Transport(clients, deviceIds)
    private val calls = AuthorizedCall(transport, probe, log)
    private val authenticator = Authenticator(probe, transport, calls, log)
    private val media = MediaCalls(calls)
    private val quickConnectFlow = QuickConnectFlow(authenticator, transport, tuning.quickConnect, log)
    private val adapterLog = log
    private val revokeTimeout = tuning.revokeTimeout

    override suspend fun discover(input: String): ServerResult<ResolvedServer> =
        when (val parsed = AddressParser.parse(input)) {
            is ServerResult.Success -> schemes.discover(parsed.value)
            is ServerResult.Failure -> parsed
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
        adapterLog.log(
            when (result) {
                is ServerResult.Success -> "revoke ok"
                is ServerResult.Failure -> "revoke failed: ${result.error::class.simpleName}"
            },
        )
        return result
    }

    override suspend fun libraries(session: Session): ServerResult<List<Library>> = media.libraries(session)

    override suspend fun items(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemPage> = media.items(session, library, startIndex, limit)

    override suspend fun playbackInfo(
        session: Session,
        item: ItemId,
    ): ServerResult<PlaybackInfo> = media.playbackInfo(session, item)

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
    ): ServerResult<Unit> = media.report(session, "Sessions/Playing/Stopped", report, withPaused = false)
}

/** What a test may turn: timeouts, interceptors to watch or fail calls, and the Quick Connect and logout limits. */
internal data class AdapterTuning(
    val timeouts: ProbeTimeouts = ProbeTimeouts(),
    val testInterceptors: List<Interceptor> = emptyList(),
    val quickConnect: QuickConnectLimits = QuickConnectLimits(),
    val revokeTimeout: Duration = 5.seconds,
)
