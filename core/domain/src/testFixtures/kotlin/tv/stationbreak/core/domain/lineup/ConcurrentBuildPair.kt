// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.model.ItemId
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The two builds a selection change's repair runs side by side (#684: what each channel admits of the new and of the
 * current selection), for a heap test on a device that cannot see core:domain's internals: a real lineup planned over
 * [library] (the starter catalog, [startersOn] channels switched on), and one call that reads both selections in one
 * walk and builds both at once on `Dispatchers.Default`, as the plan's lanes do, beside both snapshots.
 */
class ConcurrentBuildPair(
    private val library: LibraryRepository,
    private val account: LibraryAccount,
    private val startersOn: Int = STARTERS_ON,
) {
    private val service = LineupService(InMemoryLineupStore(), library, Clock.System, { 0 }, {})

    /** How many channels the prepared lineup has on, and how many it has. */
    var channelsOn = 0
        private set

    var channels = 0
        private set

    /** Plans the lineup over [libraries] and switches starters on. */
    suspend fun prepare(libraries: Set<ItemId>) {
        service.refresh(account, libraries)
        val lineup = checkNotNull(service.loaded(account)).lineup
        var on = lineup.channels.count { it.state == ChannelState.On }
        for (channel in lineup.channels) {
            if (on >= startersOn) break
            if (channel.state != ChannelState.On && channel.source is ChannelSource.Starter) {
                service.edit(account, LineupEdit.SwitchOn(channel.id, confirmedOptIn = true), libraries)
                on++
            }
        }
        channels = lineup.channels.size
        channelsOn = on
    }

    /**
     * Reads [after] and [before] in one walk at [cutoff] and builds what each channel admits of each, at the same
     * time. Gives how many channel days the two builds made in all (so the work is not left out).
     */
    suspend fun buildBoth(
        after: Set<ItemId>,
        before: Set<ItemId>,
        at: Instant,
    ): Int {
        val lineup = checkNotNull(service.loaded(account)).lineup
        val days = LineupDays(service.catalog)
        val scheduled = days.scheduled(lineup, at.toEpochMilliseconds(), null)
        val cuts = scheduled.map { it.cut }.distinct()
        val raw = snapshotsOf(library, account, listOf(after, before), cuts)
        val reads =
            listOf(after, before).indices.map { index ->
                splitByExtras(raw.mapValues { (_, snapshots) -> snapshots[index] }, cuts)
            }
        return coroutineScope {
            listOf(after, before)
                .mapIndexed { index, libraries ->
                    val (plain, filtered) = reads[index]
                    async(Dispatchers.Default) {
                        days.admitted(lineup, scheduled, plain, filtered, libraries).days.size
                    }
                }.awaitAll()
                .sum()
        }
    }

    private companion object {
        const val STARTERS_ON = 14
    }
}
