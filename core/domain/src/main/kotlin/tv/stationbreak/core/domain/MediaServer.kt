// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.StoredSession

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
 *
 * Constraint for requests that are not API calls (images, streams; who supplies their `Authorization` is open until
 * F0.5/F1.x): the header goes on the shared client, never into a URL or query string, because URLs leak through proxy
 * logs, history and diagnostics.
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

    /**
     * Signs in with a password (R19, R20). The server is probed first (credential-free), and the credentials go only to
     * the address that probe ended at, so a session's address may differ from the request's by an adopted
     * `http` to `https` redirect. Cleartext that needs confirmation and has none returns `CleartextNotConfirmed`
     * with nothing sent. A bad password is `HttpUnauthorized`, a locked user `HttpForbidden`, a redirect on the
     * credentialed call `Redirected`. The password is zero-filled when the call ends, whatever the outcome.
     */
    suspend fun signIn(request: SignInRequest): ServerResult<Session>

    /**
     * Turns a [stored] session back into a [Session] if the server it came from still accepts it: probes the stored
     * address first, and a different server ID returns `ServerMismatch` before the token is sent anywhere (R1). A
     * revoked token is `SessionExpired`. This is the only way to use a token the adapter didn't issue in this run.
     */
    suspend fun resume(stored: StoredSession): ServerResult<Session>

    /**
     * A Quick Connect sign-in as a flow of [QuickConnectState]. Collecting starts it; cancelling the collector stops
     * it and sends nothing more. Ends in `SignedIn`, `Expired`, `Denied` or `Failed` (`QuickConnectDisabled` means
     * offer a password instead).
     */
    fun quickConnect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement? = null,
    ): Flow<QuickConnectState>

    /**
     * Asks the server to forget [session]'s token (`POST /Sessions/Logout`) with a short timeout. A failure here means
     * the token is orphaned on the server until this device signs in again; it never means the TV still holds it.
     * Callers clear the stored session first ([SessionRepository.signOut]).
     */
    suspend fun revoke(session: Session): ServerResult<Unit>
}
