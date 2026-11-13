// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.schedule.ScheduleItem
import java.lang.ref.WeakReference
import kotlin.time.Instant

/**
 * The [ChannelHoldingsReader] built into the app: it reads the day [source] already holds for the channel (its pool,
 * its curated weights and the titles the curation homed on it), so no curation runs a second time. A channel's hours
 * are its day pool's, the figure the channel preview totals for a draft. The titles are named by [titleNames], the
 * lookup the properties sheet's source labels use. A holding is kept for the broadcast day: until the channel's day is
 * built again, or the account changes; a channel that leaves the lineup loses its entry, and an entry never keeps a
 * day's pool alive.
 *
 * It does its work on [dispatcher], takes no lineup lock and writes nothing. A name lookup that fails costs the names:
 * the lists are left empty, and the counts stay.
 */
class BuiltInChannelHoldings(
    private val source: BuiltInScheduleSource,
    private val library: LibraryRepository,
    private val titleNames: suspend (LibraryAccount, Set<CanonicalId>) -> Map<CanonicalId, String>,
    private val dispatcher: CoroutineDispatcher,
) : ChannelHoldingsReader {
    private class Kept(
        val account: LibraryAccount,
        pool: List<ScheduleItem>,
        val n: Long,
        val holdings: ChannelHoldings?,
    ) {
        // Weak: a rolled-over or rebuilt day's pool is freed, and then the entry no longer matches any day.
        private val pool = WeakReference(pool)

        fun isOf(
            account: LibraryAccount,
            day: ChannelDay,
        ): Boolean = this.account == account && pool.get() === day.pool && n == day.n
    }

    private val lock = Any()
    private val kept = HashMap<ChannelId, Kept>()

    override suspend fun read(
        id: ChannelId,
        at: Instant,
    ): ChannelHoldings? =
        withContext(dispatcher) {
            val account = library.activeAccount().first()
            val days = if (account == null) emptyList() else source.chosenDays(account, at).second
            // A channel that left the lineup (or is off) loses its entry.
            synchronized(lock) { kept.keys.retainAll(days.mapTo(HashSet()) { it.channel.id }) }
            val day = days.firstOrNull { it.channel.id == id }
            if (account == null || day == null || day.hiddenAt(at.toEpochMilliseconds()) != null) {
                null
            } else {
                heldBy(account, day)
            }
        }

    private suspend fun heldBy(
        account: LibraryAccount,
        day: ChannelDay,
    ): ChannelHoldings? {
        val before = synchronized(lock) { kept[day.channel.id] }
        if (before != null && before.isOf(account, day)) return before.holdings
        val tally = HoldingsTally.of(day.pool, day.weights, day.homed)
        val holdings = tally?.holdings(day.epochEndMs) { titleNames(account, it) }
        // A holding whose names could not be read is not kept: the next read asks again.
        val complete = holdings == null || holdings.top.isNotEmpty()
        if (complete) synchronized(lock) { kept[day.channel.id] = Kept(account, day.pool, day.n, holdings) }
        return holdings
    }
}
