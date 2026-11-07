// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.ChannelDay
import tv.stationbreak.core.domain.lineup.GapFill
import tv.stationbreak.core.domain.lineup.fillExclusions
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItem

/**
 * The reads one repair makes of [account]'s library, each once (spec 26): which items have a current row
 * (`currentIds`, asked only for ids not asked before), and each day's day pool (`ChannelDay.dayPool`: on a repaired
 * day the tail pool, read by id). And the one gap fill, [GapFill.pick] with [fillExclusions], as the schedule source
 * names a gone block: the same inputs give the same pieces.
 */
internal class Fills(
    private val library: LibraryRepository,
    private val account: LibraryAccount,
) {
    private val known = HashMap<CanonicalId, Boolean>()
    private val pools = HashMap<ChannelDay, List<ScheduleItem>>()

    /** The ids of [ids] with a current row: one `currentIds` read of those not asked about yet, none if all were. */
    suspend fun live(ids: Collection<CanonicalId>): Set<CanonicalId> {
        val unknown = ids.filterTo(HashSet()) { it !in known }
        if (unknown.isNotEmpty()) {
            val found = library.currentIds(account, unknown)
            for (id in unknown) known[id] = id in found
        }
        return ids.filterTo(HashSet()) { known[it] == true }
    }

    /** Whether [id] has a current row, as [live] last read it (false when it was never asked). */
    fun isLive(id: CanonicalId): Boolean = known[id] == true

    /** [day]'s day pool: its build's pool, or on a repaired day its tail pool, read by id once. */
    suspend fun dayPool(day: ChannelDay): List<ScheduleItem> =
        pools[day] ?: day.dayPool { ids, cutoffMs -> library.scheduleItemsOf(account, ids, cutoffMs) }.also {
            pools[day] = it
        }

    /**
     * The gap fill of `[startMs, endMs)` of [day] for the gone item [gone] from [pool]: never [gone], an item with no
     * current row, or one of [fixPieces] (the fix being filled around); each piece airs from its start (media 0).
     */
    @Suppress("LongParameterList") // The pick's inputs, one for one.
    suspend fun pick(
        day: ChannelDay,
        pool: List<ScheduleItem>,
        startMs: Long,
        endMs: Long,
        gone: CanonicalId,
        fixPieces: Set<CanonicalId>,
    ): List<KeptBlock> {
        val live = live(pool.map { it.id })
        val exclude = fillExclusions(pool, live, gone, fixPieces)
        val pieces =
            GapFill.pick(day.channel.id, pool, startMs, endMs, exclude) {
                day.settings.isAirable(it.runtimeMs, it.kind)
            }
        return pieces.filter { it.endMs > it.startMs }.map { KeptBlock(it.startMs, it.endMs, it.item.id, 0) }
    }
}
