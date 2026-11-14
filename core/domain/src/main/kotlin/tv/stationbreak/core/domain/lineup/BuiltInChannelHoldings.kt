// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.model.ChannelId
import java.lang.ref.WeakReference
import kotlin.time.Instant

/**
 * The [ChannelHoldingsReader] built into the app: it reads the day [source] already holds for the channel (its pool,
 * its curated weights and the titles the curation homed on it), so no curation runs a second time. A channel's hours
 * are its day pool's, the figure the channel preview totals for a draft; it describes the day as it was built, not a
 * repaired one. A holding is kept for the broadcast day: until the channel's day is built again (the entry is for that
 * very day object), or the account changes; a channel that leaves the lineup loses its entry, and an entry never keeps
 * a day alive. The titles are named by the library's `titleNames`, the lookup the sheet's source labels use.
 *
 * It does its work on [dispatcher], takes no lineup lock and writes nothing. A name lookup that fails costs the names:
 * the lists are left empty, and the counts stay.
 */
class BuiltInChannelHoldings(
    private val source: BuiltInScheduleSource,
    private val library: LibraryRepository,
    private val dispatcher: CoroutineDispatcher,
) : ChannelHoldingsReader {
    private class Kept(
        val account: LibraryAccount,
        day: ChannelDay,
        val holdings: ChannelHoldings?,
    ) {
        // The day itself, weakly: a rebuilt or rolled-over day is a new object, so a day that changed in any way (its
        // pool, weights or home set) never gets the entry of the one it replaced, and a day that is gone is freed. A
        // copy of a day that shares all three (a repair record laid over it, a rename) is a new object too, and costs
        // a recompute and a name lookup, on purpose: the key does not have to know which fields the figures read.
        private val day = WeakReference(day)

        fun isOf(
            account: LibraryAccount,
            day: ChannelDay,
        ): Boolean = this.account == account && this.day.get() === day
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
        val holdings = tally?.holdings(day.epochEndMs) { library.titleNames(account, it) }
        // A holding whose names could not be read is not kept: the next read asks again.
        val complete = holdings == null || holdings.top.isNotEmpty()
        if (complete) synchronized(lock) { kept[day.channel.id] = Kept(account, day, holdings) }
        return holdings
    }
}
