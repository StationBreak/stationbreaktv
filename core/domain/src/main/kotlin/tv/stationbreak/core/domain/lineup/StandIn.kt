// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ordering.fnv1a64
import tv.stationbreak.core.model.ordering.splitmix64
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.scheduler.Epochs

/**
 * The deterministic stand-in for a block that cannot play (spec 14, ADR-0017). It is client-local player behaviour,
 * not part of the rules format. The same channel settings, day, scheduled block start, pool and excluded set give the
 * same pick; TVs whose libraries or failures differ (so whose pool or excluded set differs) can differ for that
 * block.
 */
internal object StandIn {
    /**
     * Items to fill [lengthMs] from [pool] for the block starting at [blockStartMs] (day [n]), never any id in
     * [exclude]. Airable items only (`settings.isAirable`). Each item is keyed by
     * `splitmix64(base xor fnv1a64(id))` where `base = splitmix64(seedN(seed, n) + blockStartMs)`, and taken in
     * `(key, id)` order until their runtimes sum to at least [lengthMs]. A pool that runs out first gives what it has.
     */
    fun pick(
        settings: SchedulerSettings,
        n: Long,
        pool: List<ScheduleItem>,
        blockStartMs: Long,
        lengthMs: Long,
        exclude: Set<CanonicalId>,
    ): List<ScheduleItem> {
        val base = splitmix64(Epochs.seedN(settings.seed, n) + blockStartMs.toULong())
        val ordered =
            pool
                .filter { it.id !in exclude && settings.isAirable(it.runtimeMs) }
                .map { splitmix64(base xor fnv1a64(it.id)) to it }
                .sortedWith(compareBy<Pair<ULong, ScheduleItem>> { it.first }.thenBy { it.second.id.value })
        val picked = ArrayList<ScheduleItem>()
        var covered = 0L
        for ((_, item) in ordered) {
            if (covered >= lengthMs) break
            picked += item
            covered += item.runtimeMs
        }
        return picked
    }
}
