// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Session
import kotlin.time.Clock

/** A handle resolved for the block of [item] that starts at [blockStartMs] on one channel. */
private class Resolved(
    val handle: NextHandle,
    val item: CanonicalId,
    val blockStartMs: Long,
) {
    /** True when [program] in [block] is the very block this handle was resolved for. */
    fun isFor(
        program: Airing.Program?,
        block: AiringBlock?,
    ): Boolean = program != null && program.itemId == item && block?.startMs == blockStartMs
}

/**
 * The look-ahead of a flip (spec 15): what is on the channel one step above and one below the channel playing, resolved
 * ahead (one PlaybackInfo call and the URL, no stream or playlist request), so a flip to either skips that round trip.
 * At most one handle is held per channel, and each holds a server encode while it waits, so only the two neighbours
 * are ever kept. Confined to the main dispatcher with the rest of the run ([LiveChannel.run]), so it needs no lock.
 */
internal class Neighbours(
    private val session: Session,
    private val tuner: TunePort,
    private val schedule: ScheduleSource,
    private val clock: Clock,
) {
    private val held = linkedMapOf<ChannelId, Resolved>()

    /**
     * Once the picture of [current] plays: resolves what is on the channels one step Up and one step Down in [order]
     * (no more, no repeats), each at the position the schedule has now. A handle already held for a neighbour that is
     * still for the item on air is kept; one for a channel that is no longer a neighbour is ended first.
     */
    suspend fun prepareAround(
        current: ChannelAiring,
        order: FlipOrder,
    ) {
        val wanted =
            listOfNotNull(order.step(current.number, FlipDirection.Up), order.step(current.number, FlipDirection.Down))
                .filter { it.id != current.id }
                .distinctBy { it.id }
        val others = held.keys.filter { id -> wanted.none { it.id == id } }
        others.forEach { id -> held.remove(id)?.let { discard(it) } }
        coroutineScope { wanted.forEach { channel -> launch { prepare(channel.id) } } }
    }

    private suspend fun prepare(channel: ChannelId) {
        val now = clock.now()
        // No following blocks: only what is on now matters here.
        val block = schedule.onAir(channel, now, 0)?.now
        val program = (block?.airing as? Airing.Program)?.takeIf { it.info != null }
        val kept = held[channel]
        if (kept != null && kept.isFor(program, block)) return
        held.remove(channel)?.let { discard(it) }
        val start = block?.positionAt(now.toEpochMilliseconds())
        if (program != null && start != null) {
            tuner.preResolve(session, ItemId(program.itemId.value), start)?.let {
                held[channel] = Resolved(it, program.itemId, block.startMs)
            }
        }
    }

    /**
     * The handle for [channel] if it is still for the item on air at [onAir]; the caller tunes it from the position at
     * that time, not the one it was resolved for. A handle for an item no longer on air is discarded and null returned.
     * Every other handle is ended too: the viewer is leaving the channel they were neighbours of.
     */
    suspend fun take(
        channel: ChannelId,
        onAir: ChannelOnAir,
    ): NextHandle? {
        val mine = held.remove(channel)
        val rest = held.values.toList()
        held.clear()
        rest.forEach { discard(it) }
        if (mine == null) return null
        val fits = mine.isFor(onAir.now.airing as? Airing.Program, onAir.now)
        if (!fits) discard(mine)
        return mine.handle.takeIf { fits }
    }

    /** Ends every handle held; the sitting machine has stopped. The caller stops the tuner. */
    suspend fun release() {
        val all = held.values.toList()
        held.clear()
        all.forEach { discard(it) }
    }

    private suspend fun discard(resolved: Resolved) {
        withContext(NonCancellable) { tuner.discard(session, resolved.handle) }
    }
}
