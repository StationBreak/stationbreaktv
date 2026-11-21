// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.ChannelId

/**
 * Moves an installed lineup to the numbers of a newer catalog (catalog 8, the young bands, #1002). Until then a number
 * never changed, so a stored channel kept the default it was made with; the owner waived that once, pre-release (see
 * [StarterCatalog]). It runs on a refresh that takes a newer catalog, before anything is added.
 *
 * Every starter or leftovers channel whose stored default differs from the catalog's takes the catalog's number, unless
 * a channel that is not moving holds it (a viewer's number, a custom channel): then it takes the first number free in
 * its own band, or none (it goes to the shelf, as [LineupPlanner] does for a taken default). Nothing ends up on a
 * number another channel holds. A viewer's own number (the override) stays; one equal to the new default becomes the
 * default.
 *
 * It does so only for a lineup from before [StarterCatalog.YOUNG_BANDS_VERSION] taking a catalog from it on: a stored
 * default can differ from the catalog's for another reason (a number a viewer's channel held when it was made), which
 * stays. From a catalog before that version, a custom channel numbered 34 to 99 was a kid channel (the
 * Kids band was 1 to 99). The Kids band is 1 to 33 now, and a kid channel is known by its number, so such a channel
 * moves to the first free number in 1 to 33. If that band is full it stays where it is, and its number stays reserved
 * so no starter takes it (known limit: the engine knows a kid channel by its number, so it then reads as a grown-up
 * custom channel).
 */
internal object CatalogRenumbering {
    fun apply(
        channels: List<LineupChannel>,
        fromVersion: Int,
        catalog: StarterCatalogView,
    ): List<LineupChannel> {
        // Only the move to catalog 8 renumbers. A stored default can differ from the catalog's for another reason (a
        // number a viewer's channel held when it was chosen), and that stays.
        val first = StarterCatalog.YOUNG_BANDS_VERSION
        val atTheMove = fromVersion < first && catalog.version >= first
        val moving = if (atTheMove) movingChannels(channels, catalog) else emptyList()
        val oldKids = if (atTheMove) oldKidCustoms(channels) else emptyList()
        return if (moving.isEmpty() && oldKids.isEmpty()) channels else renumbered(channels, catalog, moving, oldKids)
    }

    private fun renumbered(
        channels: List<LineupChannel>,
        catalog: StarterCatalogView,
        moving: List<Triple<LineupChannel, Int, Band>>,
        oldKids: List<LineupChannel>,
    ): List<LineupChannel> {
        val held = heldNumbers(channels, moving.mapTo(HashSet()) { it.first.id }, oldKids.mapTo(HashSet()) { it.id })
        val reserved = HashSet<Int>()
        reserved += catalog.recipes.map { it.number }
        reserved += catalog.leftovers.map { it.number }
        reserved += catalog.retiredNumbers
        val result = HashMap<ChannelId, LineupChannel>()
        // Old kid customs first: one that cannot move keeps its number, which no starter may then take.
        for (channel in oldKids) {
            val number = firstFree(Band.Kids.numbers.asSequence(), held, reserved)
            if (number == null) {
                held += listOfNotNull(channel.defaultNumber, channel.numberOverride)
            } else {
                held += number
                result[channel.id] = renumberedCustom(channel, number)
            }
        }
        for ((channel, target, band) in moving) {
            // A viewer's own number is not a clash with the channel's own new default.
            val free = target !in held || target == channel.numberOverride
            val number = if (free) target else firstFree(band.numbers.asSequence(), held, reserved)
            if (number != null) held += number
            result[channel.id] = withDefault(channel, number)
        }
        return channels.map { result[it.id] ?: it }
    }

    // The numbers nothing may take: those of channels that stay, and a moving starter's own (viewer's) number.
    private fun heldNumbers(
        channels: List<LineupChannel>,
        starters: Set<ChannelId>,
        customs: Set<ChannelId>,
    ): HashSet<Int> {
        val held = HashSet<Int>()
        for (c in channels) {
            when (c.id) {
                in customs -> Unit
                in starters -> held += listOfNotNull(c.numberOverride)
                else -> held += listOfNotNull(c.defaultNumber, c.numberOverride)
            }
        }
        return held
    }

    // The channels whose stored default is not the catalog's, with the catalog's number and the band to look in.
    private fun movingChannels(
        channels: List<LineupChannel>,
        catalog: StarterCatalogView,
    ): List<Triple<LineupChannel, Int, Band>> {
        val byId = channels.associateBy { it.id }
        val wanted = ArrayList<Triple<LineupChannel, Int, Band>>()
        for (recipe in catalog.recipes) {
            val channel = byId[LineupIds.starter(recipe.key)] ?: continue
            if (channel.source is ChannelSource.Starter && channel.differs(recipe.number)) {
                wanted += Triple(channel, recipe.number, recipe.band)
            }
        }
        for (recipe in catalog.leftovers) {
            val channel = byId[LineupIds.leftovers(recipe.key)] ?: continue
            val band = Band.of(recipe.number)
            if (channel.source is ChannelSource.Leftovers && band != null && channel.differs(recipe.number)) {
                wanted += Triple(channel, recipe.number, band)
            }
        }
        return wanted
    }

    private fun LineupChannel.differs(number: Int): Boolean = defaultNumber != null && defaultNumber != number

    private fun oldKidCustoms(channels: List<LineupChannel>): List<LineupChannel> =
        channels.filter { c ->
            val number = c.number
            c.source is ChannelSource.Custom && number != null &&
                number in Band.Family.numbers.first..Band.Teen.numbers.last
        }

    private fun firstFree(
        range: Sequence<Int>,
        held: Set<Int>,
        reserved: Set<Int>,
    ): Int? = range.firstOrNull { it !in held && it !in reserved }

    private fun withDefault(
        channel: LineupChannel,
        number: Int?,
    ): LineupChannel {
        val override = channel.numberOverride?.takeIf { it != number }
        val unnumbered = number == null && override == null && channel.state == ChannelState.On
        val state = if (unnumbered) ChannelState.Shelf else channel.state
        return channel.copy(defaultNumber = number, numberOverride = override, state = state)
    }

    // A custom channel's number is its override when it has one, else its default.
    private fun renumberedCustom(
        channel: LineupChannel,
        number: Int,
    ): LineupChannel =
        if (channel.numberOverride != null) {
            channel.copy(numberOverride = number)
        } else {
            channel.copy(defaultNumber = number)
        }
}
