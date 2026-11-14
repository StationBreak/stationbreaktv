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

    /**
     * The block on air when a behind play's item ends, resolved ahead on media time; played from the schedule's
     * position at the cut (spec 20, ADR-0021 4).
     */
    class CatchUp(
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
    /**
     * A neighbour channel's handle for the sitting's start: a [Pending.Retarget] for the block on air, tried when no
     * handle was prepared for it, or a [Pending.AtBoundary] for the programme after the station break on air, held
     * through the break and cut to at its start.
     */
    first: Pending? = null,
) {
    private var lead: Pending.Retarget? = first as? Pending.Retarget

    private var pending: Pending.AtBoundary? = first as? Pending.AtBoundary

    /** A behind play's catch-up, held for the next [takeFor]. */
    private var caughtUp: Pending.CatchUp? = null

    /**
     * Starts resolving [onAir]'s next block [LiveTimings.preResolveLead] before this one ends, if it is a program.
     * When station breaks come next, it resolves the programme after them [LiveTimings.preResolveLead] before that
     * programme starts, so a card of a few seconds ends into a resolved programme ([takeFor] holds the handle through
     * the breaks). A break longer than the lead is left to the break's own look-ahead. Nothing is resolved for a block
     * a handle is already held for (one kept through a break, or a neighbour's).
     */
    fun prepare(
        scope: CoroutineScope,
        onAir: ChannelOnAir,
    ): Deferred<Pending.AtBoundary?>? {
        val leadMs = timings.preResolveLead.inWholeMilliseconds
        val plan = planFor(onAir, leadMs, listOfNotNull(pending, lead)) ?: return null
        return scope.async {
            val wait = plan.resolveAtMs - now()
            if (wait > 0) delay(wait)
            val handle = tuner.preResolve(session, ItemId(plan.item.value), plan.start)
            handle?.let { Pending.AtBoundary(it, plan.item, plan.blockStartMs, plan.start) }
        }
    }

    /**
     * Holds what [prepare] finished with (nothing if it had not finished), or else the handle already kept through a
     * station break. Safe when the caller is cancelled.
     */
    suspend fun keep(ahead: Deferred<Pending.AtBoundary?>?) {
        val done = ahead.finishedOrCancel() ?: return
        pending?.takeIf { it !== done }?.let { discard(it) }
        pending = done
    }

    /**
     * Ends what [prepare] started, unused: a finished handle is discarded and a resolve still running is cancelled
     * (the controller ends its encode). Safe when the caller is cancelled.
     */
    suspend fun drop(ahead: Deferred<Pending.AtBoundary?>) {
        ahead.finishedOrCancel()?.let { discard(it) }
    }

    /**
     * Resolves the program of [block] (what will be on at [atMs], the sitting's time of a behind play's cut) for its
     * position then; null when it is not a program the library has, or the resolve gave nothing.
     */
    suspend fun resolveCatchUp(
        block: AiringBlock,
        atMs: Long,
    ): Pending.CatchUp? {
        val program = (block.airing as? Airing.Program)?.takeIf { it.info != null }
        val start = block.positionAt(atMs)
        if (program == null || start == null) return null
        return tuner.preResolve(session, ItemId(program.itemId.value), start)?.let {
            Pending.CatchUp(it, program.itemId, block.startMs)
        }
    }

    /** Holds a behind play's [handle] for the next [takeFor]; one already held is discarded. */
    suspend fun catchUp(handle: Pending.CatchUp) {
        caughtUp?.let { discard(it) }
        caughtUp = handle
    }

    /**
     * The handle for [block] when it is still exactly right; a stale or mismatched one is discarded. A held catch-up
     * comes first: it is for what is on now, played from the schedule's position at the tune.
     */
    suspend fun takeFor(block: AiringBlock): Pending? {
        takeCatchUp(block)?.let { caught ->
            pending?.let { discard(it) }
            pending = null
            return caught
        }
        val held = pending
        pending = null
        val use =
            when {
                held == null -> {
                    null
                }

                // A station break: a handle for a programme after it is kept through it, for the cut at that programme.
                keptThrough(held, block) -> {
                    pending = held
                    null
                }

                fitsCut(held, block, now()) -> {
                    held
                }

                else -> {
                    discard(held)
                    null
                }
            }
        return use ?: takeLead(block)
    }

    /** The held catch-up when it is for this block, which is still on; otherwise it is ended. */
    private suspend fun takeCatchUp(block: AiringBlock): Pending.CatchUp? {
        val held = caughtUp ?: return null
        caughtUp = null
        val program = block.airing as? Airing.Program
        val fits = program?.itemId == held.item && block.startMs == held.blockStartMs && now() < block.endMs
        if (!fits) discard(held)
        return held.takeIf { fits }
    }

    /** The lead handle when it is for exactly this block and the block is still on; otherwise it is ended. */
    private suspend fun takeLead(block: AiringBlock): Pending.Retarget? {
        val held = lead
        lead = null
        return when {
            held == null -> {
                null
            }

            // A station break: a lead for a programme after it is kept for that programme's block.
            keptThrough(held, block) -> {
                lead = held
                null
            }

            (block.airing as? Airing.Program)?.itemId == held.item &&
                block.startMs == held.blockStartMs &&
                now() < block.endMs -> {
                held
            }

            else -> {
                discard(held)
                null
            }
        }
    }

    /** Ends a handle the machine will not use (unless a tune already took it). */
    suspend fun discard(handle: Pending) {
        if (!handle.consumed) withContext(NonCancellable) { tuner.discard(session, handle.handle) }
    }

    /** Ends what is held. The caller stops the tuner. */
    suspend fun release() {
        val all = listOfNotNull(pending, lead, caughtUp)
        pending = null
        lead = null
        caughtUp = null
        all.forEach { discard(it) }
    }
}

/** How far past a block's start a prepared cut may still happen (the spec's +-2 s offset error). */
private const val CUT_SLACK_MS = 2_000L

/** What [NextUp.prepare] resolves: [item] for its block at [blockStartMs], from [start], at [resolveAtMs]. */
private class Plan(
    val item: CanonicalId,
    val blockStartMs: Long,
    val start: Duration,
    val resolveAtMs: Long,
)

/**
 * The program [NextUp.prepare] resolves, past any station breaks, and when ([leadMs] before it starts); null when
 * there is none, a handle in [held] is for it already (kept through a break, or a neighbour's), or it lies past a
 * break longer than the lead (the break's own look-ahead resolves it).
 */
private fun planFor(
    onAir: ChannelOnAir,
    leadMs: Long,
    held: List<Pending>,
): Plan? {
    val first = onAir.next.firstOrNull()
    val next = if (first?.airing == Airing.Break) onAir.next.pastBreaks() else first
    return next?.let { block ->
        val program =
            (block.airing as? Airing.Program)?.takeIf { p ->
                p.info != null && held.none { it.item == p.itemId && it.blockStartMs == block.startMs }
            }
        val start = block.positionAt(block.startMs)
        val afterBreaks = block !== first
        val resolveAt = (if (afterBreaks) block.startMs else onAir.now.endMs) - leadMs
        val inTime = !afterBreaks || resolveAt < onAir.now.endMs
        if (program != null && start != null && inTime) {
            Plan(program.itemId, block.startMs, start, resolveAt)
        } else {
            null
        }
    }
}

/** True when [held] was resolved for exactly [block], which started no more than the cut's slack before [nowMs]. */
private fun fitsCut(
    held: Pending.AtBoundary,
    block: AiringBlock,
    nowMs: Long,
): Boolean {
    val program = block.airing as? Airing.Program
    return program != null &&
        program.itemId == held.item &&
        block.startMs == held.blockStartMs &&
        block.positionAt(block.startMs) == held.start &&
        nowMs - block.startMs <= CUT_SLACK_MS
}

/** True when [block] is a station break and [held] is for a block after it: it is kept for that block. */
private fun keptThrough(
    held: Pending,
    block: AiringBlock,
): Boolean = block.airing == Airing.Break && held.blockStartMs >= block.endMs

/**
 * The programme past the station breaks at the head of [this]: the first block that is not a break, when it is a
 * program; null when the breaks lead to a slate or run out.
 */
internal fun List<AiringBlock>.pastBreaks(): AiringBlock? =
    firstOrNull { it.airing != Airing.Break }?.takeIf { it.airing is Airing.Program }

/**
 * What this resolve finished with, or null when it had not (or failed); it is cancelled either way, and a resolve
 * cancelled before it returns is the controller's to end. Safe when the caller is cancelled: a finished handle is
 * never lost to a cancellation here.
 */
internal suspend fun <T : Pending> Deferred<T?>?.finishedOrCancel(): T? {
    val ahead = this ?: return null
    return withContext(NonCancellable) {
        val done = ahead.takeIf { it.isCompleted && !it.isCancelled }?.await()
        ahead.cancel()
        done
    }
}

/**
 * One block's wall-clock look-ahead at the next block ([NextUp.prepare]). A behind play drops it (spec 20): what
 * follows its item is what is on at its cut, and no resolve or encode is held through a pause. Back at live, or on the
 * ident after an early end, it is started again for the same block.
 *
 * Its lifecycle is one block's, inside one `inBlock`: [start], then any number of [drop] and [rearm], then [keep].
 * Outside it (before [start], after [keep]) there is no block to look ahead from: [rearm] is a no-op that says so
 * (false), and [drop] has nothing to drop.
 */
internal class BoundaryLookAhead(
    private val nextUp: NextUp,
) {
    private var ahead: Deferred<Pending.AtBoundary?>? = null
    private var startedFor: Pair<CoroutineScope, ChannelOnAir>? = null

    /** Starts the look-ahead for [onAir]'s next block, in [scope] (the block's). */
    fun start(
        scope: CoroutineScope,
        onAir: ChannelOnAir,
    ) {
        startedFor = scope to onAir
        ahead = nextUp.prepare(scope, onAir)
    }

    /**
     * Starts the lifecycle for [onAir] as if dropped at once: nothing is resolved now, and a [rearm] (back at live, or
     * on the ident) starts it. A return to a kept paused place begins so (spec 20): the viewer is behind.
     */
    fun startDropped(
        scope: CoroutineScope,
        onAir: ChannelOnAir,
    ) {
        startedFor = scope to onAir
        ahead = null
    }

    /** Cancels a resolve still running, and discards a handle already made. */
    suspend fun drop() {
        val dropped = ahead ?: return
        ahead = null
        nextUp.drop(dropped)
    }

    /**
     * Starts it again after a [drop]; true when it did. False when it is still running, or outside its lifecycle
     * (before [start] or after [keep]), where it does nothing.
     */
    fun rearm(): Boolean {
        val made = startedFor?.takeIf { ahead == null } ?: return false
        ahead = nextUp.prepare(made.first, made.second)
        return true
    }

    /** The block is over: [NextUp.keep] holds what finished for the next block. Safe when the caller is cancelled. */
    suspend fun keep() {
        val kept = ahead
        ahead = null
        startedFor = null
        nextUp.keep(kept)
    }
}
