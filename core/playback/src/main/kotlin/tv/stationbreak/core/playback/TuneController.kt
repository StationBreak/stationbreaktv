// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Why a tune failed. An expired session is not one of these: see [TuneResult.SessionExpired]. */
enum class TuneFailure { TIMEOUT, SERVER, PLAYER }

/** Why the first attempt was abandoned for a forced transcode. */
sealed interface FallbackCause {
    /** The first attempt played but landed too far from the requested start (ADR-0016, 4). */
    data object SeekMiss : FallbackCause

    /** The first attempt did not load or play. [diagnostic] is a stable name, as in [TuneResult.Failed.diagnostic]. */
    data class LoadFailed(
        val reason: TuneFailure,
        val diagnostic: String?,
    ) : FallbackCause
}

/** The first attempt, on route [from], was abandoned for [cause]; what followed was always a forced transcode. */
data class Fallback(
    val from: Route,
    val cause: FallbackCause,
)

/** How a play that had started came to an end. */
sealed interface PlaybackEnd {
    /** The media played to its end. */
    data object Ended : PlaybackEnd

    /** The play broke after it started, or stalled longer than the playing timeout. [errorCode] is a stable name. */
    data class Failed(
        val errorCode: String,
        val httpStatus: Int = -1,
        val afterMs: Long = -1,
        val positionMs: Long = -1,
        val bufferedMs: Long = -1,
        val stalls: Int = 0,
    ) : PlaybackEnd
}

/**
 * A stall in a play that had reached playing, and its end: numbers only (diagnostics, #392). Times are milliseconds;
 * -1 where the player could not say.
 */
sealed interface PlayNote {
    /** Playback stopped to buffer at [positionMs] with [bufferedMs] buffered, [afterMs] after the play began. */
    data class Stall(
        val positionMs: Long,
        val bufferedMs: Long,
        val afterMs: Long,
    ) : PlayNote

    /** Playing resumed after a [Stall] that lasted [ms]. */
    data class Resumed(
        val ms: Long,
    ) : PlayNote
}

/**
 * An item resolved ahead of time by [TuneController.preResolve], to be played by [TuneController.tune] without a
 * server round trip. Opaque: it holds the resolved stream, whose URL is a secret. The caller owns it until it is
 * played, discarded, or the controller is closed. What a held handle costs the server, and how long to keep one, is
 * ADR-0016 (pre-resolve); it goes stale when the item's block ends.
 */
class Prepared internal constructor(
    internal val item: ItemId,
    internal val start: Duration,
    internal val resolved: ResolvedPlayback,
) {
    /** Set once nothing else need end any encode this handle's play started: a tune adopted it, or stopped it. */
    @Volatile internal var adopted: Boolean = false

    override fun toString(): String = "Prepared"
}

/**
 * What one tune did. The app branches on the case, never on a string. The times run from the start of the call to
 * the player's events, fallback included.
 */
sealed interface TuneResult {
    /**
     * Playing. [resolved] is the last route tried. [positionError] is how far from the requested start the last
     * attempt landed; it is set only when that attempt missed too (the retry after the fallback also missed).
     * [fallback] is null when the first attempt played; otherwise it says which route was abandoned and why.
     * [end] is how this play ends: it emits the first [PlaybackEnd] of this play and completes. The controller watches
     * the play from before it starts, so an end that comes before anyone collects is still delivered to a late
     * collector. It completes without a value from the moment a newer tune (either overload) takes the tune lock, or
     * [TuneController.stop] or [TuneController.close] ends the play: the old picture may keep playing until the new one
     * is ready, but its end is no longer reported. It defaults to an empty flow, for the other constructors of this
     * result. [tracksMs] is the time from the play call to Media3's first tracks report for that play (the container
     * parsed), and [skippedFrames] the video frames the decoder dropped before the first picture, to reach an exact
     * seek position; both are null when the player could not say (diagnostics only). [loads] is what the media loads
     * before the tracks cost (count, time, bytes, container), also diagnostics only. [notes] are the play's stalls and
     * their ends (at most [MAX_STALL_NOTES] stalls), and complete when [end] does; diagnostics only.
     */
    data class Played(
        val resolved: ResolvedPlayback,
        val firstFrameMs: Long?,
        val playingMs: Long,
        val positionError: Duration?,
        val fallback: Fallback?,
        val end: Flow<PlaybackEnd> = emptyFlow(),
        val tracksMs: Long? = null,
        val skippedFrames: Long? = null,
        val loads: LoadStats? = null,
        val notes: Flow<PlayNote> = emptyFlow(),
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
     * URL: those may hold a token. It is for logs and the playback matrix, not for the app to branch on. [fallback] is
     * null when no second attempt was made.
     */
    data class Failed(
        val reason: TuneFailure,
        val diagnostic: String?,
        val resolved: ResolvedPlayback?,
        val fallback: Fallback?,
    ) : TuneResult
}

/**
 * Tunes an item: resolve, play, check where playback landed (within [seekTolerance], 2 s by default), and fall back
 * once to a forced transcode when it missed by more, or failed (ADR-0016, 4). The transcode starts at the requested
 * time and is judged with the same tolerance; if it misses too, [TuneResult.Played.positionError] says so. An expired
 * session (HTTP 401 or 403 on a media load) never falls back, and neither does a timeout. Every encode it starts and
 * abandons is stopped (ADR-0016, 5): the server call is issued in the background, so a tune never waits for it, and
 * [close] awaits every one still running. When a tune ends with anything but [TuneResult.Played] the controller has
 * already stopped the player and begun ending the encode, so the caller need not call [stop]. Nothing here is logged.
 * A [ResolvedPlayback] it returns holds the stream URL, which is a secret: its `toString` hides it, so do not read it
 * out for a log.
 *
 * [watchScope] runs the watcher that sees how a play ends ([TuneResult.Played.end]), and the background encode stops
 * (which its cancel does not lose: they run on a separate scope the controller owns, never cancelled, which borrows
 * only the dispatcher of [watchScope], or Dispatchers.Default when the controller makes its own). The watcher is
 * cancelled when a newer tune takes the lock, and by [stop] and [close], so it never outlives them. Left null, the
 * controller makes its own scope and [close] cancels it; a scope passed in is the caller's, and [close] leaves it
 * alone. A [Prepared] handle that the caller drops without [tune], [discard] or [close] keeps
 * its server encode until the server times it out.
 */
@Suppress("TooManyFunctions") // One controller by decision: tune, stop, close and the attempt steps share its state.
class TuneController(
    private val resolver: PlaybackResolver,
    private val server: MediaServer,
    private val player: VideoPlayer,
    private val seekTolerance: Duration = SeekCheck.TOLERANCE,
    watchScope: CoroutineScope? = null,
    private val clock: () -> Long,
) {
    private val ownsScope = watchScope == null
    private val scope: CoroutineScope = watchScope ?: CoroutineScope(SupervisorJob())

    /** The attempt now on the player, so [stop] can end its encode. */
    @Volatile private var current: ResolvedPlayback? = null
    private val lock = Mutex()
    private val inFlight = AtomicReference<Deferred<TuneResult>?>(null)

    /** The tune [stop] cancelled, so it reports [TuneResult.Stopped] and not [TuneResult.Superseded]. */
    private val stopped = AtomicReference<Deferred<TuneResult>?>(null)

    /** Set by [close]: no tune runs after it. */
    private val closed = AtomicBoolean(false)

    /** The watcher of the current play's end; one at a time, cancelled by a newer tune, [stop] and [close]. */
    @Volatile private var watching: Job? = null

    /** The [Prepared] handles not yet played or discarded, so [close] can end their encodes. */
    private val outstanding: MutableSet<Prepared> = Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * Runs the encode stops, apart from the watch scope. Never cancelled: a stop ends by itself and [close] awaits its
     * children. It borrows only the dispatcher of the watch scope (so a test's virtual time covers it), or
     * Dispatchers.Default when the controller made its own scope.
     */
    private val stopScope =
        CoroutineScope(SupervisorJob() + (scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext))

    /**
     * Tunes [item]. Tunes are serialized: a new call cancels the one in flight (which returns [TuneResult.Superseded])
     * and starts only when that one has finished, so two never share the player.
     */
    suspend fun tune(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
    ): TuneResult = tuneWith(session, item, start, override, null)

    private suspend fun tuneWith(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
        prepared: Prepared?,
    ): TuneResult =
        coroutineScope {
            lateinit var mine: Deferred<TuneResult>
            mine =
                async(start = CoroutineStart.LAZY) {
                    // Checked inside the lock, after any [close] that raced this call has set the flag: a tune that
                    // close() could not see (not yet in flight) still never plays.
                    lock.withLock {
                        // A newer tune silences the old play's end from here on, before it resolves anything.
                        watching?.cancel()
                        if (closed.get()) TuneResult.Stopped else run(session, item, start, override, mine, prepared)
                    }
                }
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

    /**
     * Resolves [item] at [start] with the server's default tracks, ahead of the [tune] that will play it, and returns
     * a handle for it; null when it could not be resolved or the controller is closed. It does not touch the player
     * and does not take the tune lock, so it can run while another item plays.
     */
    suspend fun preResolve(
        session: Session,
        item: ItemId,
        start: Duration,
    ): Prepared? {
        val resolved = if (closed.get()) null else resolve(session, item, start, null, false).resolved
        val prepared = resolved?.let { Prepared(item, start, it) }
        if (prepared != null) {
            outstanding.add(prepared)
            // Added before this check and close() sets the flag before it reads the set: one of the two sees the other.
            if (closed.get()) discard(session, prepared)
        }
        return prepared?.takeUnless { closed.get() }
    }

    /**
     * Plays [prepared] with no resolve call. If its play breaks, the normal fallback applies and that re-resolves.
     *
     * A handle is gone once it is passed here, whatever the result: the call takes it on entry, and if the tune ends
     * without having started its play (cancelled while waiting for the lock, superseded, stopped, or [close]d) the
     * handle's encode is ended before this returns. So [discard] on a handle already passed here does nothing. A
     * handle that was already discarded is tuned the ordinary way, with a fresh resolve. An [override] other than null
     * cannot be honoured by a handle resolved with the default tracks, so the handle is ended and the item is tuned the
     * ordinary way.
     *
     * [start] replaces the start the handle was resolved with (a handle held a while is played from where the schedule
     * is now); null keeps it. The handle itself is not changed. Direct play and HLS both seek to it.
     */
    suspend fun tune(
        session: Session,
        prepared: Prepared,
        override: TrackOverride?,
        start: Duration? = null,
    ): TuneResult {
        // Taken before waiting for the lock, so the caller never has to wonder whether it still owns the handle.
        val taken = outstanding.remove(prepared)
        return try {
            if (taken && override == null) {
                tuneWith(session, prepared.item, start ?: prepared.start, null, prepared)
            } else {
                if (taken) {
                    // Resolved without the override, so it is of no use: end its encode now, not after this tune, so
                    // the server never runs two encodes of the item while the user waits. This one stop is awaited
                    // (the others are not). Handled, so the finally below does not stop it again.
                    prepared.adopted = true
                    stopEncodeNow(session, prepared.resolved)
                }
                tuneWith(session, prepared.item, start ?: prepared.start, override, null)
            }
        } finally {
            // A play that began owns the encode now (it is the current attempt); otherwise nothing will end it.
            if (taken && !prepared.adopted) stopEncode(session, prepared.resolved)
        }
    }

    /**
     * Gives up [prepared]: ends its server encode if it has one. Idempotent. A handle already passed to [tune] is gone,
     * so this does nothing for it.
     */
    fun discard(
        session: Session,
        prepared: Prepared,
    ) {
        if (outstanding.remove(prepared)) stopEncode(session, prepared.resolved)
    }

    /**
     * One tune's attempts. A cancel at any point stops the encode of whatever attempt is current before it
     * propagates. A cancel by a newer tune leaves the player running, because the new play replaces the item (a fast
     * flip must not blank the screen); a stop, a close or a cancel from outside stops the player too. A newer tune
     * only starts after this one has unwound, so its play is never disturbed by this clean-up. [mine] is this tune's
     * own job. [ready] is a stream resolved ahead of time for the first attempt.
     */
    @Suppress("LongParameterList") // One call's context.
    private suspend fun run(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
        mine: Deferred<TuneResult>,
        ready: Prepared?,
    ): TuneResult =
        try {
            attempts(session, item, start, override, ready)
        } catch (e: CancellationException) {
            val supersededByNewer = inFlight.get() !== mine && stopped.get() !== mine
            if (supersededByNewer) abandonEncode(session) else release(session)
            throw e
        }

    private suspend fun attempts(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
        ready: Prepared?,
    ): TuneResult {
        // An earlier tune's play that is still current (a flip after it returned Played): end its encode now. A
        // cancelled tune already released its own attempt, so nothing is stopped twice. The player is left running:
        // the new play replaces the item.
        abandonEncode(session)
        val began = clock()
        var fallback: Fallback? = null
        var attempt =
            if (ready != null) {
                // Recorded before anything can be cancelled, so a cancel still finds its encode to stop; then a cancel
                // that came during the clean-up above is seen before the player is touched.
                val resolved = ready.resolved.startingAt(start)
                current = resolved
                ready.adopted = true
                currentCoroutineContext().ensureActive()
                play(resolved, began, false)
            } else {
                attempt(session, item, start, override, false, began)
            }
        while (fallback == null && attempt.canFallBack) {
            fallback = Fallback(checkNotNull(attempt.resolved).route, attempt.fallbackCause())
            // A stop or supersede here must not lose the first attempt's encode: it is issued before the next step.
            abandonEncode(session)
            val next = attempt(session, item, start, override, true, began)
            // A fallback that could not even resolve leaves the first attempt's route on record.
            attempt = if (next is Broke && next.resolved == null) next.copy(resolved = attempt.resolved) else next
        }
        // The caller is told nothing is playing, so nothing is left running (stop() is not needed after this).
        if (attempt is Broke) release(session)
        return when (attempt) {
            is Landed -> {
                TuneResult.Played(
                    attempt.resolved,
                    attempt.firstFrameMs,
                    attempt.playingMs,
                    attempt.positionError,
                    fallback,
                    attempt.end,
                    attempt.tracksMs,
                    attempt.skippedFrames,
                    attempt.loads,
                    attempt.notes,
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
     * this), then, once it has finished unwinding, stops the player and starts ending the server's encode for the
     * current play session, if there is one (in the background: [close] awaits it). It takes the same lock as [tune],
     * so it never overlaps a tune's own steps.
     */
    suspend fun stop(session: Session) {
        inFlight.get()?.let {
            stopped.set(it)
            it.cancel()
        }
        lock.withLock { release(session) }
    }

    /**
     * Ends the controller for good: what [stop] does, but it runs to the end even when the caller's scope is already
     * cancelled, and every [tune] after it returns [TuneResult.Stopped] without playing. A host calls it when it is
     * done (a screen closing), so a scope that is cancelled after [TuneResult.Played] still stops the player and the
     * server's encode (ADR-0016, 5). It returns only when every encode stop still running in the background has
     * finished, so none is left running when the screen closes. It does not release the player: the host that created
     * it does, and only after this returns (close, then release), so the stop it makes is never sent to a released
     * player.
     */
    suspend fun close(session: Session) {
        closed.set(true)
        withContext(NonCancellable) {
            outstanding.toList().forEach { discard(session, it) }
            stop(session)
            // Every encode stop issued in the background is done before this returns (ADR-0016, 5).
            while (true) {
                val waiting =
                    stopScope.coroutineContext.job.children
                        .toList()
                if (waiting.isEmpty()) break
                waiting.joinAll()
            }
            if (ownsScope) scope.cancel()
        }
    }

    /** Stops the player and the encode of the current attempt, and forgets it. */
    private fun release(session: Session) {
        watching?.cancel()
        player.stop()
        abandonEncode(session)
    }

    /** Stops the server's encode for the current attempt, if there is one, and forgets the attempt. */
    private fun abandonEncode(session: Session) {
        val last = current
        current = null
        if (last != null) stopEncode(session, last)
    }

    private sealed interface Attempt {
        val resolved: ResolvedPlayback?
        val canFallBack: Boolean

        /** Why this attempt is being abandoned for a transcode. */
        fun fallbackCause(): FallbackCause
    }

    /** The player reached Playing. [positionError] is set when it landed off the requested start. */
    @Suppress("LongParameterList") // One play's measures, as Played reports them.
    private class Landed(
        override val resolved: ResolvedPlayback,
        val firstFrameMs: Long?,
        val playingMs: Long,
        val positionError: Duration?,
        override val canFallBack: Boolean,
        val end: Flow<PlaybackEnd>,
        val tracksMs: Long?,
        val skippedFrames: Long?,
        val loads: LoadStats?,
        val notes: Flow<PlayNote>,
    ) : Attempt {
        override fun fallbackCause() = FallbackCause.SeekMiss
    }

    /** Nothing played. [expired] means the server refused the session. */
    private data class Broke(
        override val resolved: ResolvedPlayback?,
        val reason: TuneFailure,
        val diagnostic: String?,
        val expired: Boolean = false,
        override val canFallBack: Boolean = false,
    ) : Attempt {
        override fun fallbackCause() = FallbackCause.LoadFailed(reason, diagnostic)
    }

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
        return play(resolved, began, forceTranscode)
    }

    private suspend fun play(
        resolved: ResolvedPlayback,
        began: Long,
        forceTranscode: Boolean,
    ): Attempt {
        // A cancel mid-wait is cleaned up by run().
        return when (val result = playAndWait(resolved.source)) {
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
            end = landing.end,
            tracksMs = landing.tracksMs,
            skippedFrames = landing.skippedFrames,
            loads = landing.loads,
            notes = landing.notes,
        )
    }

    private sealed interface Wait {
        data object TimedOut : Wait

        class Failed(
            val event: PlayerEvent.Failed,
        ) : Wait

        @Suppress("LongParameterList") // One play's measures, as Landed carries them.
        class Landed(
            val firstFrameAtMs: Long?,
            val playing: PlayerEvent.Playing,
            val end: Flow<PlaybackEnd>,
            val tracksMs: Long?,
            val skippedFrames: Long?,
            val loads: LoadStats?,
            val notes: Flow<PlayNote>,
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
            val firstFrame = CompletableDeferred<PlayerEvent.FirstFrame>()
            val tracksAt = AtomicLong(NO_TIME)
            val loads = AtomicReference<LoadStats?>(null)
            // Before play(), like the listener, so the end of this play cannot be missed.
            val watch = watchEnd(generation)
            // Listening starts before play(), so no event of this play is missed; events wait for its generation.
            val listener =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    player.events.collect { event ->
                        if (event.generation != generation.await()) return@collect
                        when (event) {
                            is PlayerEvent.FirstFrame -> {
                                firstFrame.complete(event)
                            }

                            is PlayerEvent.TracksReady -> {
                                if (tracksAt.compareAndSet(NO_TIME, event.atMs)) loads.set(event.loads)
                            }

                            is PlayerEvent.Playing, is PlayerEvent.Failed -> {
                                outcome.complete(event)
                            }

                            else -> {
                                Unit
                            }
                        }
                    }
                }
            var landed = false
            try {
                val playCalledAt = clock()
                generation.complete(player.play(source))
                val wait =
                    when (val event = withTimeoutOrNull(PLAYING_TIMEOUT) { outcome.await() }) {
                        is PlayerEvent.Playing -> {
                            val frame = withTimeoutOrNull(FIRST_FRAME_WAIT) { firstFrame.await() }
                            // The tracks come before the picture, so they are known by now or not at all.
                            val tracksMs = tracksAt.get().takeIf { it != NO_TIME }?.minus(playCalledAt)
                            val skipped = frame?.skippedFrames
                            Wait.Landed(frame?.atMs, event, watch.flow, tracksMs, skipped, loads.get(), watch.notes)
                        }

                        is PlayerEvent.Failed -> {
                            Wait.Failed(event)
                        }

                        else -> {
                            Wait.TimedOut
                        }
                    }
                landed = wait is Wait.Landed
                wait
            } finally {
                listener.cancel()
                // Every way out but a landed play (a failure, a timeout, a cancel, an exception) ends the watcher.
                if (!landed) watch.job.cancel()
            }
        }

    /** A running watcher: its [job], and [flow], which emits the end it saw (nothing if the watcher was cancelled). */
    private class Watch(
        val job: Job,
        val flow: Flow<PlaybackEnd>,
        val notes: Flow<PlayNote>,
    )

    /**
     * Starts watching the play whose generation [generation] will hold, in the watch scope, replacing the previous
     * watcher. It ends at the first [PlayerEvent.Ended] or [PlayerEvent.Failed] of that play, or at a
     * [PlayerEvent.Stalled] that no [PlayerEvent.Playing] follows within [PLAYING_TIMEOUT]. The result is kept, so a
     * collector that comes late still gets it. Subscribing is not dispatched, so no event after this call is missed.
     */
    private fun watchEnd(generation: CompletableDeferred<Long>): Watch {
        val result = CompletableDeferred<PlaybackEnd?>()
        val notes = Channel<PlayNote>(Channel.UNLIMITED)
        watching?.cancel()
        val job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val watcher = currentCoroutineContext()[Job]
                var stall: Job? = null
                val held = HeldPlay()
                player.events.collect { event ->
                    if (event.generation != generation.await()) return@collect
                    held.see(event, notes)
                    val end: PlaybackEnd? =
                        when (event) {
                            is PlayerEvent.Ended -> PlaybackEnd.Ended
                            is PlayerEvent.Failed -> held.failed(event)
                            else -> null
                        }
                    when {
                        end != null -> {
                            result.complete(end)
                            watcher?.cancel()
                        }

                        event is PlayerEvent.Stalled -> {
                            stall?.cancel()
                            stall =
                                launch {
                                    delay(PLAYING_TIMEOUT)
                                    result.complete(held.timedOut(event, PLAYING_TIMEOUT.inWholeMilliseconds))
                                    watcher?.cancel()
                                }
                        }

                        event is PlayerEvent.Playing -> {
                            stall?.cancel()
                        }
                    }
                }
            }
        job.invokeOnCompletion {
            result.complete(null)
            notes.close()
        }
        watching = job
        return Watch(job, flow { result.await()?.let { emit(it) } }, notes.receiveAsFlow())
    }

    /**
     * What the watcher knows of one play that reached playing, for its stall notes and its failure's numbers: when it
     * first played, how many times it stalled, and the open stall. Used from the watcher's collector only.
     */
    private class HeldPlay {
        private var playedAt = NO_TIME
        private var stallAt = NO_TIME
        private var stalls = 0

        fun see(
            event: PlayerEvent,
            notes: Channel<PlayNote>,
        ) {
            when (event) {
                is PlayerEvent.Playing -> {
                    if (playedAt == NO_TIME) playedAt = event.atMs
                    if (stallAt != NO_TIME) notes.trySend(PlayNote.Resumed(event.atMs - stallAt))
                    stallAt = NO_TIME
                }

                is PlayerEvent.Stalled -> {
                    if (playedAt != NO_TIME && ++stalls <= MAX_STALL_NOTES) {
                        stallAt = if (event.atMs < 0) NO_TIME else event.atMs
                        notes.trySend(PlayNote.Stall(event.positionMs, event.bufferedMs, since(event.atMs)))
                    }
                }

                else -> {
                    Unit
                }
            }
        }

        fun failed(event: PlayerEvent.Failed) =
            PlaybackEnd.Failed(
                event.errorCode,
                event.httpStatus ?: -1,
                since(event.atMs),
                event.positionMs,
                event.bufferedMs,
                stalls,
            )

        /** The failure of a stall that [Stalled] began and no Playing ended within [waitedMs]. */
        fun timedOut(
            stall: PlayerEvent.Stalled,
            waitedMs: Long,
        ) = PlaybackEnd.Failed(
            STALLED,
            -1,
            since(stall.atMs).let { if (it < 0) it else it + waitedMs },
            stall.positionMs,
            stall.bufferedMs,
            stalls,
        )

        private fun since(atMs: Long) = if (playedAt == NO_TIME || atMs < 0) -1L else atMs - playedAt
    }

    /**
     * Ends the server's encode for [resolved], if it started one, without making the caller wait for the server: the
     * call runs in the background and is tracked, so [close] can await it. It is outside the caller's cancellation (a
     * cancel never loses a stop) and its failure is swallowed, as a failed stop always was. A flip away from a
     * transcode would otherwise hold the next tune for the round trips of the stop (issue #392).
     */
    private fun stopEncode(
        session: Session,
        resolved: ResolvedPlayback,
    ) {
        launchStop(session, resolved)
    }

    /** [stopEncode], then waits for the server's answer, even if the caller is cancelled meanwhile. */
    private suspend fun stopEncodeNow(
        session: Session,
        resolved: ResolvedPlayback,
    ) {
        val job = launchStop(session, resolved) ?: return
        withContext(NonCancellable) { job.join() }
    }

    @Suppress("TooGenericExceptionCaught") // A failed stop must not take the app down from a background coroutine.
    private fun launchStop(
        session: Session,
        resolved: ResolvedPlayback,
    ): Job? {
        // Direct play started no encode on the server; direct stream and transcode did.
        val playSession: PlaySessionId =
            resolved.playSession?.takeUnless { resolved.route == Route.DIRECT_PLAY } ?: return null
        // On its own scope, so a cancel of the caller or of the watch scope cannot lose it; [close] awaits it.
        // Started undispatched, so a stop that does not wait is done before this returns.
        return stopScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                server.stopEncoding(session, playSession)
            } catch (
                @Suppress("SwallowedException") e: Exception,
            ) {
                // Nothing more can be done for an encode the server would not stop.
            }
        }
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
        val PLAYING_TIMEOUT = 20.seconds
        val FIRST_FRAME_WAIT = 2.seconds
        const val TIMEOUT = "Timeout"
        const val STALLED = "StallTimeout"
        const val MAX_STALL_NOTES = 20
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

/**
 * This play with its start moved to [start]. The URL does not carry the start (the player seeks, for HLS as for direct
 * play), so only the source's start and the start the seek check judges against change.
 */
private fun ResolvedPlayback.startingAt(start: Duration): ResolvedPlayback =
    copy(source = source.copy(playerStart = start), requested = start)
