// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ServerAddress

/** A scripted [MediaServer] for tests of code that uses the port (ADR-0004). Not thread-safe. */
class FakeMediaServer : MediaServer {
    var discoverResult: ServerResult<ResolvedServer> = ServerResult.Failure(MediaServerError.Unknown())
    var connectResult: ServerResult<ResolvedServer> = ServerResult.Failure(MediaServerError.Unknown())

    val discoverCalls = mutableListOf<String>()
    val connectCalls = mutableListOf<Pair<ServerAddress, CleartextAcknowledgement?>>()

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
}
