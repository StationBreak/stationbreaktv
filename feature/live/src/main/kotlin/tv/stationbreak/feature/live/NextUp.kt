// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Session
import kotlin.time.Duration

/** A program resolved ahead, for the block of [item] that starts at [blockStartMs]. */
internal sealed class Pending(
    val handle: NextHandle,
    val item: CanonicalId,
    val blockStartMs: Long,
) {
    /** Set when the handle is passed to a tune, which takes it whatever happens: it must not be discarded after. */
    var consumed = false

    /** The next block of the channel playing, resolved for its [start]: the cut at the boundary plays it as it is. */
    class AtBoundary(
        handle: NextHandle,
        item: CanonicalId,
        blockStartMs: Long,
        val start: Duration,
    ) : Pending(handle, item, blockStartMs)

    /** A neighbour channel's block: it waited, so the tune plays it from where the schedule is by then. */
    class Retarget(
        handle: NextHandle,
        item: CanonicalId,
        blockStartMs: Long,
    ) : Pending(handle, item, blockStartMs)
}

/**
 * The next program, resolved ahead so the cut at the boundary has no resolve in it. Tuning a handle consumes it, so a
 * handle is discarded here only when the machine decides not to use it: stale after a re-query, a mismatch, leaving.
 * A resolve cancelled before it returns is the controller's to end (its `close` ends every outstanding encode).
 */
internal class NextUp(
    private val session: Session,
    private val tuner: TunePort,
    private val timings: LiveTimings,
    private val now: () -> Long,
    /** A neighbour channel's handle for the first block of the sitting; tried when no handle was prepared for it. */
    private var lead: Pending.Retarget? = null,
) {
    private var pending: Pending.AtBoundary? = null

    /** Starts resolving [onAir]'s next block [LiveTimings.preResolveLead] before this one ends, if it is a program. */
    fun prepare(
        scope: CoroutineScope,
        onAir: ChannelOnAir,
    ): Deferred<Pending.AtBoundary?>? {
        val next = onAir.next.firstOrNull()
        val program = (next?.airing as? Airing.Program)?.takeIf { it.info != null }
        val start = next?.positionAt(next.startMs)
        if (next == null || program == null || start == null) return null
        return scope.async {
            val wait = onAir.now.endMs - timings.preResolveLead.inWholeMilliseconds - now()
            if (wait > 0) delay(wait)
            val handle = tuner.preResolve(session, ItemId(program.itemId.value), start)
            handle?.let { Pending.AtBoundary(it, program.itemId, next.startMs, start) }
        }
    }

    /** Holds what [prepare] finished with (nothing if it had not finished). Safe when the caller is cancelled. */
    suspend fun keep(ahead: Deferred<Pending.AtBoundary?>?) {
        withContext(NonCancellable) {
            pending = ahead?.takeIf { it.isCompleted && !it.isCancelled }?.await()
            ahead?.cancel()
        }
    }

    /** The handle prepared for [block] when it is still exactly right; a stale or mismatched one is discarded. */
    suspend fun takeFor(block: AiringBlock): Pending? {
        val held = pending
        pending = null
        val program = block.airing as? Airing.Program
        val fits =
            held != null &&
                program != null &&
                program.itemId == held.item &&
                block.startMs == held.blockStartMs &&
                block.positionAt(block.startMs) == held.start &&
                now() - block.startMs <= CUT_SLACK_MS
        if (held != null && !fits) discard(held)
        return held?.takeIf { fits } ?: takeLead(block)
    }

    /** The lead handle when it is for exactly this block and the block is still on; otherwise it is ended. */
    private suspend fun takeLead(block: AiringBlock): Pending.Retarget? {
        val held = lead ?: return null
        lead = null
        val program = block.airing as? Airing.Program
        val fits = program?.itemId == held.item && block.startMs == held.blockStartMs && now() < block.endMs
        if (!fits) discard(held)
        return held.takeIf { fits }
    }

    /** Ends a handle the machine will not use (unless a tune already took it). */
    suspend fun discard(handle: Pending) {
        if (!handle.consumed) withContext(NonCancellable) { tuner.discard(session, handle.handle) }
    }

    /** Ends what is held. The caller stops the tuner. */
    suspend fun release() {
        val all = listOfNotNull(pending, lead)
        pending = null
        lead = null
        all.forEach { discard(it) }
    }

    private companion object {
        /** How far past a block's start a prepared cut may still happen (the spec's +-2 s offset error). */
        const val CUT_SLACK_MS = 2_000L
    }
}
