// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.tracks.TrackPreferences
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.FallbackCause
import tv.stationbreak.core.playback.Prepared
import tv.stationbreak.core.playback.Route
import tv.stationbreak.core.playback.TrackChoice
import tv.stationbreak.core.playback.TuneController
import tv.stationbreak.core.playback.TuneFailure
import tv.stationbreak.core.playback.TuneResult
import tv.stationbreak.core.playback.facts
import kotlin.time.Duration

/**
 * [TunePort] over core:playback's [TuneController]: the live machine's whole view of the player. The host owns the
 * controller and the player (close, then release, ADR-0016); this adapter only translates.
 *
 * Nothing here logs or builds a message from a result: results can name routes and streams.
 */
@Suppress("TooManyFunctions") // One override per port call, and the result mappers.
class TunePortAdapter(
    private val controller: TuneController,
    /** Keeps a viewer's choice for the account (the track choice store): it never throws, and a failed save is lost. */
    private val keep: suspend (Session, Remembered) -> Unit = { _, _ -> },
    /** The version the account last chose for an item (#630); never throws, null for none. */
    private val version: suspend (Session, ItemId) -> MediaSourceId? = { _, _ -> null },
    /** The carried language preferences of the session's account (design doc B2.6); never throws, null for none. */
    private val preferences: suspend (Session) -> TrackPreferences? = { null },
) : TunePort {
    override suspend fun tune(
        session: Session,
        item: ItemId,
        start: Duration,
        choice: TrackChoice?,
    ): TuneOutcome {
        val asked = choice ?: remembered(session, item)
        return controller.tune(session, item, start, asked, preferences(session)).toOutcome()
    }

    /**
     * The viewer's remembered version of [item] as a choice of version alone, for a tune that has no choice of its own
     * (a choice made on this programme names its version already). The resolver plays it only while the item still
     * offers it; otherwise the server's default version plays, silently.
     */
    private suspend fun remembered(
        session: Session,
        item: ItemId,
    ): TrackChoice? = version(session, item)?.let { TrackChoice(it, null) }

    /** Never throws, so a failed look-ahead is just "nothing prepared" and the next show is tuned the ordinary way. */
    @Suppress("TooGenericExceptionCaught") // Any failure of the look-ahead means no handle; cancellation still passes.
    override suspend fun preResolve(
        session: Session,
        item: ItemId,
        start: Duration,
    ): NextHandle? =
        try {
            controller.preResolve(session, item, start, preferences(session))?.let(::NextHandle)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("SwallowedException") e: Exception,
        ) {
            null
        }

    /** Never throws: a warm that fails is a cold flip, as before. Same thread as [tune] (the controller's rule). */
    @Suppress("TooGenericExceptionCaught") // Any failure of the warm means no standby; the flip stays cold.
    override fun warmNext(
        session: Session,
        next: NextHandle,
    ): Boolean =
        try {
            controller.warmNext(next.prepared())
        } catch (
            @Suppress("SwallowedException") e: Exception,
        ) {
            false
        }

    override suspend fun tune(
        session: Session,
        next: NextHandle,
        start: Duration?,
        choice: TrackChoice?,
    ): TuneOutcome {
        val handle = next.prepared()
        val asked = choice ?: remembered(session, handle.item)
        return controller.tune(session, handle, asked, start, preferences(session)).toOutcome()
    }

    override suspend fun discard(
        session: Session,
        next: NextHandle,
    ) = controller.discard(session, next.prepared())

    override suspend fun stop(session: Session) = controller.stop(session)

    override suspend fun close(session: Session) = controller.close(session)

    override suspend fun remember(
        session: Session,
        what: Remembered,
    ) = keep(session, what)

    private fun NextHandle.prepared(): Prepared = token as Prepared

    private fun TuneResult.Played.timings() =
        TuneTimings(
            firstFrameMs = firstFrameMs,
            playingMs = playingMs,
            positionErrorMs = positionError?.inWholeMilliseconds,
            fallback =
                when (val cause = fallback?.cause) {
                    null -> null
                    FallbackCause.SeekMiss -> "seekMiss"
                    is FallbackCause.LoadFailed -> loadFailedName(cause.diagnostic)
                },
            route = resolved.route.label(),
            from = (fallback?.from ?: resolved.route).label(),
            tracksMs = tracksMs,
            skippedFrames = skippedFrames,
            loads = loads,
            av = av,
            carriedMs = resolved.carriedMs,
            warm = warm,
        )

    private fun Route.label() =
        when (this) {
            Route.DIRECT_PLAY -> "direct"
            Route.DIRECT_STREAM -> "stream"
            Route.TRANSCODE -> "transcode"
        }

    /** The stable error name, one token for logcat (an HTTP status inside it has its spaces replaced). */
    private fun loadFailedName(diagnostic: String?): String =
        if (diagnostic == null) "loadFailed" else "loadFailed:" + diagnostic.replace(' ', '_')

    private fun TuneResult.toOutcome(): TuneOutcome =
        when (this) {
            is TuneResult.Played -> TuneOutcome.Playing(end, control, timings(), notes, reportFacts(), resolved.facts())
            is TuneResult.SessionExpired -> TuneOutcome.Expired
            TuneResult.Superseded, TuneResult.Stopped -> TuneOutcome.Cancelled
            is TuneResult.Failed -> TuneOutcome.Dead(timedOut = reason == TuneFailure.TIMEOUT)
        }
}

/** The final route's facts (after any fallback) for the reporter: ids and a method, never logged. */
private fun TuneResult.Played.reportFacts() =
    ReportFacts(
        sourceId = resolved.media.id,
        playSessionId = resolved.playSession,
        playMethod =
            when (resolved.route) {
                Route.DIRECT_PLAY -> PlayMethod.DirectPlay
                Route.DIRECT_STREAM -> PlayMethod.DirectStream
                Route.TRANSCODE -> PlayMethod.Transcode
            },
        runtime = resolved.media.runtime,
    )
