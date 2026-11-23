// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.GrownUpHold
import tv.stationbreak.core.engine.RatingTier
import tv.stationbreak.core.rules.AdultMark

/**
 * The lowest number a channel may have (#1097): no Adult channel below 100, no Teen channel below 67, no Family
 * channel below 34. A channel may always move up a band, never down. The one source for every number path
 * (`LineupEdits`), the stored-lineup move ([FloorMigration]) and the #1035 gate.
 *
 * What a channel airs sets it: a kid channel ([KidStatus.isKidChannel]) 1; a viewer's own custom channel by its own
 * ceiling ([ChannelCeilings.of] through `GrownUpHold.tierLimit`: PG or less 34, TV-14 or less 67, anything else,
 * none included, 100), and an After Hours copy (adult-marked) 100; a built-in channel (a starter, a leftovers
 * channel, or a retired one kept as Custom) the floor of its default number's band, and After Hours 100. A built-in
 * channel's content is capped by [ChannelCeilings] already.
 */
object ChannelFloors {
    /** The lowest band whose numbers [channel] may use in [catalog]: Kids, Family, Teen or General. */
    fun needs(
        channel: LineupChannel,
        catalog: StarterCatalogView,
    ): Band =
        when {
            // Adult stays adult wherever it is numbered, a kid-band number included.
            isAfterHoursStarter(channel) || isAdultCopy(channel) -> Band.General

            KidStatus.isKidChannel(channel) -> Band.Kids

            ChannelCeilings.isBuiltIn(channel) -> builtInNeeds(channel, catalog)

            channel.source is ChannelSource.Custom -> ownCustomNeeds(channel, catalog)

            else -> Band.General
        }

    /** The lowest number [channel] may have in [catalog]. */
    fun of(
        channel: LineupChannel,
        catalog: StarterCatalogView,
    ): Int = needs(channel, catalog).numbers.first

    // The catalog's band of the recipe (a starter, as `FreeNumbers` places it) or leftovers channel, so a channel
    // with no default number still has its band; a retired one falls back to its default number, then General.
    private fun builtInNeeds(
        channel: LineupChannel,
        catalog: StarterCatalogView,
    ): Band {
        val source = channel.source
        val number =
            when (source) {
                is ChannelSource.Leftovers -> catalog.leftovers.firstOrNull { it.key == source.recipeKey }?.number
                else -> null
            }
        val band = starterBand(channel, catalog)?.takeIf { it.young } ?: number?.let(::bandOfDefault)
        return band ?: channel.defaultNumber?.let(::bandOfDefault) ?: Band.General
    }

    // A custom channel marked adult (a copy of After Hours, #984).
    private fun isAdultCopy(channel: LineupChannel): Boolean {
        val custom = channel.source as? ChannelSource.Custom
        return custom != null && AdultMark.isMarked(custom.rules)
    }

    // A viewer's own channel by its own ceiling, none included (that is Adult).
    private fun ownCustomNeeds(
        channel: LineupChannel,
        catalog: StarterCatalogView,
    ): Band = bandOfTier(GrownUpHold.tierLimit(ChannelCeilings.of(channel, catalog)))

    private fun bandOfTier(tier: RatingTier): Band =
        when (tier) {
            RatingTier.Family -> Band.Family
            RatingTier.Teen -> Band.Teen
            else -> Band.General
        }

    /** The band of the catalog recipe of a starter channel, null for any other or a retired one. */
    internal fun starterBand(
        channel: LineupChannel,
        catalog: StarterCatalogView,
    ): Band? {
        val source = channel.source as? ChannelSource.Starter ?: return null
        return catalog.recipes.firstOrNull { it.key == source.recipeKey }?.band
    }

    // The young band a default number is in, else General (every band from 100 up shares the floor 100).
    private fun bandOfDefault(number: Int): Band = Band.of(number)?.takeIf { it.young } ?: Band.General
}
