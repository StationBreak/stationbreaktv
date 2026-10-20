// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import kotlin.time.Instant

/**
 * The [ScheduleSource] built into the app (spec 12): it reads the lineup from [service] and the library from
 * [library], and works the days out with the engine and the scheduler.
 *
 * Every switched-on channel's day is built from the library as held before that day's cut-off, with **one
 * `ruleSnapshot` per distinct cut-off and never `scheduleItems`** (#275). The starter and leftovers channels all share
 * the lineup's one offset and day start, so a lineup of them is one read; a custom channel with another day start adds
 * one. The built days (settings, epoch and day pool) are kept per lineup revision and cut-offs, so asking again
 * within the same days reads nothing; F1.3 and F2.1 build their schedules from them.
 */
class BuiltInScheduleSource(
    private val service: LineupService,
    private val library: LibraryRepository,
) : ScheduleSource {
    private data class Key(
        val revision: Long,
        val libraries: Set<ItemId>,
        val cutoffs: List<Long>,
    )

    private val days = LineupDays(service.catalog)
    private val lock = Any()
    private val built = LinkedHashMap<Key, List<ChannelDay>>()

    override suspend fun airings(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        at: Instant,
    ): List<ChannelAiring> =
        channelDays(account, libraries, at).map {
            ChannelAiring(
                id = it.channel.id,
                number = checkNotNull(it.channel.number),
                name = it.name,
                epochStart = Instant.fromEpochMilliseconds(it.epochStartMs),
                hidden = it.hidden,
            )
        }

    /** The switched-on channels' days at [at], in number order. */
    internal suspend fun channelDays(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        at: Instant,
    ): List<ChannelDay> {
        val held = service.loaded(account) ?: return emptyList()
        val scheduled = days.scheduled(held.lineup, at.toEpochMilliseconds())
        val key = Key(held.revision, libraries, scheduled.map { it.cutoffMs }.distinct().sorted())
        return synchronized(lock) { built[key] } ?: build(key, account, held.lineup, scheduled)
    }

    private suspend fun build(
        key: Key,
        account: LibraryAccount,
        lineup: Lineup,
        scheduled: List<Scheduled>,
    ): List<ChannelDay> {
        val snapshots = snapshotsOf(library, account, key.libraries, key.cutoffs)
        val result = days.days(lineup, scheduled, snapshots)
        synchronized(lock) {
            built[key] = result
            while (built.size > KEPT) built.remove(built.keys.first())
        }
        return result
    }

    private companion object {
        /** How many built sets of days are kept: today and tomorrow for a few revisions. */
        const val KEPT = 8
    }
}

/** One `ruleSnapshot` per cut-off in [cutoffs], by cut-off. */
internal suspend fun snapshotsOf(
    library: LibraryRepository,
    account: LibraryAccount,
    libraries: Set<ItemId>,
    cutoffs: Collection<Long>,
): Map<Long, RuleSnapshot> {
    val read = HashMap<Long, RuleSnapshot>()
    for (cutoff in cutoffs) {
        read[cutoff] = library.ruleSnapshot(account, libraries, Instant.fromEpochMilliseconds(cutoff))
    }
    return read
}
