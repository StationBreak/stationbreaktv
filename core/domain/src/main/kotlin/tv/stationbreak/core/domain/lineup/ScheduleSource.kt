// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import kotlin.time.Instant

/**
 * Why a switched-on channel has nothing to air in its broadcast day, so it is hidden from the guide and flipping and
 * shown with this reason in channel management (#299). It comes back by itself once the day has something.
 *
 * The entries are in **precedence order**: when more than one could explain an empty day, the first one that applies
 * is given. Each reads the channel's pool counts (`PoolCounts`).
 */
enum class HiddenReason {
    /**
     * A collection the channel reads (as a source or an exclusion) is not known yet, and no full collection pass has
     * run, so the emptiness may only mean the collections have not been listed yet.
     */
    CollectionsNotSynced,

    /** Nothing in the library matches the channel's sources. For a leftovers channel: nothing is left over. */
    NoMatches,

    /** Everything that matched was removed by the channel's exclusions. */
    AllExcluded,

    /** Everything that matched and was not excluded is above the channel's rating ceiling, or has no rating. */
    AllBlockedByCeiling,

    /** The channel has content, but none for this day (all too new, gone before it, or too long or short to air). */
    EmptyToday,
}

/**
 * A switched-on channel in its broadcast day at a moment: the day that holds that moment starts at [epochStart].
 * [hidden] is null when the day has something to air, otherwise why it is hidden (#299).
 */
data class ChannelAiring(
    val id: ChannelId,
    val number: Int,
    val name: String,
    val epochStart: Instant,
    val hidden: HiddenReason?,
)

/** Where the guide and flipping learn which channels air (spec 12). */
interface ScheduleSource {
    /**
     * Every switched-on channel of [account]'s lineup that can be scheduled, in number order, as it is at [at], built
     * from the [libraries]. Empty while there is no lineup.
     */
    suspend fun airings(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        at: Instant,
    ): List<ChannelAiring>
}
