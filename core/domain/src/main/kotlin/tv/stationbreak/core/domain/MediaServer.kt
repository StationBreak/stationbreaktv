// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemIdPage
import tv.stationbreak.core.model.ItemPage
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryItemsPage
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.core.model.StoredSession
import kotlin.time.Instant

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
@Suppress("TooManyFunctions") // One port by decision (Granny's split of it is on hold).
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
     * Trusts exactly [pin] for the `https` address [input] names (`https` unless the input says otherwise, and the
     * typed or default port), then probes that address afresh, credential-free, on the client that now trusts it.
     * [pin] is the SPKI a failed handshake carried (`CertificateEvidence.spki`), never one fetched again. On success
     * the pin stays in force for every later request to that exact scheme, host and port, and the result's address is
     * the one to store with it. On any failure, including a different certificate than [pin]
     * (`TlsCertificateChanged`), or when the caller is cancelled, the pin is removed again. An `http` [input] or one
     * that isn't an address is `InvalidAddress`: a pin is never made over cleartext.
     */
    suspend fun trust(
        input: String,
        pin: SpkiPin,
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

    /**
     * The video-capable libraries the user can see (`GET /UserViews`): movies, TV shows, mixed content, home videos and
     * music videos. Playlists, collections, music and anything else are left out.
     */
    suspend fun libraries(session: Session): ServerResult<List<Library>>

    /**
     * One page of [library]'s direct children, from [startIndex] (at least 0) with at most [limit] items (1 to
     * [MAX_PAGE_SIZE]). The total count is the whole library's, so a caller can page. Throws
     * [IllegalArgumentException] for a bad [startIndex] or [limit]: that is a bug in the caller, not a server problem.
     */
    suspend fun items(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemPage>

    /**
     * One page of every video item under [library], at any depth (movies, episodes, videos and music videos; not
     * series, seasons or folders), for the library cache. The items carry the fields the cache needs and come in a
     * stable, total order (by name, ties broken by the server on ID). For a library that doesn't change, paging by
     * [startIndex] therefore neither skips nor repeats an item. A library that changes mid-walk can shift offsets: that
     * is the sync's job, not this call's (a changed-since pass from the walk's start minus a margin, ID
     * reconciliation, and upserts that are idempotent). [limit] is 1 to [MAX_PAGE_SIZE]. The total count is asked for,
     * and returned, on the first page ([startIndex] 0) only; a first page the server answers without one fails as a
     * [MediaServerError.ServerError].
     *
     * With [changedSince] the call returns only items the server saved at or after that UTC moment
     * (`MinDateLastSaved`). Throws [IllegalArgumentException] for a bad [startIndex] or [limit]: that is a bug in the
     * caller, not a server problem.
     */
    suspend fun libraryItems(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
        changedSince: Instant? = null,
    ): ServerResult<LibraryItemsPage>

    /**
     * One page of the IDs of the same items [libraryItems] returns, with nothing else, for finding what the server no
     * longer has. Paging and the total count work as for [libraryItems], and the argument rules are the same.
     */
    suspend fun libraryItemIds(
        session: Session,
        library: Library,
        startIndex: Int,
        limit: Int,
    ): ServerResult<ItemIdPage>

    /**
     * Asks how [item] can be played, with a conservative static device profile (a placeholder until F1.6's capability
     * probe). The result has the media sources, the play session to report under, and a URL builder. No URL, path or
     * key the server supplied is passed on (R2).
     */
    suspend fun playbackInfo(
        session: Session,
        item: ItemId,
    ): ServerResult<PlaybackInfo>

    /** Tells the server playback of [report]'s item began (`POST /Sessions/Playing`). */
    suspend fun reportStart(
        session: Session,
        report: PlaybackReport,
    ): ServerResult<Unit>

    /** Tells the server how far playback has got (`POST /Sessions/Playing/Progress`). */
    suspend fun reportProgress(
        session: Session,
        report: PlaybackReport,
    ): ServerResult<Unit>

    /**
     * Tells the server playback ended at [report]'s position (`POST /Sessions/Playing/Stopped`), which is what stores a
     * resume point.
     */
    suspend fun reportStopped(
        session: Session,
        report: PlaybackReport,
    ): ServerResult<Unit>

    companion object {
        /** The most items one [items] call may ask for, so an answer stays well inside the adapter's size limit. */
        const val MAX_PAGE_SIZE = 200
    }
}
