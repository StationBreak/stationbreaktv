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
 * Not thread-safe: the caller holds its own lock. What is kept is bounded to what a lineup can take over from: a build
 * drops the days of the same account and libraries from another content generation (they can never match again) and
 * those of a day that ended a whole day before its own oldest began; and at most three days of each channel of the
 * build are kept (today, tomorrow, one more), least recently used out first, so a TV short of memory pins no dead days.
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

    private val made = LinkedHashMap<Where, Made>(INITIAL_SIZE, 0.75f, true)

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

    /** Files [result], days built from the library [generation] for [inputs]; drops what can no longer match. */
    fun remember(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        generation: Long,
        inputs: Map<ChannelId, LineupDays.DayInput>,
        result: List<ChannelDay>,
    ) {
        if (result.isEmpty()) return
        // Another generation of this library can never match again; an old day has no lineup left to take it over.
        val oldest = result.minOf { it.epochStartMs }
        made.entries.removeAll {
            val k = it.key
            k.account == account && k.libraries == libraries &&
                (k.generation != generation || it.value.day.endsBefore(oldest))
        }
        for (day in result) {
            val input = inputs[day.channel.id] ?: continue
            made[Where(account, libraries, generation, day.channel.id, day.n)] = Made(input, day)
        }
        val capacity = inputs.size * DAYS_KEPT
        while (made.size > capacity) made.remove(made.keys.first())
    }

    /** For tests: how many days are remembered. */
    val size: Int get() = made.size

    /** Drops the days of the accounts [of] is true for. */
    fun forget(of: (LibraryAccount) -> Boolean) {
        made.keys.removeAll { of(it.account) }
    }

    // Whether this day ended at least a whole epoch of its own before [startMs].
    private fun ChannelDay.endsBefore(startMs: Long): Boolean = epochStartMs + 2 * settings.epochMs <= startMs

    private companion object {
        /** Today, tomorrow, and one more of every channel of a build. */
        const val DAYS_KEPT = 3
        const val INITIAL_SIZE = 64
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
