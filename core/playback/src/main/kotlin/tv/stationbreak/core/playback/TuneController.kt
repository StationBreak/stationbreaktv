// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.Session
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Why a tune failed, for the app to branch on. An expired session is not a failure: see [TuneResult.SessionExpired]. */
enum class TuneFailure { TIMEOUT, SERVER, PLAYER }

/**
 * What one tune did. The app branches on the case, never on a string. The times run from the start of the call to
 * the player's events, fallback included.
 */
sealed interface TuneResult {
    /**
     * Playing. [resolved] is the last route tried. [positionError] is how far from the requested start the last
     * attempt landed; it is set only when that attempt missed too (the retry after the fallback also missed).
     * [fallback] is true when the first attempt was abandoned for a forced transcode.
     */
    data class Played(
        val resolved: ResolvedPlayback,
        val firstFrameMs: Long?,
        val playingMs: Long,
        val positionError: Duration?,
        val fallback: Boolean,
    ) : TuneResult

    /** A newer tune replaced this one before it finished. */
    data object Superseded : TuneResult

    /** [TuneController.stop] ended this tune before it finished. */
    data object Stopped : TuneResult

    /** The server refused the session (HTTP 401 or 403, or a session error): sign in again. No fallback is tried. */
    data class SessionExpired(
        val resolved: ResolvedPlayback?,
    ) : TuneResult

    /**
     * Nothing is playing. [resolved] is the last route tried, null when nothing could be resolved. [diagnostic] is a
     * stable name (a Media3 error code name, a server error name, or "Unexpected"), never a message, class name or
     * URL: those may hold a token. It is for logs and the playback matrix, not for the app to branch on.
     */
    data class Failed(
        val reason: TuneFailure,
        val diagnostic: String?,
        val resolved: ResolvedPlayback?,
        val fallback: Boolean,
    ) : TuneResult
}

/**
 * Tunes an item: resolve, play, check where playback landed, and fall back once to a forced transcode when it missed
 * or failed (ADR-0016, 4). An expired session (HTTP 401 or 403 on a media load) never falls back. Every encode it
 * starts and abandons is stopped (ADR-0016, 5). Nothing here is logged. A [ResolvedPlayback] it returns holds the
 * stream URL, which is a secret: its `toString` hides it, so do not read it out for a log or a report.
 */
class TuneController(
    private val resolver: PlaybackResolver,
    private val server: MediaServer,
    private val player: VideoPlayer,
    private val clock: () -> Long,
) {
    /** The attempt now on the player, so [stop] can end its encode. */
    @Volatile private var current: ResolvedPlayback? = null
    private val lock = Mutex()
    private val inFlight = AtomicReference<Deferred<TuneResult>?>(null)

    /**
     * Tunes [item]. Tunes are serialized: a new call cancels the one in flight (which returns [TuneResult.Superseded])
     * and starts only when that one has finished, so two never share the player.
     */
    suspend fun tune(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
    ): TuneResult =
        coroutineScope {
            val mine = async(start = CoroutineStart.LAZY) { lock.withLock { run(session, item, start, override) } }
            inFlight.getAndSet(mine)?.cancel()
            try {
                mine.await()
            } catch (
                @Suppress("SwallowedException") e: CancellationException,
            ) {
                // Cancelled by a newer tune: report it. Cancelled from outside: ensureActive rethrows.
                currentCoroutineContext().ensureActive()
                TuneResult.Superseded
            } finally {
                inFlight.compareAndSet(mine, null)
            }
        }

    private suspend fun run(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
    ): TuneResult {
        val began = clock()
        var fallback = false
        var attempt = attempt(session, item, start, override, false, began)
        while (!fallback && attempt.canFallBack) {
            fallback = true
            attempt.resolved?.let { stopEncode(session, it) }
            val next = attempt(session, item, start, override, true, began)
            // A fallback that could not even resolve leaves the first attempt's route on record.
            attempt = if (next is Broke && next.resolved == null) next.copy(resolved = attempt.resolved) else next
        }
        return when (attempt) {
            is Landed -> {
                TuneResult.Played(
                    attempt.resolved,
                    attempt.firstFrameMs,
                    attempt.playingMs,
                    attempt.positionError,
                    fallback,
                )
            }

            is Broke -> {
                if (attempt.expired) {
                    TuneResult.SessionExpired(attempt.resolved)
                } else {
                    TuneResult.Failed(attempt.reason, attempt.diagnostic, attempt.resolved, fallback)
                }
            }
        }
    }

    /** Stops the player, then the server's encode for the current play session, if there is one. */
    suspend fun stop(session: Session) {
        val last = current
        current = null
        player.stop()
        if (last != null) stopEncode(session, last)
    }

    private sealed interface Attempt {
        val resolved: ResolvedPlayback?
        val canFallBack: Boolean
    }

    /** The player reached Playing. [positionError] is set when it landed off the requested start. */
    private class Landed(
        override val resolved: ResolvedPlayback,
        val firstFrameMs: Long?,
        val playingMs: Long,
        val positionError: Duration?,
        override val canFallBack: Boolean,
    ) : Attempt

    /** Nothing played. [expired] means the server refused the session. */
    private data class Broke(
        override val resolved: ResolvedPlayback?,
        val reason: TuneFailure,
        val diagnostic: String?,
        val expired: Boolean = false,
        override val canFallBack: Boolean = false,
    ) : Attempt

    private class Resolution(
        val resolved: ResolvedPlayback?,
        val broke: Broke? = null,
    )

    @Suppress("TooGenericExceptionCaught") // Any failure of the resolver becomes a stable name, never a message.
    private suspend fun resolve(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
        forceTranscode: Boolean,
    ): Resolution =
        try {
            when (val answer = resolver.resolve(session, item, start, override, forceTranscode)) {
                is ServerResult.Failure -> Resolution(null, serverBroke(answer.error))
                is ServerResult.Success -> Resolution(answer.value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("SwallowedException") e: Exception,
        ) {
            Resolution(null, Broke(null, TuneFailure.SERVER, UNEXPECTED))
        }

    private fun serverBroke(error: MediaServerError): Broke {
        val expired =
            error is MediaServerError.HttpUnauthorized ||
                error is MediaServerError.HttpForbidden ||
                error is MediaServerError.SessionExpired
        return Broke(null, TuneFailure.SERVER, error.stableName(), expired)
    }

    @Suppress("LongParameterList") // One call's context.
    private suspend fun attempt(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
        forceTranscode: Boolean,
        began: Long,
    ): Attempt {
        val resolution = resolve(session, item, start, override, forceTranscode)
        val resolved = resolution.resolved ?: return checkNotNull(resolution.broke)
        current = resolved
        val result =
            try {
                playAndWait(resolved.source)
            } catch (e: CancellationException) {
                // Superseded or cancelled mid-wait: nothing may keep loading.
                withContext(NonCancellable) { stopEncode(session, resolved) }
                throw e
            }
        return when (result) {
            Wait.TimedOut -> {
                // Nothing played: stop what is loading, on the player and on the server.
                current = null
                player.stop()
                stopEncode(session, resolved)
                Broke(resolved, TuneFailure.TIMEOUT, TIMEOUT)
            }

            is Wait.Failed -> {
                failed(resolved, result.event, forceTranscode)
            }

            is Wait.Landed -> {
                landed(resolved, result, began, forceTranscode)
            }
        }
    }

    private fun failed(
        resolved: ResolvedPlayback,
        event: PlayerEvent.Failed,
        forced: Boolean,
    ): Attempt {
        val expired = event.httpStatus == HTTP_UNAUTHORIZED || event.httpStatus == HTTP_FORBIDDEN
        val diagnostic = event.httpStatus?.let { "${event.errorCode} (HTTP $it)" } ?: event.errorCode
        // A transcode is already the last resort: asking for one again would play the same thing.
        val canFallBack = !expired && !forced && resolved.route != Route.TRANSCODE
        return Broke(resolved, TuneFailure.PLAYER, diagnostic, expired, canFallBack)
    }

    private fun landed(
        resolved: ResolvedPlayback,
        landing: Wait.Landed,
        began: Long,
        forced: Boolean,
    ): Attempt {
        val actual = resolved.source.serverOffset + landing.playing.position
        val verdict = SeekCheck.check(resolved.requested, actual)
        val missed = verdict as? SeekVerdict.Missed
        return Landed(
            resolved,
            firstFrameMs = landing.firstFrameAtMs?.minus(began),
            playingMs = landing.playing.atMs - began,
            positionError = missed?.error,
            canFallBack = missed != null && !forced,
        )
    }

    private sealed interface Wait {
        data object TimedOut : Wait

        class Failed(
            val event: PlayerEvent.Failed,
        ) : Wait

        class Landed(
            val firstFrameAtMs: Long?,
            val playing: PlayerEvent.Playing,
        ) : Wait
    }

    /**
     * Starts [source] and waits for its play to start or fail. Only events of this play's own generation count, so
     * a late event of an earlier play cannot answer for it. [Wait.TimedOut] when neither happens in
     * [PLAYING_TIMEOUT]. Once playing, it waits up to [FIRST_FRAME_WAIT] more for the first frame.
     */
    private suspend fun playAndWait(source: PlayerSource): Wait =
        coroutineScope {
            val generation = CompletableDeferred<Long>()
            val outcome = CompletableDeferred<PlayerEvent>()
            val firstFrame = CompletableDeferred<Long>()
            // Listening starts before play(), so no event of this play is missed; events wait for its generation.
            val listener =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    player.events.collect { event ->
                        if (event.generation != generation.await()) return@collect
                        when (event) {
                            is PlayerEvent.FirstFrame -> firstFrame.complete(event.atMs)
                            is PlayerEvent.Playing, is PlayerEvent.Failed -> outcome.complete(event)
                            else -> Unit
                        }
                    }
                }
            try {
                generation.complete(player.play(source))
                when (val event = withTimeoutOrNull(PLAYING_TIMEOUT) { outcome.await() }) {
                    is PlayerEvent.Playing -> {
                        Wait.Landed(withTimeoutOrNull(FIRST_FRAME_WAIT) { firstFrame.await() }, event)
                    }

                    is PlayerEvent.Failed -> {
                        Wait.Failed(event)
                    }

                    else -> {
                        Wait.TimedOut
                    }
                }
            } finally {
                listener.cancel()
            }
        }

    private suspend fun stopEncode(
        session: Session,
        resolved: ResolvedPlayback,
    ) {
        val playSession: PlaySessionId = resolved.playSession ?: return
        // Direct play started no encode on the server; direct stream and transcode did.
        if (resolved.route == Route.DIRECT_PLAY) return
        server.stopEncoding(session, playSession)
    }

    /** A name per error, written out so a rename of a class cannot change what a log or a matrix row says. */
    @Suppress("CyclomaticComplexMethod") // One branch per error: the closed set is the point.
    private fun MediaServerError.stableName(): String =
        when (this) {
            is MediaServerError.DnsFailure -> "DnsFailure"
            is MediaServerError.ConnectionRefused -> "ConnectionRefused"
            is MediaServerError.NetworkUnreachable -> "NetworkUnreachable"
            is MediaServerError.Timeout -> "Timeout"
            is MediaServerError.TlsUntrusted -> "TlsUntrusted"
            is MediaServerError.TlsHostnameMismatch -> "TlsHostnameMismatch"
            is MediaServerError.TlsCertificateExpired -> "TlsCertificateExpired"
            is MediaServerError.TlsCertificateNotYetValid -> "TlsCertificateNotYetValid"
            is MediaServerError.TlsCertificateChanged -> "TlsCertificateChanged"
            is MediaServerError.TlsHandshakeFailed -> "TlsHandshakeFailed"
            is MediaServerError.NotTls -> "NotTls"
            is MediaServerError.HttpUnauthorized -> "HttpUnauthorized"
            is MediaServerError.HttpForbidden -> "HttpForbidden"
            is MediaServerError.NotJellyfin -> "NotJellyfin"
            is MediaServerError.UnsupportedVersion -> "UnsupportedVersion"
            is MediaServerError.ServerNotSetUp -> "ServerNotSetUp"
            is MediaServerError.Redirected -> "Redirected"
            is MediaServerError.CleartextNotConfirmed -> "CleartextNotConfirmed"
            is MediaServerError.SessionExpired -> "SessionExpired"
            is MediaServerError.ServerMismatch -> "ServerMismatch"
            is MediaServerError.QuickConnectDisabled -> "QuickConnectDisabled"
            is MediaServerError.ServerError -> "ServerError"
            is MediaServerError.InvalidAddress -> "InvalidAddress"
            is MediaServerError.NotFound -> "NotFound"
            is MediaServerError.Unknown -> "Unknown"
        }

    private companion object {
        val PLAYING_TIMEOUT = 20.seconds
        val FIRST_FRAME_WAIT = 2.seconds
        const val TIMEOUT = "Timeout"
        const val UNEXPECTED = "Unexpected"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
