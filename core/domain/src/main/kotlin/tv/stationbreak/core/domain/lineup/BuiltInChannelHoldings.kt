// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.first
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.time.Instant

/**
 * The [ChannelHoldingsReader] built into the app: it reads the day [source] already holds for the channel (its pool,
 * its curated weights and, when the day was built here, which titles the curation homed on it), so no curation runs a
 * second time. A channel's hours are its day pool's, the figure the channel preview totals for a draft. The titles are
 * named by [titleNames], the lookup the properties sheet's source labels use. A holding is kept for the broadcast day:
 * until the channel's day is built again, or the account changes.
 *
 * It takes no lineup lock and writes nothing. A name lookup that fails costs the names: the lines that need them
 * are left out of the holding's lists, and the counts stay.
 */
class BuiltInChannelHoldings(
    private val source: BuiltInScheduleSource,
    private val library: LibraryRepository,
    private val titleNames: suspend (LibraryAccount, Set<CanonicalId>) -> Map<CanonicalId, String>,
) : ChannelHoldingsReader {
    private class Kept(
        val account: LibraryAccount,
        val pool: List<ScheduleItem>,
        val n: Long,
        val holdings: ChannelHoldings?,
    ) {
        fun isOf(
            account: LibraryAccount,
            day: ChannelDay,
        ): Boolean = this.account == account && pool === day.pool && n == day.n
    }

    private val lock = Any()
    private val kept = HashMap<ChannelId, Kept>()

    override suspend fun read(
        id: ChannelId,
        at: Instant,
    ): ChannelHoldings? {
        val account = library.activeAccount().first() ?: return null
        val day =
            source
                .chosenDays(account, at)
                .second
                .firstOrNull { it.channel.id == id }
        return if (day == null || day.hiddenAt(at.toEpochMilliseconds()) != null) null else heldBy(account, day)
    }

    private suspend fun heldBy(
        account: LibraryAccount,
        day: ChannelDay,
    ): ChannelHoldings? {
        val before = synchronized(lock) { kept[day.channel.id] }
        if (before != null && before.isOf(account, day)) {
            return before.holdings
        }
        val holdings = HoldingsTally.of(day.pool, day.weights, day.homed)?.holdings { titleNames(account, it) }
        // A holding whose names could not be read is not kept: the next read asks again.
        val complete = holdings == null || holdings.listed.isNotEmpty()
        if (complete) synchronized(lock) { kept[day.channel.id] = Kept(account, day.pool, day.n, holdings) }
        return holdings
    }
}
