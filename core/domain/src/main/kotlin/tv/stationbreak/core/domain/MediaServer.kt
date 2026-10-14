// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerInfo

/**
 * A server found at [address], and what it said about itself. [resolvedByFallback] is true when the address was
 * chosen by falling back from `https` to `http` rather than typed that way, so the UI can show it differently. It is
 * the address's own flag, so it travels with the address wherever that is stored.
 */
data class ResolvedServer(
    val address: ServerAddress,
    val server: ServerInfo,
) {
    val resolvedByFallback: Boolean get() = address.viaFallback
}

/**
 * The port to a media server (ADR-0005). Only domain types cross it: no SDK or OkHttp type, no `Throwable` and no
 * URL in an error. Methods return a [ServerResult] and don't throw for server problems; cancellation is the one
 * exception and propagates as usual.
 */
interface MediaServer {
    /**
     * Turns whatever the user typed into a verified Jellyfin 12 server, or says why not. With no scheme in [input] it
     * tries `https` first and falls back to `http` only when the server refused, was unreachable, or answered a TLS
     * hello in plain text; an explicit scheme is honoured exactly. Setup only: afterwards use [connect]. The probe
     * carries no credentials of any kind.
     */
    suspend fun discover(input: String): ServerResult<ResolvedServer>

    /**
     * Probes [address] on its own scheme and never downgrades it. The result's address differs from [address] only
     * when the server redirected `http` to `https` on the same host, port and subpath. Cleartext to an address that
     * needs confirmation ([ServerAddress.cleartextNeedsConfirmation]) needs [acknowledgement] built from exactly
     * that address, otherwise this returns [MediaServerError.CleartextNotConfirmed] without sending anything.
     */
    suspend fun connect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement? = null,
    ): ServerResult<ResolvedServer>
}
