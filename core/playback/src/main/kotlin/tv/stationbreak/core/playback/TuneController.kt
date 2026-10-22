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

/** Why a tune failed. An expired session is not one of these: see [TuneResult.SessionExpired]. */
enum class TuneFailure { TIMEOUT, SERVER, PLAYER }

/**
 * What one tune did. The app branches on the case, never on a string. The times run from the start of the call to
 * the player's events, fallback included.
 */
sealed interface TuneResult {
    /**
     * Playing. [resolved] is the last route tried. [positionError] is how far from the requested start the last
     * attempt landed; it is set only when that attempt missed too (the retry after the fallback also missed).
     * [fallback] is true when the first attempt was abandoned for a forced transcode. [firstRoute] is the route the
     * resolver chose first, before any fallback (the same as the last route when there was none).
     */
    data class Played(
        val resolved: ResolvedPlayback,
        val firstFrameMs: Long?,
        val playingMs: Long,
        val positionError: Duration?,
        val fallback: Boolean,
        val firstRoute: Route = resolved.route,
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
 * Tunes an item: resolve, play, check where playback landed (within [seekTolerance], 2 s by default), and fall back
 * once to a forced transcode when it missed by more, or failed (ADR-0016, 4). The transcode starts at the requested
 * time and is judged with the same tolerance; if it misses too, [TuneResult.Played.positionError] says so. An expired
 * session (HTTP 401 or 403 on a media load) never falls back, and neither does a timeout. Every encode it starts and
 * abandons is stopped (ADR-0016, 5). When a tune ends with anything but [TuneResult.Played] the controller has already
 * stopped the player and the encode, so the caller need not call [stop]. Nothing here is logged. A [ResolvedPlayback]
 * it returns holds the stream URL, which is a secret: its `toString` hides it, so do not read it out for a log.
 */
class TuneController(
    private val resolver: PlaybackResolver,
    private val server: MediaServer,
    private val player: VideoPlayer,
    private val seekTolerance: Duration = SeekCheck.TOLERANCE,
    private val clock: () -> Long,
) {
    /** The attempt now on the player, so [stop] can end its encode. */
    @Volatile private var current: ResolvedPlayback? = null
    private val lock = Mutex()
    private val inFlight = AtomicReference<Deferred<TuneResult>?>(null)

    /** The tune [stop] cancelled, so it reports [TuneResult.Stopped] and not [TuneResult.Superseded]. */
    private val stopped = AtomicReference<Deferred<TuneResult>?>(null)

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
                if (stopped.get() === mine) TuneResult.Stopped else TuneResult.Superseded
            } finally {
                inFlight.compareAndSet(mine, null)
                stopped.compareAndSet(mine, null)
            }
        }

    private suspend fun run(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
    ): TuneResult {
        // An earlier tune's play that is still current (a flip after it returned Played): end its encode now. A
        // cancelled tune already released its own attempt, so nothing is stopped twice. The player is left running:
        // the new play replaces the item.
        withContext(NonCancellable) { abandonEncode(session) }
        val began = clock()
        var fallback = false
        var attempt = attempt(session, item, start, override, false, began)
        val firstRoute = attempt.resolved?.route
        while (!fallback && attempt.canFallBack) {
            fallback = true
            // Not cancellable: a stop or supersede here must not lose the first attempt's encode.
            withContext(NonCancellable) { abandonEncode(session) }
            val next = attempt(session, item, start, override, true, began)
            // A fallback that could not even resolve leaves the first attempt's route on record.
            attempt = if (next is Broke && next.resolved == null) next.copy(resolved = attempt.resolved) else next
        }
        // The caller is told nothing is playing, so nothing is left running (stop() is not needed after this).
        if (attempt is Broke) withContext(NonCancellable) { release(session) }
        return when (attempt) {
            is Landed -> {
                TuneResult.Played(
                    attempt.resolved,
                    attempt.firstFrameMs,
                    attempt.playingMs,
                    attempt.positionError,
                    fallback,
                    firstRoute ?: attempt.resolved.route,
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

    /**
     * Ends what is playing: cancels the tune in flight (which returns [TuneResult.Stopped] and never plays after
     * this), then, once it has finished unwinding, stops the player and the server's encode for the current play
     * session, if there is one. It takes the same lock as [tune], so it never overlaps a tune's own steps.
     */
    suspend fun stop(session: Session) {
        inFlight.get()?.let {
            stopped.set(it)
            it.cancel()
        }
        lock.withLock { withContext(NonCancellable) { release(session) } }
    }

    /** Stops the player and the encode of the current attempt, and forgets it. */
    private suspend fun release(session: Session) {
        player.stop()
        abandonEncode(session)
    }

    /** Stops the server's encode for the current attempt, if there is one, and forgets the attempt. */
    private suspend fun abandonEncode(session: Session) {
        val last = current
        current = null
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
                is ServerResult.Failure -> {
                    val error = answer.error
                    Resolution(null, Broke(null, TuneFailure.SERVER, error.stableName(), error.refusesSession()))
                }

                is ServerResult.Success -> {
                    Resolution(answer.value)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("SwallowedException") e: Exception,
        ) {
            Resolution(null, Broke(null, TuneFailure.SERVER, UNEXPECTED))
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
        // A stop or a newer tune during the resolve must not be followed by a play.
        currentCoroutineContext().ensureActive()
        current = resolved
        val result =
            try {
                playAndWait(resolved.source)
            } catch (e: CancellationException) {
                // Superseded, stopped or cancelled mid-wait: nothing may keep loading. A newer tune only starts
                // after this one has unwound, so its play is never disturbed by this clean-up.
                withContext(NonCancellable) { release(session) }
                throw e
            }
        return when (result) {
            Wait.TimedOut -> {
                // Nothing played; run() stops what is loading, on the player and on the server.
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
        val actual = landing.playing.position
        val verdict = SeekCheck.check(resolved.requested, actual, seekTolerance)
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

    private companion object {
        val PLAYING_TIMEOUT = 20.seconds
        val FIRST_FRAME_WAIT = 2.seconds
        const val TIMEOUT = "Timeout"
        const val UNEXPECTED = "Unexpected"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
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

/** The server no longer accepts this session: sign in again (ADR-0016, 4). */
private fun MediaServerError.refusesSession(): Boolean =
    this is MediaServerError.HttpUnauthorized ||
        this is MediaServerError.HttpForbidden ||
        this is MediaServerError.SessionExpired
