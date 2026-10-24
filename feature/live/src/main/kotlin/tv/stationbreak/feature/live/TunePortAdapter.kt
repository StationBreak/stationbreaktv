// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.FallbackCause
import tv.stationbreak.core.playback.Prepared
import tv.stationbreak.core.playback.Route
import tv.stationbreak.core.playback.TuneController
import tv.stationbreak.core.playback.TuneFailure
import tv.stationbreak.core.playback.TuneResult
import kotlin.time.Duration

/**
 * [TunePort] over core:playback's [TuneController]: the live machine's whole view of the player. The host owns the
 * controller and the player (close, then release, ADR-0016); this adapter only translates.
 *
 * Nothing here logs or builds a message from a result: results can name routes and streams.
 */
class TunePortAdapter(
    private val controller: TuneController,
) : TunePort {
    override suspend fun tune(
        session: Session,
        item: ItemId,
        start: Duration,
    ): TuneOutcome = controller.tune(session, item, start, null).toOutcome()

    /** Never throws, so a failed look-ahead is just "nothing prepared" and the next show is tuned the ordinary way. */
    @Suppress("TooGenericExceptionCaught") // Any failure of the look-ahead means no handle; cancellation still passes.
    override suspend fun preResolve(
        session: Session,
        item: ItemId,
        start: Duration,
    ): NextHandle? =
        try {
            controller.preResolve(session, item, start)?.let(::NextHandle)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("SwallowedException") e: Exception,
        ) {
            null
        }

    override suspend fun tune(
        session: Session,
        next: NextHandle,
        start: Duration?,
    ): TuneOutcome = controller.tune(session, next.prepared(), null, start).toOutcome()

    override suspend fun discard(
        session: Session,
        next: NextHandle,
    ) = controller.discard(session, next.prepared())

    override suspend fun stop(session: Session) = controller.stop(session)

    override suspend fun close(session: Session) = controller.close(session)

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
            is TuneResult.Played -> TuneOutcome.Playing(end, timings())
            is TuneResult.SessionExpired -> TuneOutcome.Expired
            TuneResult.Superseded, TuneResult.Stopped -> TuneOutcome.Cancelled
            is TuneResult.Failed -> TuneOutcome.Dead(timedOut = reason == TuneFailure.TIMEOUT)
        }
}
