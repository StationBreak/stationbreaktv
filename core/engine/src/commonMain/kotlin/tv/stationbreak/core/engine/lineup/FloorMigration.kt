// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.ChannelId

/** A channel [FloorMigration] moved from [from] to [to] (#1097), kept in the lineup until the viewer has been told. */
data class MovedNotice(
    val id: ChannelId,
    val from: Int,
    val to: Int,
)

/**
 * Brings a stored lineup in line with the band floors ([ChannelFloors], #1097), once, moving a channel only up.
 *
 * The viewer's own custom channel below its floor moves to the first free number from the floor in its own band
 * (Family or Teen), else the first free one in [LineupDefaults.CUSTOM_NUMBERS]: an Adult channel goes straight there,
 * never into a built-in General slot. A built-in channel (a starter, a leftovers channel, or a retired one kept as
 * Custom) carrying a number below its band's floor (a viewer's number from before the rule, #1078) goes back to its
 * default number; when another channel holds that, to the first free number at or above the floor in its own band,
 * else (a retired one only) 600-899. Its band cap no longer applies there, so what it airs changes: it airs as its
 * recipe says, in its own band. When none of that is free, any free number from the floor up to
 * [LineupEdits.MAX_NUMBER], so no channel stays below its floor. Nothing is deleted, and nothing lands on a number
 * another channel holds, a catalog recipe or leftovers channel has as its number, or the catalog retired. Idempotent:
 * once moved, no offender remains, so a second run changes nothing and notes nothing. It runs on every refresh, so a
 * lineup written by a build before the rule is brought in line the first time this one plans it. Each move is noted
 * ([MovedNotice]).
 */
internal object FloorMigration {
    /** The channels after the move and what it moved. */
    class Result(
        val channels: List<LineupChannel>,
        val notices: List<MovedNotice>,
    )

    fun apply(
        channels: List<LineupChannel>,
        catalog: StarterCatalogView,
    ): Result {
        val offenders =
            channels
                .filter { isBelowFloor(it, catalog) }
                .sortedWith(compareBy({ it.number }, { it.id.value }))
        if (offenders.isEmpty()) return Result(channels, emptyList())
        val leaving = offenders.mapTo(HashSet()) { it.id }
        // What nothing may take: the channels that stay, then each number a moved channel lands on.
        val held = HashSet<Int>()
        held += channels.filter { it.id !in leaving }.flatMap { listOfNotNull(it.defaultNumber, it.numberOverride) }
        val reserved = catalog.recipes.map { it.number } + catalog.leftovers.map { it.number } + catalog.retiredNumbers
        val moved = HashMap<ChannelId, LineupChannel>()
        val notices = ArrayList<MovedNotice>()
        for (channel in offenders) {
            val number = target(channel, catalog, held, reserved.toSet()) ?: continue
            held += number
            moved[channel.id] = withNumber(channel, number)
            notices += MovedNotice(channel.id, checkNotNull(channel.number), number)
        }
        return Result(channels.map { moved[it.id] ?: it }, notices)
    }

    // The number [channel] moves to, or null when none is free.
    private fun target(
        channel: LineupChannel,
        catalog: StarterCatalogView,
        held: Set<Int>,
        reserved: Set<Int>,
    ): Int? {
        val floor = ChannelFloors.of(channel, catalog)
        val builtIn = ChannelCeilings.isBuiltIn(channel)
        val taken = held + reserved
        val back = channel.defaultNumber?.takeIf { builtIn && it >= floor && it !in held }
        val band = ChannelFloors.needs(channel, catalog)
        val own = if (builtIn) ChannelFloors.starterBand(channel, catalog)?.numbers ?: band.numbers else band.numbers
        val retired = channel.source is ChannelSource.Custom
        return back
            ?: firstFree(own.asSequence().filter { it >= floor && (builtIn || band != Band.General) }, taken)
            ?: firstFree(LineupDefaults.CUSTOM_NUMBERS.asSequence().filter { !builtIn || retired }, taken)
            ?: firstFree((floor..LineupEdits.MAX_NUMBER).asSequence(), taken)
    }

    // A channel numbered below its floor: the viewer's own custom, or a built-in kept below its band.
    private fun isBelowFloor(
        channel: LineupChannel,
        catalog: StarterCatalogView,
    ): Boolean {
        val number = channel.number ?: return false
        val usable = channel.source !is ChannelSource.Unusable
        return usable && number < ChannelFloors.of(channel, catalog)
    }

    private fun firstFree(
        range: Sequence<Int>,
        held: Set<Int>,
    ): Int? = range.firstOrNull { it !in held }

    // The number is the channel's override when it has one, else its default; a built-in back on its default drops it.
    private fun withNumber(
        channel: LineupChannel,
        number: Int,
    ): LineupChannel {
        val own = !ChannelCeilings.isBuiltIn(channel)
        return when {
            own && channel.numberOverride == null -> channel.copy(defaultNumber = number)
            number == channel.defaultNumber -> channel.copy(numberOverride = null)
            else -> channel.copy(numberOverride = number)
        }
    }
}
