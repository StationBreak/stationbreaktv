// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.ChannelId

/**
 * A channel [FloorMigration] moved from [from] to [to] on epoch day [day] (#1097), kept in the lineup until the viewer
 * has been told, or for [KEEP_DAYS] days: a notice never keeps the lineup file at the version that holds it for good.
 */
data class MovedNotice(
    val id: ChannelId,
    val from: Int,
    val to: Int,
    val day: Long,
) {
    companion object {
        /** How many days a notice is kept when the viewer does not open Channels. */
        const val KEEP_DAYS = 14L

        private const val DAY_MS = 86_400_000L

        /** The epoch day (UTC) of [ms]: what the planner's own clock says is today. */
        fun dayOf(ms: Long): Long = ms.floorDiv(DAY_MS)

        /**
         * [notices] still to be told on [today]: of a channel in [ids], at most [KEEP_DAYS] days old. A notice dated
         * after [today] (a clock that was set ahead, or a file edited by hand) was made today: it is returned with
         * [today] as its day, so it still ends [KEEP_DAYS] days on.
         */
        fun live(
            notices: List<MovedNotice>,
            ids: Set<ChannelId>,
            today: Long,
        ): List<MovedNotice> = notices.filter { it.id in ids && it.day >= today - KEEP_DAYS }.map { it.copy(day = minOf(it.day, today)) }
    }
}

/**
 * Brings a stored lineup in line with the band floors ([ChannelFloors], #1097), once, moving a channel only up.
 *
 * The viewer's own custom channel below its floor moves to the first free number from the floor in its own band
 * (Family or Teen), else the first free one in [LineupDefaults.CUSTOM_NUMBERS]: an Adult channel goes straight there,
 * never into a built-in General slot. A built-in channel (a starter, a leftovers channel, or a retired one kept as
 * Custom) carrying a number below its band's floor (a viewer's number from before the rule, #1078) goes back to its
 * default number; when another channel holds that, to the first free number at or above the floor in its own band,
 * else, for one kept as Custom because its recipe is retired, 600-899. Its band cap no longer applies there, so what it
 * airs changes: it airs as its recipe says, in its own band. When none of that is free, any free number from the
 * floor up to [LineupEdits.MAX_NUMBER], so no channel stays below its floor (one that still finds none stays where it
 * is, and its number stays held). Nothing is deleted, and nothing lands on a number another channel holds, a built-in
 * offender's default, a catalog recipe or leftovers channel has as its number, or the catalog retired; the one
 * exception is a built-in returning to its own default, which may be a retired number. Idempotent:
 * once moved, no offender remains, so a second run changes nothing and notes nothing. It runs on every refresh, so a
 * lineup written by a build before the rule is brought in line the first time this one plans it. Each move is noted
 * ([MovedNotice]) with the day it was made.
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
        day: Long,
    ): Result {
        val offenders =
            channels
                .filter { isBelowFloor(it, catalog) }
                // The viewer's own channels first: they never take a built-in's default (reserved below), so what
                // they leave is free for a built-in to return to.
                .sortedWith(compareBy({ ChannelCeilings.isBuiltIn(it) }, { it.number }, { it.id.value }))
        if (offenders.isEmpty()) return Result(channels, emptyList())
        val leaving = offenders.mapTo(HashSet()) { it.id }
        // What nothing may take: the channels that stay, each number a moved channel lands on, and an offender's own
        // number until it has moved (one that cannot move stays on it).
        val stays = FreeNumbers.usedBy(channels.filter { it.id !in leaving })
        val landed = HashSet<Int>()
        val sitting = offenders.mapNotNullTo(ArrayList()) { it.number }
        // A built-in offender's default is its own to return to: no other channel takes it first.
        val defaults = offenders.filter { ChannelCeilings.isBuiltIn(it) }.mapNotNull { it.defaultNumber }
        val reserved = (catalog.recipes.map { it.number } + catalog.leftovers.map { it.number } + defaults).toSet()
        val moved = HashMap<ChannelId, LineupChannel>()
        val notices = ArrayList<MovedNotice>()
        for (channel in offenders) {
            val number = target(channel, catalog, stays + landed + sitting, reserved) ?: continue
            landed += number
            sitting.remove(checkNotNull(channel.number))
            moved[channel.id] = withNumber(channel, number)
            notices += MovedNotice(channel.id, checkNotNull(channel.number), number, day)
        }
        return Result(channels.map { moved[it.id] ?: it }, notices)
    }

    // The number [channel] moves to, or null when none is free: [held] is what a channel holds or sits on, [reserved]
    // what the catalog and the built-in offenders' defaults keep. A built-in may return to its default when [held]
    // does not have it, whoever else is still on it.
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
            ?: firstFree(own.asSequence().filter { it >= floor && (builtIn || band != Band.General) }, taken, catalog)
            ?: firstFree(LineupDefaults.CUSTOM_NUMBERS.asSequence().filter { !builtIn || retired }, taken, catalog)
            ?: firstFree((floor..LineupEdits.MAX_NUMBER).asSequence(), taken, catalog)
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
        taken: Set<Int>,
        catalog: StarterCatalogView,
    ): Int? = FreeNumbers.free(range, taken, catalog).firstOrNull()

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
