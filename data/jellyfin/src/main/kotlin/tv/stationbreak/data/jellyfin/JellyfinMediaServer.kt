// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.OkHttpClient
import tv.stationbreak.core.domain.CleartextGate
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ServerAddress
import javax.inject.Inject

/**
 * The Jellyfin implementation of [MediaServer] (ADR-0005). It takes the app's base [OkHttpClient] and derives its own
 * variants from it, so one trust configuration covers everything. No SDK type appears in a signature of this class.
 */
class JellyfinMediaServer internal constructor(
    baseClient: OkHttpClient,
    timeouts: ProbeTimeouts,
) : MediaServer {
    @Inject
    constructor(baseClient: OkHttpClient) : this(baseClient, ProbeTimeouts())

    private val probe = ServerProbe(baseClient, timeouts)
    private val schemes = SchemeResolver(probe)

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
}
