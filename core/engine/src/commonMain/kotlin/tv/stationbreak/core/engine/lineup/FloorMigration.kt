// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.ChannelId

/**
 * Moves the viewer's own custom channels that sit below their floor ([ChannelFloors], #1097) up, once: to the first
 * free number from the floor in the channel's own band (Family or Teen), else the first free one in
 * [LineupDefaults.CUSTOM_NUMBERS]: an Adult channel goes straight there, never into a built-in General slot.
 * Never down, never deleted, never into the Kids band (a kid channel is never below its floor). Nothing lands on a
 * number another channel holds, a catalog recipe or leftovers channel has as its number, or the catalog retired. A
 * built-in channel is not moved: [ChannelCeilings] already holds its content to its band. Idempotent: once moved, no
 * offender remains, so a second run changes nothing. It runs on every refresh, so a lineup written by a build before
 * the rule is brought in line the first time this one plans it.
 */
internal object FloorMigration {
    fun apply(
        channels: List<LineupChannel>,
        catalog: StarterCatalogView,
    ): List<LineupChannel> {
        val below = channels.filter { isBelowFloor(it, catalog) }
        val offenders = below.sortedWith(compareBy({ it.number }, { it.id.value }))
        if (offenders.isEmpty()) return channels
        val held = HashSet<Int>()
        held += channels.flatMap { listOfNotNull(it.defaultNumber, it.numberOverride) }
        held += catalog.recipes.map { it.number }
        held += catalog.leftovers.map { it.number }
        held += catalog.retiredNumbers
        val moved = HashMap<ChannelId, LineupChannel>()
        for (channel in offenders) {
            val band = ChannelFloors.needs(channel, catalog)
            val number =
                firstFree(band.numbers.asSequence().filter { band != Band.General }, held)
                    ?: firstFree(LineupDefaults.CUSTOM_NUMBERS.asSequence(), held)
                    ?: continue
            held += number
            moved[channel.id] = withNumber(channel, number)
        }
        return channels.map { moved[it.id] ?: it }
    }

    // A viewer's own custom channel numbered below its floor. A built-in channel (or a retired one) is left alone.
    private fun isBelowFloor(
        channel: LineupChannel,
        catalog: StarterCatalogView,
    ): Boolean {
        val number = channel.number ?: return false
        val own = channel.source is ChannelSource.Custom && !ChannelCeilings.isBuiltIn(channel)
        return own && number < ChannelFloors.of(channel, catalog)
    }

    private fun firstFree(
        range: Sequence<Int>,
        held: Set<Int>,
    ): Int? = range.firstOrNull { it !in held }

    // A custom channel's number is its override when it has one, else its default.
    private fun withNumber(
        channel: LineupChannel,
        number: Int,
    ): LineupChannel =
        if (channel.numberOverride != null) {
            channel.copy(numberOverride = number)
        } else {
            channel.copy(defaultNumber = number)
        }
}
