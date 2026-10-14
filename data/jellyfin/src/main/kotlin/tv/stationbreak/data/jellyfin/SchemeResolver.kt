// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.model.Scheme

/**
 * Picks `https` or `http` for an address typed without a scheme (S0.3.1, Angua (Security) R5 and R6).
 *
 * `https` is tried first. `http` is tried next only if `https` failed in one of exactly three ways: the connection was
 * refused, the network was unreachable, or the server answered the TLS hello in plain text. Every other failure is
 * final: a certificate problem, a handshake failure, a reset, a timeout, a DNS failure. An explicit scheme is honoured
 * exactly and never probed the other way.
 */
internal class SchemeResolver(
    private val probe: ServerProbe,
) {
    suspend fun discover(candidate: AddressCandidate): ServerResult<ResolvedServer> {
        val explicit = candidate.explicit
        val first = probe.probe(explicit ?: candidate.at(Scheme.Https))
        val fallBack = explicit == null && first.errorOrNull()?.let(::allowsHttpFallback) == true
        return if (fallBack) {
            probe.probe(candidate.at(Scheme.Http).copy(viaFallback = true))
        } else {
            first
        }
    }

    private fun allowsHttpFallback(error: MediaServerError): Boolean =
        error is MediaServerError.ConnectionRefused ||
            error is MediaServerError.NetworkUnreachable ||
            error is MediaServerError.NotTls
}
