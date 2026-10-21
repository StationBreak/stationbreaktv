// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.Session
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What one tune did. [resolved] is the last route tried, null when nothing could be resolved. The times run from the
 * start of the call to the player's events, fallback included. [positionError] is how far from the requested start
 * the last attempt landed, set only when it missed. [error] is a class name (or "Timeout", or "Unauthorized" for an
 * expired session), never a message: messages may hold a URL or a token.
 */
data class TuneOutcome(
    val resolved: ResolvedPlayback?,
    val firstFrameMs: Long?,
    val playingMs: Long?,
    val positionError: Duration?,
    val fallback: Boolean,
    val error: String?,
)

/**
 * Tunes an item: resolve, play, check where playback landed, and fall back once to a forced transcode when it missed
 * or failed (ADR-0016, 4). An expired session (HTTP 401 or 403 on a media load) never falls back. Every encode it
 * starts and abandons is stopped (ADR-0016, 5). Nothing here is logged, and nothing it returns holds a URL.
 */
class TuneController(
    private val resolver: PlaybackResolver,
    private val server: MediaServer,
    private val player: VideoPlayer,
    private val clock: () -> Long,
) {
    /** The attempt now on the player, so [stop] can end its encode. */
    @Volatile private var current: ResolvedPlayback? = null

    suspend fun tune(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
    ): TuneOutcome {
        val began = clock()
        var fallback = false
        var attempt = attempt(session, item, start, override, false, began)
        while (!fallback && attempt.wantsFallback()) {
            fallback = true
            attempt.resolved?.let { stopEncode(session, it) }
            attempt = attempt(session, item, start, override, true, began)
        }
        return TuneOutcome(
            resolved = attempt.resolved,
            firstFrameMs = attempt.firstFrameMs,
            playingMs = attempt.playingMs,
            positionError = attempt.positionError,
            fallback = fallback,
            error = attempt.error,
        )
    }

    /** Stops the player, then the server's encode for the current play session, if there is one. */
    suspend fun stop(session: Session) {
        val last = current
        current = null
        player.stop()
        if (last != null) stopEncode(session, last)
    }

    private class Attempt(
        val resolved: ResolvedPlayback?,
        val firstFrameMs: Long? = null,
        val playingMs: Long? = null,
        val positionError: Duration? = null,
        val error: String? = null,
        val canFallBack: Boolean = false,
    ) {
        fun wantsFallback() = canFallBack
    }

    private class Resolution(
        val resolved: ResolvedPlayback?,
        val error: String? = null,
    )

    @Suppress("LongParameterList", "TooGenericExceptionCaught") // One call's context; failures become class names.
    private suspend fun resolve(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
        forceTranscode: Boolean,
    ): Resolution =
        try {
            when (val answer = resolver.resolve(session, item, start, override, forceTranscode)) {
                is ServerResult.Failure -> Resolution(null, answer.error.className())
                is ServerResult.Success -> Resolution(answer.value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Resolution(null, e.javaClass.simpleName)
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
        val resolved = resolution.resolved ?: return Attempt(null, error = resolution.error)
        current = resolved
        val result = playAndWait(resolved.source)
        return when (result) {
            Wait.TimedOut -> Attempt(resolved, error = TIMEOUT)
            is Wait.Failed -> failed(resolved, result.event, forceTranscode)
            is Wait.Landed -> landed(resolved, result, began, forceTranscode)
        }
    }

    private fun failed(
        resolved: ResolvedPlayback,
        event: PlayerEvent.Failed,
        forced: Boolean,
    ): Attempt {
        val expired = event.httpStatus == HTTP_UNAUTHORIZED || event.httpStatus == HTTP_FORBIDDEN
        val error = if (expired) UNAUTHORIZED else event.errorClass
        // A transcode is already the last resort: asking for one again would play the same thing.
        val canFallBack = !expired && !forced && resolved.route != Route.TRANSCODE
        return Attempt(resolved, error = error, canFallBack = canFallBack)
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
        return Attempt(
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

    /** Starts [source] and waits for it to play or fail. [Wait.TimedOut] when neither happens in [PLAYING_TIMEOUT]. */
    private suspend fun playAndWait(source: PlayerSource): Wait =
        coroutineScope {
            var firstFrameAt: Long? = null
            // Listening starts before play(), so no event of this attempt is missed.
            val waiter =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(PLAYING_TIMEOUT) {
                        player.events.first { event ->
                            if (event is PlayerEvent.FirstFrame) firstFrameAt = event.atMs
                            event is PlayerEvent.Playing || event is PlayerEvent.Failed
                        }
                    }
                }
            player.play(source)
            when (val event = waiter.await()) {
                is PlayerEvent.Playing -> Wait.Landed(firstFrameAt, event)
                is PlayerEvent.Failed -> Wait.Failed(event)
                else -> Wait.TimedOut
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

    private fun MediaServerError.className(): String =
        when (this) {
            is MediaServerError.HttpUnauthorized,
            is MediaServerError.HttpForbidden,
            is MediaServerError.SessionExpired,
            -> UNAUTHORIZED

            else -> javaClass.simpleName
        }

    private companion object {
        val PLAYING_TIMEOUT = 20.seconds
        const val TIMEOUT = "Timeout"
        const val UNAUTHORIZED = "Unauthorized"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
