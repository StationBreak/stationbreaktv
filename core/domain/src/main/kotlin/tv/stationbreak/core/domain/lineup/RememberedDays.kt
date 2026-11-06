// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId

/**
 * The days an earlier build made, for the build of a changed lineup to take over (#589): a refresh that changes the
 * lineup changes the key of every day of it, but most days are the same (a catalog swap's rules wait for the day
 * boundary, so today of every channel on the air is unchanged, D1). A day is filed under where it was made (the
 * account, the libraries, the library's content generation, the channel and its epoch) with the [LineupDays.DayInput]
 * it was made from, and is taken over only for an equal input from the very same library state. Anything else is built.
 *
 * Not thread-safe: the caller holds its own lock. At most [CAPACITY] days are kept, least recently used out first.
 */
internal class RememberedDays {
    private data class Where(
        val account: LibraryAccount,
        val libraries: Set<ItemId>,
        val generation: Long,
        val channel: ChannelId,
        val n: Long,
    )

    private class Made(
        val input: LineupDays.DayInput,
        val day: ChannelDay,
    )

    private val made = LinkedHashMap<Where, Made>(CAPACITY, 0.75f, true)

    /**
     * The days of [scheduled] that can be taken over, by channel: made from the library [generation] for an input equal
     * to [inputs]' (from [LineupDays.dayInputsOf]).
     */
    fun reusable(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        generation: Long,
        scheduled: List<Scheduled>,
        inputs: Map<ChannelId, LineupDays.DayInput>,
    ): Map<ChannelId, ChannelDay> {
        val found = HashMap<ChannelId, ChannelDay>()
        for (s in scheduled) {
            val id = s.channel.id
            val earlier = made[Where(account, libraries, generation, id, s.n)]
            if (earlier != null && earlier.input == inputs[id]) found[id] = earlier.day
        }
        return found
    }

    /** Files [result], the days built from the library [generation] for [inputs]. */
    fun remember(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        generation: Long,
        inputs: Map<ChannelId, LineupDays.DayInput>,
        result: List<ChannelDay>,
    ) {
        for (day in result) {
            val input = inputs[day.channel.id] ?: continue
            made[Where(account, libraries, generation, day.channel.id, day.n)] = Made(input, day)
        }
        while (made.size > CAPACITY) made.remove(made.keys.first())
    }

    /** Drops the days of the accounts [of] is true for. */
    fun forget(of: (LibraryAccount) -> Boolean) {
        made.keys.removeAll { of(it.account) }
    }

    private companion object {
        /** Today and tomorrow of a lineup of a few dozen channels, for a few revisions. */
        const val CAPACITY = 512
    }
}

/**
 * The cuts a build must read a snapshot for: those of the days it builds, and all of them when it builds a leftovers
 * channel's day (it claims from the other channels' cuts too). None when every day is taken over ([reused]).
 */
internal fun cutsToRead(
    scheduled: List<Scheduled>,
    reused: Set<ChannelId>,
): List<Cut> {
    val built = scheduled.filter { it.channel.id !in reused }
    val all = built.any { it.channel.source is ChannelSource.Leftovers }
    return (if (all) scheduled else built).map { it.cut }.distinct().sorted()
}
